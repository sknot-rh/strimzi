/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */

package io.strimzi.operator.cluster.operator.resource;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.strimzi.operator.cluster.model.KafkaConfiguration;
import io.strimzi.operator.cluster.model.KafkaVersion;
import io.strimzi.operator.cluster.model.OrderedProperties;
import org.apache.kafka.clients.admin.AlterConfigOp;
import org.apache.kafka.clients.admin.Config;
import org.apache.kafka.clients.admin.ConfigEntry;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Computes a diff between current config supplied as a Map ConfigResource, Config and desired config supplied as a ConfigMap
 * An algorithm:
 *  1. Create map from supplied desired ConfigMap
 *  2. Fill placeholders (e.g. ${BROKER_ID}) in desired map
 *  3. Put all entries from desired to the diff
 *  4a. If the entry is in IGNORABLE_PROPERTIES or entry.value from desired is equal to entry.value from current, remove entry from diff
 *  4b. If entry was removed from desired, add it to the diff with default value.
 *      If custom entry was removed, add it to the diff with 'null' value.
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
    private ArrayList<String> deletedEntries;

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
        this.deletedEntries = new ArrayList<String>();
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

    private OrderedProperties configMap2Map() {
        OrderedProperties result = new OrderedProperties();
        if (desired == null || desired.getData() == null || desired.getData().get("server.config") == null) {
            return result;
        }
        String desireConf = desired.getData().get("server.config");

        List<String> list = getLinesWithoutCommentsAndEmptyLines(desireConf);
        for (String line: list) {
            String[] split = line.split("=");
            if (split.length == 1) {
                result.addPair(split[0], "");
            } else {
                result.addPair(split[0], split[1]);
            }
        }
        return result;
    }

    private List<String> getLinesWithoutCommentsAndEmptyLines(String config) {
        List<String> allLines = Arrays.asList(config.split("\\r?\\n"));
        List<String> validLines = new ArrayList<>();

        for (String line : allLines)    {
            if (!line.replace(" ", "").startsWith("#") && !line.isEmpty())   {
                validLines.add(line.replace(" ", ""));
            }
        }

        return validLines;
    }

    private void fillPlaceholderValue(OrderedProperties orderedProperties, String placeholder, String value) {
        orderedProperties.asMap().entrySet().forEach(entry -> {
            if (!isIgnorableProperty(entry.getKey())) {
                entry.setValue(entry.getValue().replaceAll("\\$\\{" + Pattern.quote(placeholder) + "\\}", value));
            }
        });
    }

    /**
     * Computes diff between two maps. Entries in IGNORABLE_PROPERTIES are skipped
     * @return KafkaConfiguration containing all entries which were changed from current in desired configuration
     */
    private KafkaConfiguration computeDiff() {
        OrderedProperties desiredMap = configMap2Map();
        Map<String, String> currentMap = new HashMap<>();
        currentEntries.stream().forEach(e -> {
            currentMap.put(e.name(), e.value());
        });

        fillPlaceholderValue(desiredMap, "STRIMZI_BROKER_ID", Integer.toString(brokerId));
        Map<String, String> diff = getMapDiff(currentMap, desiredMap);
        String diffString = diff.toString();
        diffString = diffString.substring(1, diffString.length() - 1).replace(", ", "\n");
        return KafkaConfiguration.unvalidated(diffString);
    }

    public KafkaConfiguration getDiff() {
        return this.diff;
    }

    private Map<String, String> getMapDiff(Map<String, String> current, OrderedProperties desired) {
        Map<String, String> difference = new HashMap<>(desired.asMap());

        for (Map.Entry<String, String> entry: desired.asMap().entrySet()) {
            if (isIgnorableProperty(entry.getKey()) || (current.get(entry.getKey()) != null && current.get(entry.getKey()).equalsIgnoreCase(entry.getValue()))) {
                difference.remove(entry.getKey());
            } else {
                log.info("{} differs in '{}' ---> '{}'", entry.getKey(), current.get(entry.getKey()), entry.getValue());
            }
        }

        // some value was set to non-default value, then the entry was removed from desired -> we want to use default value
        for (Map.Entry<String, String> entry: current.entrySet()) {
            if (!desired.asMap().keySet().contains(entry.getKey()) && !isDesiredPropertyDefaultValue(entry.getKey())) {
                if (!isIgnorableProperty(entry.getKey())) {
                    String defVal = KafkaConfiguration.getDefaultValueOfProperty(entry.getKey(), kafkaVersion) == null ? "null" : KafkaConfiguration.getDefaultValueOfProperty(entry.getKey(), kafkaVersion).toString();
                    log.info("{} had value {} and was removed from desired. Setting {}", entry.getKey(), entry.getValue(), defVal);
                    difference.put(entry.getKey(), defVal);
                    deletedEntries.add(entry.getKey());
                }
            }
        }
        return difference;
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
     * Loops through entire current conf and tests whether the entry is in diff. If so it tests whether is was deleted or updated.
     * @return Map object which is used for dynamic configuration of kafka broker
     */
    public Map<ConfigResource, Collection<AlterConfigOp>> getUpdatedConfig() {
        Map<ConfigResource, Collection<AlterConfigOp>> updated = new HashMap<>();
        Collection<AlterConfigOp> updatedCE = new ArrayList<>();
        currentEntries.forEach(entry -> {
            if (diff.asOrderedProperties().asMap().containsKey(entry.name())) {
                if (deletedEntries.contains(entry.name())) {
                    deletedEntries.remove(entry.name());
                    updatedCE.add(new AlterConfigOp(new ConfigEntry(entry.name(), diff.getConfigOption(entry.name())), AlterConfigOp.OpType.DELETE));
                } else {
                    updatedCE.add(new AlterConfigOp(new ConfigEntry(entry.name(), diff.getConfigOption(entry.name())), AlterConfigOp.OpType.SET));
                }
            }
        });
        updated.put(new ConfigResource(ConfigResource.Type.BROKER, Integer.toString(brokerId)), updatedCE);
        return updated;
    }

    private boolean isIgnorableProperty(String key) {
        return IGNORABLE_PROPERTIES.matcher(key).matches();
    }
}
