/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */

package io.strimzi.operator.cluster.operator.resource;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.strimzi.test.TestUtils;
import org.apache.kafka.clients.admin.Config;
import org.apache.kafka.clients.admin.ConfigEntry;
import org.apache.kafka.common.config.ConfigResource;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.CoreMatchers.is;

public class KafkaConfigurationDiffTest {

    public ConfigMap getTestingDesiredConfiguration(ArrayList<ConfigEntry> additional) {
        InputStream is = getClass().getClassLoader().getResourceAsStream("desired-kafka-broker.conf");
        String desiredConfigString = TestUtils.readResource(is);

        for (ConfigEntry ce: additional) {
            desiredConfigString += "\n" + ce.name() + "=" + ce.value();
        }

        ConfigMap configMap = new ConfigMap();

        HashMap<String, String> data = new HashMap();
        data.put("server.config", desiredConfigString);
        configMap.setData(data);
        return configMap;
    }

    public Map<ConfigResource, Config> getTestingCurrentConfiguration() {
        Map<ConfigResource, Config> current = new HashMap<>();
        ConfigResource cr = new ConfigResource(ConfigResource.Type.BROKER, "2");
        InputStream is = getClass().getClassLoader().getResourceAsStream("current-kafka-broker.conf");

        List<String> configList = Arrays.asList(TestUtils.readResource(is).split(System.getProperty("line.separator")));
        List<ConfigEntry> entryList = new ArrayList<>();
        configList.forEach((entry) -> {
            String split[] = entry.split("=");
            String val = split.length == 1 ? "" : split[1];
            ConfigEntry ce = new ConfigEntry(split[0].replace("\n", ""), val, true, true, false);
            entryList.add(ce);
        });

        Config config = new Config(entryList);
        current.put(cr, config);
        return current;
    }

    @Test
    public void testEmptyDiff() {
        ArrayList<ConfigEntry> ces = new ArrayList<>();
        KafkaConfigurationDiff kcd = new KafkaConfigurationDiff(getTestingCurrentConfiguration(), getTestingDesiredConfiguration(ces));
        Set<String> result = kcd.getDiff();
        assertThat(result.size(), is(0));
        assertThat(kcd.isConfigurationDynamicallyChangeable(), is(true));
    }

    @Test
    public void testReadOnlyEntryAddedToDesired() {
        ArrayList<ConfigEntry> ces = new ArrayList<>();
        ces.add(new ConfigEntry("lala", "42", true, true, true));
        KafkaConfigurationDiff kcd = new KafkaConfigurationDiff(getTestingCurrentConfiguration(), getTestingDesiredConfiguration(ces));
        Set<String> result = kcd.getDiff();
        assertThat(result.size(), is(1));
        assertThat(kcd.isConfigurationDynamicallyChangeable(), is(false));
    }

    @Test
    public void testNonReadOnlyEntryAddedToDesired() {
        ArrayList<ConfigEntry> ces = new ArrayList<>();
        ces.add(new ConfigEntry("min.insync.replicas", "2", false, true, false));
        KafkaConfigurationDiff kcd = new KafkaConfigurationDiff(getTestingCurrentConfiguration(), getTestingDesiredConfiguration(ces));
        Set<String> result = kcd.getDiff();
        assertThat(result.size(), is(1));
        assertThat(kcd.isConfigurationDynamicallyChangeable(), is(false));
    }

    @Test
    public void testDynamicallyChangeablePropAddedToDesired() {
        ArrayList<ConfigEntry> ces = new ArrayList<>();
        ces.add(new ConfigEntry("test.property.name", "7", false, true, false));
        KafkaConfigurationDiff kcd = new KafkaConfigurationDiff(getTestingCurrentConfiguration(), getTestingDesiredConfiguration(ces));
        Set<String> result = kcd.getDiff();
        assertThat(result.size(), is(1));
        assertThat(kcd.isConfigurationDynamicallyChangeable(), is(true));
    }
}
