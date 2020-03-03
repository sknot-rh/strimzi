/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */

package io.strimzi.operator.cluster.operator.resource;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.strimzi.operator.cluster.KafkaVersionTestUtils;
import io.strimzi.operator.cluster.model.KafkaVersion;
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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.CoreMatchers.is;

public class KafkaBrokerConfigurationDiffTest {

    private static final KafkaVersion.Lookup VERSIONS = KafkaVersionTestUtils.getKafkaVersionLookup();
    private static final String KAFKA_VERSION = "2.4.0";
    KafkaVersion kafkaVersion = VERSIONS.version(KAFKA_VERSION);
    private int brokerId = 0;

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

    public Map<ConfigResource, Config> getTestingCurrentConfiguration(ArrayList<ConfigEntry> additional) {
        Map<ConfigResource, Config> current = new HashMap<>();
        ConfigResource cr = new ConfigResource(ConfigResource.Type.BROKER, "0");
        InputStream is = getClass().getClassLoader().getResourceAsStream("current-kafka-broker.conf");

        List<String> configList = Arrays.asList(TestUtils.readResource(is).split(System.getProperty("line.separator")));
        List<ConfigEntry> entryList = new ArrayList<>();
        configList.forEach(entry -> {
            String[] split = entry.split("=");
            String val = split.length == 1 ? "" : split[1];
            ConfigEntry ce = new ConfigEntry(split[0].replace("\n", ""), val, true, true, false);
            entryList.add(ce);
        });
        for (ConfigEntry ce: additional) {
            entryList.add(ce);
        }

        Config config = new Config(entryList);
        current.put(cr, config);
        return current;
    }

    @Test
    public void testDefaultValue() {
        ArrayList<ConfigEntry> ces = new ArrayList<>();
        KafkaBrokerConfigurationDiff kcd = new KafkaBrokerConfigurationDiff(getTestingCurrentConfiguration(new ArrayList<>()), getTestingDesiredConfiguration(ces), kafkaVersion, brokerId);
        assertThat(kcd.isDesiredPropertyDefaultValue("offset.metadata.max.bytes"), is(true));
    }

    @Test
    public void testNonDefaultValue() {
        ArrayList<ConfigEntry> ces = new ArrayList<>();
        ces.add(new ConfigEntry("offset.metadata.max.bytes", "4097", false, true, false));
        KafkaBrokerConfigurationDiff kcd = new KafkaBrokerConfigurationDiff(getTestingCurrentConfiguration(ces), getTestingDesiredConfiguration(new ArrayList<>()), kafkaVersion, brokerId);
        assertThat(kcd.isDesiredPropertyDefaultValue("offset.metadata.max.bytes"), is(false));
    }

    @Test
    public void testCustomPropertyAdded() {
        ArrayList<ConfigEntry> ces = new ArrayList<>();
        ces.add(new ConfigEntry("custom.property", "42", false, true, false));
        KafkaBrokerConfigurationDiff kcd = new KafkaBrokerConfigurationDiff(getTestingCurrentConfiguration(new ArrayList<>()), getTestingDesiredConfiguration(ces), kafkaVersion, brokerId);
        assertThat(kcd.getDiff().unknownConfigs(kafkaVersion).size(), is(1));
        assertThat(kcd.cannotBeUpdatedDynamically(), is(true));
    }

    @Test
    public void testCustomPropertyRemoved() {
        ArrayList<ConfigEntry> ces = new ArrayList<>();
        ces.add(new ConfigEntry("custom.property", "42", false, true, false));
        KafkaBrokerConfigurationDiff kcd = new KafkaBrokerConfigurationDiff(getTestingCurrentConfiguration(ces), getTestingDesiredConfiguration(new ArrayList<>()), kafkaVersion, brokerId);
        assertThat(kcd.getDiff().unknownConfigs(kafkaVersion).size(), is(1));
        assertThat(kcd.cannotBeUpdatedDynamically(), is(true));
    }

    @Test
    public void testCustomPropertyKept() {
        ArrayList<ConfigEntry> ces = new ArrayList<>();
        ces.add(new ConfigEntry("custom.property", "42", false, true, false));
        KafkaBrokerConfigurationDiff kcd = new KafkaBrokerConfigurationDiff(getTestingCurrentConfiguration(ces), getTestingDesiredConfiguration(ces), kafkaVersion, brokerId);
        assertThat(kcd.getDiff().unknownConfigs(kafkaVersion).size(), is(0));
        assertThat(kcd.cannotBeUpdatedDynamically(), is(false));
    }

