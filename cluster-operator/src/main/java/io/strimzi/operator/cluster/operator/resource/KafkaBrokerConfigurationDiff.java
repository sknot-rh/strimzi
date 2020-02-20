/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */

package io.strimzi.operator.cluster.operator.resource;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.strimzi.operator.cluster.model.KafkaConfiguration;
import io.strimzi.operator.cluster.model.KafkaVersion;
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
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

public class KafkaBrokerConfigurationDiff {

    private static final Logger log = LogManager.getLogger(KafkaBrokerConfigurationDiff.class.getName());
    private final Map<ConfigResource, Config> current;
    private Collection<ConfigEntry> currentEntries;
    private ConfigMap desired;
    private KafkaConfiguration diff;
    private KafkaVersion kafkaVersion;
    private int brokerId;

    public static final Pattern IGNORABLE_PROPERTIES = Pattern.compile("^(.*-909[1-3].ssl.keystore.location"
            + "|.*-909[1-3]\\.ssl\\.keystore\\.password"
            + "|.*-909[1-3]\\.ssl\\.keystore\\.type"
            + "|.*-909[1-3]\\.ssl\\.truststore\\.location"
            + "|.*-909[1-3]\\.ssl\\.truststore\\.password"
            + "|.*-909[1-3]\\.ssl\\.truststore\\.type"
            //+ "|advertised\\.listeners"
            + "|zookeeper\\.connect"
            //+ "|log\\.dirs"
            + "|broker\\.rack"
            + "|broker\\.id)$");

    public KafkaBrokerConfigurationDiff(Map<ConfigResource, Config> current, ConfigMap desired, KafkaVersion kafkaVersion, int brokerId) {
        this.current = current;
        this.currentEntries = current.get(new ConfigResource(ConfigResource.Type.BROKER, Integer.toString(brokerId))).entries();
        this.desired = desired;
        this.kafkaVersion = kafkaVersion;
        this.brokerId = brokerId;
        this.diff = computeDiff();
    }

    private HashMap<String, String> configMap2Map() {
        HashMap<String, String> result = new HashMap<String, String>();
        if (desired == null || desired.getData() == null || desired.getData().get("server.config") == null) {
            return result;
        }
        String desireConf = desired.getData().get("server.config");

        List<String> list = getLinesWithoutCommentsAndEmptyLines(desireConf);
        for (String line: list) {
            String[] split = line.split("=");
            if (split.length == 1) {
                result.put(split[0], "");
            } else {
                result.put(split[0], split[1]);
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

    private void fillPlaceholderValue(Map<String, String> map, String placeholder, String value) {
        map.entrySet().forEach(entry -> {
            if (!IGNORABLE_PROPERTIES.matcher(entry.getKey()).matches() && entry.getValue().contains("${" + placeholder + "}")) {
                entry.setValue(entry.getValue().replaceAll("\\$\\{" + placeholder + "\\}", value));
            }
        });
    }

    private KafkaConfiguration computeDiff() {
        HashMap<String, String> desiredMap = configMap2Map();
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

    @SuppressWarnings("checkstyle:Regexp")
    private Map<String, String> getMapDiff(Map<String, String> current, Map<String, String> desired) {
        Map<String, String> difference = new HashMap<>();
        difference.putAll(desired);
        desired.forEach((k, v) -> {
            if (IGNORABLE_PROPERTIES.matcher(k).matches() || (current.get(k) != null && current.get(k).toLowerCase(Locale.ENGLISH).equals(v.toLowerCase(Locale.ENGLISH)))) {
                difference.remove(k);
            } else {
                log.info("{} differs in '{}' ---> '{}'", k, current.get(k), v);
            }
        });

        current.entrySet().forEach(entry -> {
            // some value was set to non-default value, then the entry was removed from desired -> we want to use default value
            if (!desired.keySet().contains(entry.getKey()) && !isDesiredPropertyDefaultValue(entry.getKey(), entry.getValue())) {
                if (!IGNORABLE_PROPERTIES.matcher(entry.getKey()).matches()) {
                    String defVal = KafkaConfiguration.getDefaultValueOfProperty(entry.getKey(), kafkaVersion) == null ? "null" : KafkaConfiguration.getDefaultValueOfProperty(entry.getKey(), kafkaVersion).toString();
                    log.info("{} had value {} and was removed from desired. Setting {}", entry.getKey(), entry.getValue(), defVal);
                    difference.put(entry.getKey(), defVal);
                }
            }
        });

        return difference;
    }

    public boolean isDesiredPropertyDefaultValue(String key, String value) {
        Optional<ConfigEntry> entry = currentEntries.stream().filter(configEntry -> configEntry.name().equals(key)).findFirst();
        if (entry.isPresent()) {
            return KafkaConfiguration.isValueOfPropertyDefault(entry.get().name(), value, kafkaVersion);
        }
        return false;
    }

    public boolean dynamicChangesOnly() {
        return !isRollingUpdateNeeded();
    }

    public boolean isRollingUpdateNeeded() {
        // TODO all the magic of listeners combinations
        return diff.anyReadOnly(kafkaVersion)
                || !diff.unknownConfigs(kafkaVersion).isEmpty()
                || advertisedListernesChanged();
    }

    public boolean advertisedListernesChanged() {
        return diff.asOrderedProperties().asMap().keySet().contains("advertised.listeners");
    }

    public Map<ConfigResource, Collection<AlterConfigOp>> getUpdatedConfig() {
        Map<ConfigResource, Collection<AlterConfigOp>> updated = new HashMap<>();
        Collection<AlterConfigOp> updatedCE = new ArrayList<>();
        currentEntries.forEach(entry -> {
            if (diff.asOrderedProperties().asMap().containsKey(entry.name())) {
                //TODO all AlterConfigOp
                updatedCE.add(new AlterConfigOp(new ConfigEntry(entry.name(), diff.getConfigOption(entry.name())), AlterConfigOp.OpType.SET));
            }
        });
        updated.put(new ConfigResource(ConfigResource.Type.BROKER, Integer.toString(brokerId)), updatedCE);
        return updated;
    }
}
