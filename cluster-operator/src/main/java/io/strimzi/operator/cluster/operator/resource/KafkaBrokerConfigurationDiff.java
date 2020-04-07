/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */

package io.strimzi.operator.cluster.operator.resource;

import com.fasterxml.jackson.databind.JsonNode;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.zjsonpatch.JsonDiff;
import io.strimzi.operator.cluster.model.KafkaConfiguration;
import io.strimzi.operator.cluster.model.KafkaVersion;
import io.strimzi.operator.cluster.model.ModelUtils;
import org.apache.kafka.clients.admin.AlterConfigOp;
import org.apache.kafka.clients.admin.Config;
import org.apache.kafka.clients.admin.ConfigEntry;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import static io.fabric8.kubernetes.client.internal.PatchUtils.patchMapper;

/**
 * Computes a diff between current config supplied as a Map ConfigResource, Config and desired config supplied as a ConfigMap
 * An algorithm:
 *  1. Create map from supplied desired ConfigMap
 *  2. Fill placeholders (e.g. ${BROKER_ID}) in desired map
 *  3a. If the entry is in IGNORABLE_PROPERTIES or entry.value from desired is equal to entry.value from current, do nothing
 *      else add it to the diff
 *  3b. If entry was removed from desired, add it to the diff with default value.
 *  3c. If custom entry was removed, delete property
 *
 */
public class KafkaBrokerConfigurationDiff {

    private static final Logger log = LogManager.getLogger(KafkaBrokerConfigurationDiff.class.getName());
    private final Map<ConfigResource, Config> current;
    private Collection<ConfigEntry> currentEntries;
    private ConfigMap desired;
    private KafkaConfiguration diff;
    private KafkaVersion kafkaVersion;
    private int brokerId;
    Map<ConfigResource, Collection<AlterConfigOp>> updated = new HashMap<>();

    public static final Pattern IGNORABLE_PROPERTIES = Pattern.compile(
            "^(broker\\.id"
            + "|.*-909[1-4].ssl.keystore.location"
            + "|.*-909[1-4]\\.ssl\\.keystore\\.password"
            + "|.*-909[1-4]\\.ssl\\.keystore\\.type"
            + "|.*-909[1-4]\\.ssl\\.truststore\\.location"
            + "|.*-909[1-4]\\.ssl\\.truststore\\.password"
            + "|.*-909[1-4]\\.ssl\\.truststore\\.type"
            + "|.*-909[1-4]\\.ssl\\.client\\.auth"
            + "|.*-909[1-4]\\.scram-sha-512\\.sasl\\.jaas\\.config"
            + "|.*-909[1-4]\\.sasl\\.enabled\\.mechanisms"
            + "|advertised\\.listeners"
            + "|zookeeper\\.connect"
            //+ "|log\\.dirs"*/
            + "|super\\.users"
            + "|broker\\.rack)$");

    public KafkaBrokerConfigurationDiff(Map<ConfigResource, Config> current, ConfigMap desired, KafkaVersion kafkaVersion, int brokerId) {
        this.current = current;
        this.diff = emptyKafkaConf(); // init
        this.desired = desired;
        this.kafkaVersion = kafkaVersion;
        this.brokerId = brokerId;
        Config brokerConfigs = this.current.get(new ConfigResource(ConfigResource.Type.BROKER, Integer.toString(brokerId)));
        if (brokerConfigs == null) {
            log.warn("Failed to get broker {} configuration", brokerId);
        } else {
            this.currentEntries = brokerConfigs.entries();
            this.diff = computeDiff();
        }
    }

    private KafkaConfiguration emptyKafkaConf() {
        Map<String, Object> conf = new HashMap<>();
        return new KafkaConfiguration(conf.entrySet());
    }


    private void fillPlaceholderValue(Map<String, String> orderedProperties, String placeholder, String value) {
        orderedProperties.entrySet().forEach(entry -> {
            entry.setValue(entry.getValue().replaceAll("\\$\\{" + Pattern.quote(placeholder) + "\\}", value));
        });
    }

    public KafkaConfiguration getDiff() {
        return this.diff;
    }

    public boolean isDesiredPropertyDefaultValue(String key) {
        Optional<ConfigEntry> entry = currentEntries.stream().filter(configEntry -> configEntry.name().equals(key)).findFirst();
        if (entry.isPresent()) {
            return entry.get().isDefault();
        }
        return false;
    }