    @Test
    public void testChangedPresentValue() {
        ArrayList<ConfigEntry> ces = new ArrayList<>();
        ces.add(new ConfigEntry("min.insync.replicas", "2", false, true, false));
        KafkaBrokerConfigurationDiff kcd = new KafkaBrokerConfigurationDiff(getTestingCurrentConfiguration(new ArrayList<>()), getTestingDesiredConfiguration(ces), kafkaVersion, brokerId);
        assertThat(kcd.getDiff().asOrderedProperties().asMap().size(), is(1));
        assertThat(kcd.cannotBeUpdatedDynamically(), is(false));
    }

    @Test
    public void testChangedPresentValueToDefault() {
        ArrayList<ConfigEntry> ces = new ArrayList<>();
        ces.add(new ConfigEntry("min.insync.replicas", "1", false, true, false));
        KafkaBrokerConfigurationDiff kcd = new KafkaBrokerConfigurationDiff(getTestingCurrentConfiguration(new ArrayList<>()), getTestingDesiredConfiguration(ces), kafkaVersion, brokerId);
        assertThat(kcd.getDiff().asOrderedProperties().asMap().size(), is(0));
        assertThat(kcd.cannotBeUpdatedDynamically(), is(false));
    }

    @Test
    public void testChangedAdvertisedListener() {
        ArrayList<ConfigEntry> ces = new ArrayList<>();
        ces.add(new ConfigEntry("advertised.listeners", "karel", false, true, false));
        KafkaBrokerConfigurationDiff kcd = new KafkaBrokerConfigurationDiff(getTestingCurrentConfiguration(new ArrayList<>()), getTestingDesiredConfiguration(ces), kafkaVersion, brokerId);
        assertThat(kcd.getDiff().asOrderedProperties().asMap().size(), is(0));
        assertThat(kcd.cannotBeUpdatedDynamically(), is(false));
    }

    @Test
    public void testChangedAdvertisedListenerFromNothingToDefault() {
        ArrayList<ConfigEntry> ces = new ArrayList<>();
        ces.add(new ConfigEntry("advertised.listeners", "null", false, true, false));
        KafkaBrokerConfigurationDiff kcd = new KafkaBrokerConfigurationDiff(getTestingCurrentConfiguration(ces), getTestingDesiredConfiguration(ces), kafkaVersion, brokerId);
        assertThat(kcd.getDiff().asOrderedProperties().asMap().size(), is(0));
        assertThat(kcd.cannotBeUpdatedDynamically(), is(false));
    }

    @Test
    public void testChangedAdvertisedListenerFromNonDefaultToDefault() {
        // advertised listeners are filled after the pod started
        ArrayList<ConfigEntry> ces = new ArrayList<>();
        ces.add(new ConfigEntry("advertised.listeners", "null", false, true, false));
        KafkaBrokerConfigurationDiff kcd = new KafkaBrokerConfigurationDiff(getTestingCurrentConfiguration(new ArrayList<>()), getTestingDesiredConfiguration(ces), kafkaVersion, brokerId);
        assertThat(kcd.getDiff().asOrderedProperties().asMap().size(), is(0));
        assertThat(kcd.cannotBeUpdatedDynamically(), is(false));
    }

    @Test
    public void testChangedZookeeperConnect() {
        ArrayList<ConfigEntry> ces = new ArrayList<>();
        ces.add(new ConfigEntry("zookeeper.connect", "karel", false, true, false));
        KafkaBrokerConfigurationDiff kcd = new KafkaBrokerConfigurationDiff(getTestingCurrentConfiguration(new ArrayList<>()), getTestingDesiredConfiguration(ces), kafkaVersion, brokerId);
        assertThat(kcd.getDiff().asOrderedProperties().asMap().size(), is(0));
        assertThat(kcd.cannotBeUpdatedDynamically(), is(false));
    }

    @Test
    public void testChangedLogDirs() {
        ArrayList<ConfigEntry> ces = new ArrayList<>();
        ces.add(new ConfigEntry("log.dirs", "/var/lib/kafka/data/karel", false, true, false));
        KafkaBrokerConfigurationDiff kcd = new KafkaBrokerConfigurationDiff(getTestingCurrentConfiguration(new ArrayList<>()), getTestingDesiredConfiguration(ces), kafkaVersion, brokerId);
        assertThat(kcd.getDiff().asOrderedProperties().asMap().size(), is(1));
        assertThat(kcd.cannotBeUpdatedDynamically(), is(true));
    }

