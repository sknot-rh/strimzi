/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */

package io.strimzi.operator.cluster.operator.resource;

import io.fabric8.kubernetes.api.model.ConfigMap;
import org.apache.kafka.clients.admin.Config;
import org.apache.kafka.clients.admin.ConfigEntry;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;

public class KafkaConfigurationDiff {

    private static final Logger log = LogManager.getLogger(KafkaConfigurationDiff.class.getName());
    private Map<ConfigResource, Config> current;
    private ConfigMap desired;

    private static final ArrayList<String> DYNAMICALLY_CHANGEABLE_ENTRIES = new ArrayList<>(Arrays.asList(
            "test.property.name"));

    public KafkaConfigurationDiff(Map<ConfigResource, Config> current, ConfigMap desired) {
        this.current = current;
        this.desired = desired;
    }

    private Properties configMap2Properties() {
        Properties result = new Properties();
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

    public Set<String> getDiff() {
        Properties desiredConfig = configMap2Properties();
        Set<String> result = new HashSet<>();
        Map.Entry<ConfigResource, Config> currentBrokerConfiguration = current.entrySet().iterator().next();

        log.info("Diff for broker id {}", currentBrokerConfiguration.getKey());
        currentBrokerConfiguration.getValue().entries().forEach(entry -> {
            String val = entry.value() == null ? "null" : entry.value();

            if (desiredConfig.containsKey(entry.name())) {
                if (desiredConfig.getProperty(entry.name()).equals(val)) {
                    // entry is in both, equal
                } else {
                    // entry is in both, not equal -> diff
                    if (desiredConfig.getProperty(entry.name()) != null) {
                        // diff is in pod dependent entry
                        if (!desiredConfig.getProperty(entry.name()).matches(".*\\$\\{.+\\}.*")) {
                            result.add(entry.name());
                            log.debug("diff {} {}/{}", entry.name(), desiredConfig.getProperty(entry.name()), entry.value());
                        } else {
                            log.debug("skipping pod-dependent entry {} with value {}", entry.name(), desiredConfig.getProperty(entry.name()));
                        }
                    } else {
                        result.add(entry.name());
                        log.debug("diff {} {}/{}", entry.name(), desiredConfig.getProperty(entry.name()), entry.value());
                    }
                }
            } else {
                // desired does not contain key, check default value
                if (entry.isDefault()) {
                    // value is default, do nothing
                } else {
                    // value is not default, not present in desired -> set it to the default
                    log.debug("diff {} {}/{}", entry.name(), desiredConfig.getProperty(entry.name()), entry.value());
                    result.add(entry.name());
                }
            }
        });

        // now we have to check entries added by user
        desiredConfig.forEach((key, value) -> {
            Optional<ConfigEntry> ce = currentBrokerConfiguration.getValue().entries().stream().filter(configEntry ->
                configEntry.name().equals(key)).findFirst();
            if (!ce.isPresent()) {
                log.debug("{} is not in the current conf", key);
                result.add((String) key);
            } else {
                if (ce.get().isReadOnly() && ce.get().value() != value) {
                    // read only value has been changed in desired cfg
                    log.debug("read only entry {} has been changed in desired cfg", key);
                    result.add((String) key);
                }
            }
        });
        return result;
    }

    public boolean isConfigurationDynamicallyChangeable() {
        Set<String> changedEntries = getDiff();
        for (String entry: changedEntries) {
            if (!DYNAMICALLY_CHANGEABLE_ENTRIES.contains(entry)) {
                return false;
            }
        }
        return true;
    }
}