    public boolean cannotBeUpdatedDynamically() {
        // TODO all the magic of listeners combinations
        if (diff == null) {
            return false;
        } else return diff.anyReadOnly(kafkaVersion)
                || !diff.unknownConfigs(kafkaVersion).isEmpty();
    }

    /**
     * @return Map object which is used for dynamic configuration of kafka broker
     */
    public Map<ConfigResource, Collection<AlterConfigOp>> getUpdatedConfig() {
        return updated;
    }

    private boolean isIgnorableProperty(String key) {
        return IGNORABLE_PROPERTIES.matcher(key).matches();
    }

    /**
     * Computes diff between two maps. Entries in IGNORABLE_PROPERTIES are skipped
     * @return KafkaConfiguration containing all entries which were changed from current in desired configuration
     */
    public KafkaConfiguration computeDiff() {
        Map<String, String> currentMap = new HashMap<>();
        Map<String, String> difference = new HashMap<>();

        Collection<AlterConfigOp> updatedCE = new ArrayList<>();

        currentEntries.stream().forEach(e -> {
            currentMap.put(e.name(), e.value());
        });

        Map<String, String> desiredMap = ModelUtils.configMap2Map(desired, "server.config");

        fillPlaceholderValue(desiredMap, "STRIMZI_BROKER_ID", Integer.toString(brokerId));

        JsonNode source = patchMapper().valueToTree(currentMap);
        JsonNode target = patchMapper().valueToTree(desiredMap);
        JsonNode jsonDiff = JsonDiff.asJson(source, target);

        for (JsonNode d : jsonDiff) {
            String pathValue = d.get("path").asText().substring(1);

            Optional<ConfigEntry> entry = currentEntries.stream().filter(e -> e.name().equals(pathValue)).findFirst();

            if (entry.isPresent()) {
                if (d.get("op").asText().equals("remove")) {
                    if (!entry.get().source().name().equals("DEFAULT_CONFIG")) {
                        // we are deleting custom option
                        difference.put(pathValue, "deleted entry");
                        updatedCE.add(new AlterConfigOp(new ConfigEntry(pathValue, entry.get().value()), AlterConfigOp.OpType.DELETE));
                    } else if (entry.get().isDefault()) {
                        // entry is in current, is not in desired, is default -> it uses default value, skip
                        log.debug("{} not set in desired, using default value", entry.get().name());
                    } else {
                        // entry is in current, is not in desired, is not default -> it was using non-default value and was removed
                        // if the entry was custom, it should be deleted
                        if (!isIgnorableProperty(pathValue)) {
                            String defVal = KafkaConfiguration.getDefaultValueOfProperty(pathValue, kafkaVersion) == null ? "null" : KafkaConfiguration.getDefaultValueOfProperty(pathValue, kafkaVersion).toString();
                            difference.put(pathValue, defVal);
                            updatedCE.add(new AlterConfigOp(new ConfigEntry(pathValue, defVal), AlterConfigOp.OpType.DELETE));
                            log.debug("{} not set in desired, unsetting back to default {}", entry.get().name(), defVal);
                        }
                    }
                } else if (d.get("op").asText().equals("replace")) {
                    // entry is in the current, desired is updated value
                    if (!isIgnorableProperty(pathValue)) {
                        log.debug("{} has new desired value {}", entry.get().name(), desiredMap.get(entry.get().name()));
                        updatedCE.add(new AlterConfigOp(new ConfigEntry(pathValue, desiredMap.get(pathValue)), AlterConfigOp.OpType.SET));
                        difference.put(pathValue, desiredMap.get(pathValue));
                    }
                }
            } else {
                if (d.get("op").asText().equals("add")) {
                    // entry is not in the current, it is added
                    if (!isIgnorableProperty(pathValue)) {
                        log.debug("add new {} {}", pathValue, d.get("op").asText());
                        difference.put(pathValue, desiredMap.get(pathValue));
                        updatedCE.add(new AlterConfigOp(new ConfigEntry(pathValue, desiredMap.get(pathValue)), AlterConfigOp.OpType.SET));
                    }
                }
            }
        }

        difference.entrySet().forEach(e -> {
            log.info("{} broker conf differs: '{}' -> '{}'", e.getKey(), currentMap.get(e.getKey()), e.getValue());
        });

        updated.put(new ConfigResource(ConfigResource.Type.BROKER, Integer.toString(brokerId)), updatedCE);

        String diffString = difference.toString();
        diffString = diffString.substring(1, diffString.length() - 1).replace(", ", "\n");
        return KafkaConfiguration.unvalidated(diffString);
    }
}