    @Test
    public void testLogDirsNonDefaultToDefault() {
        ArrayList<ConfigEntry> ces = new ArrayList<>();
        ces.add(new ConfigEntry("log.dirs", "null", false, true, false));
        KafkaBrokerConfigurationDiff kcd = new KafkaBrokerConfigurationDiff(getTestingCurrentConfiguration(new ArrayList<>()), getTestingDesiredConfiguration(ces), kafkaVersion, brokerId);
        assertThat(kcd.getDiff().asOrderedProperties().asMap().size(), is(1));
        assertThat(kcd.cannotBeUpdatedDynamically(), is(true));
    }

    @Test
    public void testLogDirsDefaultToDefault() {
        ArrayList<ConfigEntry> ces = new ArrayList<>();
        ces.add(new ConfigEntry("log.dirs", "null", false, true, false));
        KafkaBrokerConfigurationDiff kcd = new KafkaBrokerConfigurationDiff(getTestingCurrentConfiguration(ces), getTestingDesiredConfiguration(ces), kafkaVersion, brokerId);
        assertThat(kcd.getDiff().asOrderedProperties().asMap().size(), is(0));
        assertThat(kcd.cannotBeUpdatedDynamically(), is(false));
    }

    @Test
    public void testUnchangedLogDirs() {
        ArrayList<ConfigEntry> ces = new ArrayList<>();
        ces.add(new ConfigEntry("log.dirs", "/var/lib/kafka/data/karel", false, true, false));
        KafkaBrokerConfigurationDiff kcd = new KafkaBrokerConfigurationDiff(getTestingCurrentConfiguration(ces), getTestingDesiredConfiguration(ces), kafkaVersion, brokerId);
        assertThat(kcd.getDiff().asOrderedProperties().asMap().size(), is(0));
        assertThat(kcd.cannotBeUpdatedDynamically(), is(false));
    }

    @Test
    public void testChangedInterBrokerListenerName() {
        ArrayList<ConfigEntry> ces = new ArrayList<>();
        ces.add(new ConfigEntry("inter.broker.listener.name", "david", false, true, false));
        KafkaBrokerConfigurationDiff kcd = new KafkaBrokerConfigurationDiff(getTestingCurrentConfiguration(new ArrayList<>()), getTestingDesiredConfiguration(ces), kafkaVersion, brokerId);
        assertThat(kcd.getDiff().asOrderedProperties().asMap().size(), is(1));
        assertThat(kcd.cannotBeUpdatedDynamically(), is(true));
    }

    @Test
    public void testChangedListenerSecurityProtocolMap() {
        ArrayList<ConfigEntry> ces = new ArrayList<>();
        ces.add(new ConfigEntry("listener.security.protocol.map", "david", false, true, false));
        KafkaBrokerConfigurationDiff kcd = new KafkaBrokerConfigurationDiff(getTestingCurrentConfiguration(new ArrayList<>()), getTestingDesiredConfiguration(ces), kafkaVersion, brokerId);
        assertThat(kcd.getDiff().asOrderedProperties().asMap().size(), is(1));
        assertThat(kcd.cannotBeUpdatedDynamically(), is(false));
    }

    @Test
    public void testChangedListenerSecurityProtocolMapFromNonDefault() {
        ArrayList<ConfigEntry> ces = new ArrayList<>();
        ArrayList<ConfigEntry> ces2 = new ArrayList<>();
        ces.add(new ConfigEntry("listener.security.protocol.map", "REPLICATION-9091:SSL,PLAIN-9092:SASL_PLAINTEXT,TLS-9093:SSL,EXTERNAL-9094:SSL", false, true, false));
        ces.add(new ConfigEntry("listener.security.protocol.map", "REPLICATION-9091:SSL,PLAIN-9092:SASL_PLAINTEXT,TLS-9093:SSL", false, true, false));
        KafkaBrokerConfigurationDiff kcd = new KafkaBrokerConfigurationDiff(getTestingCurrentConfiguration(ces), getTestingDesiredConfiguration(ces2), kafkaVersion, brokerId);
        assertThat(kcd.getDiff().asOrderedProperties().asMap().size(), is(1));
        assertThat(kcd.cannotBeUpdatedDynamically(), is(false));
    }

}
