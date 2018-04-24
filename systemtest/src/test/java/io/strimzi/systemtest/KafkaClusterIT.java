/*
 * Copyright 2018, Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.systemtest;

import com.jayway.jsonpath.JsonPath;
import io.fabric8.kubernetes.api.model.Event;
import io.fabric8.kubernetes.client.DefaultKubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.strimzi.test.ClusterController;
import io.strimzi.test.CmData;
import io.strimzi.test.KafkaCluster;
import io.strimzi.test.Namespace;
import io.strimzi.test.OpenShiftOnly;
import io.strimzi.test.Resources;
import io.strimzi.test.StrimziRunner;
import io.strimzi.test.Topic;
import io.strimzi.test.k8s.Oc;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.regex.Pattern;

import static io.strimzi.systemtest.k8s.Events.Created;
import static io.strimzi.systemtest.k8s.Events.Failed;
import static io.strimzi.systemtest.k8s.Events.FailedSync;
import static io.strimzi.systemtest.k8s.Events.FailedValidation;
import static io.strimzi.systemtest.k8s.Events.Killing;
import static io.strimzi.systemtest.k8s.Events.Pulled;
import static io.strimzi.systemtest.k8s.Events.Scheduled;
import static io.strimzi.systemtest.k8s.Events.Started;
import static io.strimzi.systemtest.k8s.Events.SuccessfulDelete;
import static io.strimzi.systemtest.k8s.Events.Unhealthy;
import static io.strimzi.systemtest.matchers.Matchers.hasAllOfReasons;
import static io.strimzi.systemtest.matchers.Matchers.hasNoneOfReasons;
import static io.strimzi.systemtest.matchers.Matchers.valueOfCmEquals;
import static io.strimzi.test.TestUtils.map;
import static junit.framework.TestCase.assertTrue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;

@RunWith(StrimziRunner.class)
@Namespace(KafkaClusterIT.NAMESPACE)
@ClusterController
public class KafkaClusterIT extends AbstractClusterIT {

    private static final Logger LOGGER = LoggerFactory.getLogger(KafkaClusterIT.class);

    public static final String NAMESPACE = "kafka-cluster-test";
    private static final String CLUSTER_NAME = "my-cluster";
    private static final String TOPIC_NAME = "test-topic";

    @BeforeClass
    public static void waitForCc() {
        // TODO Build this into the annos, or get rid of the annos
        //cluster.client().waitForDeployment("strimzi-cluster-controller");
    }

    @Test
    @Resources(value = "../examples/templates/cluster-controller", asAdmin = true)
    @OpenShiftOnly
    public void testDeployKafkaClusterViaTemplate() {
        Oc oc = (Oc) this.kubeClient;
        String clusterName = "openshift-my-cluster";
        oc.newApp("strimzi-ephemeral", map("CLUSTER_NAME", clusterName));
        oc.waitForStatefulSet(zookeeperClusterName(clusterName), 1);
        oc.waitForStatefulSet(kafkaClusterName(clusterName), 3);
        oc.deleteByName("cm", clusterName);
        oc.waitForResourceDeletion("statefulset", kafkaClusterName(clusterName));
        oc.waitForResourceDeletion("statefulset", zookeeperClusterName(clusterName));
    }

    @Test
    @KafkaCluster(name = CLUSTER_NAME, kafkaNodes = 3)
    public void testKafkaScaleUpScaleDown() {
        // kafka cluster already deployed via annotation
        LOGGER.info("Running kafkaScaleUpScaleDown {}", CLUSTER_NAME);

        //kubeClient.waitForStatefulSet(kafkaStatefulSetName(clusterName), 3);

        final int initialReplicas = client.apps().statefulSets().inNamespace(NAMESPACE).withName(kafkaClusterName(CLUSTER_NAME)).get().getStatus().getReplicas();
        assertEquals(3, initialReplicas);

        // scale up
        final int scaleTo = initialReplicas + 1;
        final int newPodId = initialReplicas;
        final int newBrokerId = newPodId;
        final String newPodName = kafkaPodName(CLUSTER_NAME,  newPodId);
        final String firstPodName = kafkaPodName(CLUSTER_NAME,  0);
        LOGGER.info("Scaling up to {}", scaleTo);
        replaceCm(CLUSTER_NAME, "kafka-nodes", String.valueOf(initialReplicas + 1));
        kubeClient.waitForStatefulSet(kafkaClusterName(CLUSTER_NAME), initialReplicas + 1);

        // Test that the new broker has joined the kafka cluster by checking it knows about all the other broker's API versions
        // (execute bash because we want the env vars expanded in the pod)
        String versions = getBrokerApiVersions(newPodName);
        for (int brokerId = 0; brokerId < scaleTo; brokerId++) {
            assertTrue(versions, versions.indexOf("(id: " + brokerId + " rack: ") >= 0);
        }

        //Test that the new pod does not have errors or failures in events
        List<Event> events = getEvents("Pod", newPodName);
        assertThat(events, hasAllOfReasons(Scheduled, Pulled, Created, Started));
        assertThat(events, hasNoneOfReasons(Failed, Unhealthy, FailedSync, FailedValidation));

        // TODO Check logs for errors

        // scale down
        LOGGER.info("Scaling down");
        //client.apps().statefulSets().inNamespace(NAMESPACE).withName(kafkaStatefulSetName(CLUSTER_NAME)).scale(initialReplicas, true);
        replaceCm(CLUSTER_NAME, "kafka-nodes", String.valueOf(initialReplicas));
        kubeClient.waitForStatefulSet(kafkaClusterName(CLUSTER_NAME), initialReplicas);

        final int finalReplicas = client.apps().statefulSets().inNamespace(NAMESPACE).withName(kafkaClusterName(CLUSTER_NAME)).get().getStatus().getReplicas();
        assertEquals(initialReplicas, finalReplicas);
        versions = getBrokerApiVersions(firstPodName);

        assertTrue("Expect the added broker, " + newBrokerId + ",  to no longer be present in output of kafka-broker-api-versions.sh",
                versions.indexOf("(id: " + newBrokerId + " rack: ") == -1);

        //Test that the new broker has event 'Killing'
        assertThat(getEvents("Pod", newPodName), hasAllOfReasons(Killing));
        //Test that stateful set has event 'SuccessfulDelete'
        assertThat(getEvents("StatefulSet", kafkaClusterName(CLUSTER_NAME)), hasAllOfReasons(SuccessfulDelete));

        // TODO Check logs for errors
    }

    @Test
    @KafkaCluster(name = CLUSTER_NAME, kafkaNodes = 1, zkNodes = 1)
    public void testZookeeperScaleUpScaleDown() {
        // kafka cluster already deployed via annotation
        LOGGER.info("Running zookeeperScaleUpScaleDown with cluster {}", CLUSTER_NAME);
        //kubeClient.waitForStatefulSet(zookeeperStatefulSetName(CLUSTER_NAME), 1);
        KubernetesClient client = new DefaultKubernetesClient();
        final int initialReplicas = client.apps().statefulSets().inNamespace(NAMESPACE).withName(zookeeperClusterName(CLUSTER_NAME)).get().getStatus().getReplicas();
        assertEquals(1, initialReplicas);

        // scale up
        final int scaleTo = initialReplicas + 2;
        final int[] newPodIds = {initialReplicas, initialReplicas + 1};
        final String[] newPodName = {
                zookeeperPodName(CLUSTER_NAME,  newPodIds[0]),
                zookeeperPodName(CLUSTER_NAME,  newPodIds[1])
        };
        final String firstPodName = zookeeperPodName(CLUSTER_NAME,  0);
        LOGGER.info("Scaling up to {}", scaleTo);
        replaceCm(CLUSTER_NAME, "zookeeper-nodes", String.valueOf(scaleTo));
        kubeClient.waitForPod(newPodName[0]);
        kubeClient.waitForPod(newPodName[1]);

        // check the new node is either in leader or follower state
        waitForZkMntr(firstPodName, Pattern.compile("zk_server_state\\s+(leader|follower)"));
        waitForZkMntr(newPodName[0], Pattern.compile("zk_server_state\\s+(leader|follower)"));
        waitForZkMntr(newPodName[1], Pattern.compile("zk_server_state\\s+(leader|follower)"));

        // TODO Check logs for errors
        //Test that first pod does not have errors or failures in events
        List<Event> eventsForFirstPod = getEvents("Pod", newPodName[0]);
        assertThat(eventsForFirstPod, hasAllOfReasons(Scheduled, Pulled, Created, Started));
        assertThat(eventsForFirstPod, hasNoneOfReasons(Failed, Unhealthy, FailedSync, FailedValidation));

        //Test that second pod does not have errors or failures in events
        List<Event> eventsForSecondPod = getEvents("Pod", newPodName[1]);
        assertThat(eventsForSecondPod, hasAllOfReasons(Scheduled, Pulled, Created, Started));
        assertThat(eventsForSecondPod, hasNoneOfReasons(Failed, Unhealthy, FailedSync, FailedValidation));

        // scale down
        LOGGER.info("Scaling down");
        replaceCm(CLUSTER_NAME, "zookeeper-nodes", String.valueOf(1));
        kubeClient.waitForResourceDeletion("po", zookeeperPodName(CLUSTER_NAME,  1));
        // Wait for the one remaining node to enter standalone mode
        waitForZkMntr(firstPodName, Pattern.compile("zk_server_state\\s+standalone"));

        //Test that the second pod has event 'Killing'
        assertThat(getEvents("Pod", newPodName[1]), hasAllOfReasons(Killing));
        //Test that stateful set has event 'SuccessfulDelete'
        assertThat(getEvents("StatefulSet", zookeeperClusterName(CLUSTER_NAME)), hasAllOfReasons(SuccessfulDelete));
        // TODO Check logs for errors
    }

    @Test
    @KafkaCluster(name = CLUSTER_NAME, kafkaNodes = 1, config = {
            @CmData(key = "zookeeper-healthcheck-delay", value = "30"),
            @CmData(key = "zookeeper-healthcheck-timeout", value = "10"),
            @CmData(key = "kafka-healthcheck-delay", value = "30"),
            @CmData(key = "kafka-healthcheck-timeout", value = "10"),
            @CmData(key = "kafka-config", value = "{\"default.replication.factor\": 1,\"offsets.topic.replication.factor\": 1,\"transaction.state.log.replication.factor\": 1}")
    })
    public void testClusterWithCustomParameters() {
        // kafka cluster already deployed via annotation
        LOGGER.info("Running clusterWithCustomParameters with cluster {}", CLUSTER_NAME);

        //TODO Add assertions to check that Kafka brokers have a custom configuration
        String jsonString = kubeClient.get("cm", CLUSTER_NAME);
        assertThat(jsonString, valueOfCmEquals("zookeeper-healthcheck-delay", "30"));
        assertThat(jsonString, valueOfCmEquals("zookeeper-healthcheck-timeout", "10"));
        assertThat(jsonString, valueOfCmEquals("kafka-healthcheck-delay", "30"));
        assertThat(jsonString, valueOfCmEquals("kafka-healthcheck-timeout", "10"));
        assertThat(jsonString, valueOfCmEquals("kafka-config", "{\"default.replication.factor\": 1,\"offsets.topic.replication.factor\": 1,\"transaction.state.log.replication.factor\": 1}"));
    }

    @Test
    @OpenShiftOnly
    @KafkaCluster(name = "my-cluster-persistent", kafkaNodes = 2, zkNodes = 2, config = {
        @CmData(key = "kafka-storage", value = "{ \"type\": \"persistent-claim\", \"size\": \"1Gi\", \"delete-claim\": false }"),
        @CmData(key = "zookeeper-storage", value = "{ \"type\": \"persistent-claim\", \"size\": \"1Gi\", \"delete-claim\": false }"),
        @CmData(key = "zookeeper-healthcheck-delay", value = "30"),
        @CmData(key = "zookeeper-healthcheck-timeout", value = "15"),
        @CmData(key = "kafka-healthcheck-delay", value = "30"),
        @CmData(key = "kafka-healthcheck-timeout", value = "15"),
        @CmData(key = "kafka-config", value = "{\"default.replication.factor\": 1,\"offsets.topic.replication.factor\": 1,\"transaction.state.log.replication.factor\": 1}")
    })
    public void testDeployKafkaOnPersistentStorage() {
        String clusterName = "my-cluster-persistent";
        int expectedZKPods = 2;
        int expectedKafkaPods = 2;
        Oc oc = (Oc) this.kubeClient;

        List<String> persistentVolumeClaimNames = oc.list("pvc");
        assertTrue(persistentVolumeClaimNames.size() == (expectedZKPods + expectedKafkaPods));

        //Checking Persistent volume claims for Zookeeper nodes
        for (int i = 0; i < expectedZKPods; i++) {
            assertTrue(persistentVolumeClaimNames.contains(zookeeperPVCName(clusterName, i)));
        }

        //Checking Persistent volume claims for Kafka nodes
        for (int i = 0; i < expectedZKPods; i++) {
            assertTrue(persistentVolumeClaimNames.contains(kafkaPVCName(clusterName, i)));
        }

        String configMap = kubeClient.get("cm", clusterName);
        assertThat(configMap, valueOfCmEquals("zookeeper-healthcheck-delay", "30"));
        assertThat(configMap, valueOfCmEquals("zookeeper-healthcheck-timeout", "15"));
        assertThat(configMap, valueOfCmEquals("kafka-healthcheck-delay", "30"));
        assertThat(configMap, valueOfCmEquals("kafka-healthcheck-timeout", "15"));
        assertThat(configMap, valueOfCmEquals("kafka-config", "{\"default.replication.factor\": 1,\"offsets.topic.replication.factor\": 1,\"transaction.state.log.replication.factor\": 1}"));
    }

    @Test
    @OpenShiftOnly
    @KafkaCluster(name = "my-cluster", kafkaNodes = 2, zkNodes = 2, config = {
            @CmData(key = "zookeeper-healthcheck-delay", value = "30"),
            @CmData(key = "zookeeper-healthcheck-timeout", value = "10"),
            @CmData(key = "kafka-healthcheck-delay", value = "30"),
            @CmData(key = "kafka-healthcheck-timeout", value = "10"),
            @CmData(key = "kafka-config", value = "{\"default.replication.factor\": 1,\"offsets.topic.replication.factor\": 1,\"transaction.state.log.replication.factor\": 1}")
    })
    public void testForUpdateValuesInConfigMap() {
        String clusterName = "my-cluster";
        int expectedZKPods = 2;
        int expectedKafkaPods = 2;
        List<Date> zkPodStartTime = new ArrayList<>();
        for (int i = 0; i < expectedZKPods; i++) {
            zkPodStartTime.add(kubeClient.getResourceCreateTimestamp("pod", zookeeperPodName(clusterName, i)));
        }
        List<Date> kafkaPodStartTime = new ArrayList<>();
        for (int i = 0; i < expectedKafkaPods; i++) {
            kafkaPodStartTime.add(kubeClient.getResourceCreateTimestamp("pod", kafkaPodName(clusterName, i)));
        }
        Oc oc = (Oc) this.kubeClient;
        replaceCm(clusterName, "zookeeper-healthcheck-delay", "23");
        replaceCm(clusterName, "zookeeper-healthcheck-timeout", "24");
        replaceCm(clusterName, "kafka-healthcheck-delay", "23");
        replaceCm(clusterName, "kafka-healthcheck-timeout", "20");
        replaceCm(clusterName, "kafka-config", "{\"default.replication.factor\": 2,\"offsets.topic.replication.factor\": 2,\"transaction.state.log.replication.factor\": 2}");

        for (int i = 0; i < expectedZKPods; i++) {
            kubeClient.waitForResourceUpdate("pod", zookeeperPodName(clusterName, i), zkPodStartTime.get(i));
            kubeClient.waitForPod(zookeeperPodName(clusterName,  i));
        }
        for (int i = 0; i < expectedKafkaPods; i++) {
            kubeClient.waitForResourceUpdate("pod", kafkaPodName(clusterName, i), kafkaPodStartTime.get(i));
            kubeClient.waitForPod(kafkaPodName(clusterName,  i));
        }
        String configMap = kubeClient.get("cm", clusterName);
        assertThat(configMap, valueOfCmEquals("zookeeper-healthcheck-delay", "23"));
        assertThat(configMap, valueOfCmEquals("zookeeper-healthcheck-timeout", "24"));
        assertThat(configMap, valueOfCmEquals("kafka-healthcheck-delay", "23"));
        assertThat(configMap, valueOfCmEquals("kafka-healthcheck-timeout", "20"));
        assertThat(configMap, valueOfCmEquals("kafka-config", "{\"default.replication.factor\": 2,\"offsets.topic.replication.factor\": 2,\"transaction.state.log.replication.factor\": 2}"));

        LOGGER.info("Verified CM and Testing kafka pods");
        for (int i = 0; i < expectedKafkaPods; i++) {
            String kafkaPodJson = oc.getResourceAsJson("pod", kafkaPodName(clusterName, i));
            assertEquals("offsets.topic.replication.factor=2\ndefault.replication.factor=2\ntransaction.state.log.replication.factor=2", getValueFromJson(kafkaPodJson,
                    globalVariableJsonPathBuilder("KAFKA_USER_CONFIGURATION")));
            String initialDelaySecondsPath = "$.spec.containers[*].livenessProbe.initialDelaySeconds";
            assertEquals("23", getValueFromJson(kafkaPodJson, initialDelaySecondsPath));
            String kafkaHealthcheckTimeout = "$.spec.containers[*].livenessProbe.timeoutSeconds";
            assertEquals("20", getValueFromJson(kafkaPodJson, kafkaHealthcheckTimeout));
        }
        LOGGER.info("Testing Zookeepers");
        for (int i = 0; i < expectedZKPods; i++) {
            String zkPodJson = kubeClient.getResourceAsJson("pod", zookeeperPodName(clusterName, i));
            String initialDelaySecondsPath = "$.spec.containers[*].livenessProbe.initialDelaySeconds";
            assertEquals("23", getValueFromJson(zkPodJson, initialDelaySecondsPath));
            String zookeeperHealthcheckTimeout = "$.spec.containers[*].livenessProbe.timeoutSeconds";
            assertEquals("24", getValueFromJson(zkPodJson, zookeeperHealthcheckTimeout));
        }
    }

    @Test
    @KafkaCluster(name = CLUSTER_NAME, kafkaNodes = 3, config = {
            @CmData(key = "KAFKA_DEFAULT_REPLICATION_FACTOR", value = "1")})
    @Topic(name = TOPIC_NAME, clusterName = "my-cluster")
    public void testSendMessages() {
        int messagesCount = 20;
        sendMessages(CLUSTER_NAME, TOPIC_NAME, messagesCount, 1);
        String consumedMessages = consumeMessages(CLUSTER_NAME, TOPIC_NAME, 1, 20, 2);
        List<String> messages = new ArrayList<>(Arrays.asList(consumedMessages.split("\n")));
        String count = JsonPath.parse(messages.get(3)).read("$.count").toString();
        assertEquals(messagesCount, Integer.parseInt(count));
        String topic = JsonPath.parse(messages.get(3)).read(".partitions.[0].topic").toString().
                replaceAll("[\\[\\]\"]", "");
        assertEquals(TOPIC_NAME, topic);
    }

    @KafkaCluster(name = "jvm-resource-cluster",
        kafkaNodes = 1,
        zkNodes = 1,
        config = {
            @CmData(key = "kafka-resources",
                    value = "{ \"limits\": {\"memory\": \"2Gi\", \"cpu\": 2}, " +
                            "\"requests\": {\"memory\": \"2Gi\", \"cpu\": 2}}"),
            @CmData(key = "kafka-jvmOptions",
                    value = "{\"-Xmx\": \"512m\", \"-Xms\": \"256m\"}"),
            @CmData(key = "zookeeper-resources",
                    value = "{ \"limits\": {\"memory\": \"1G\", \"cpu\": 1}, " +
                            "\"requests\": {\"memory\": \"1G\", \"cpu\": 1} }"),
            @CmData(key = "zookeeper-jvmOptions",
                    value = "{\"-Xmx\": \"600m\", \"-Xms\": \"300m\"}"),
            @CmData(key = "topic-controller-config",
                    value = "{\"resources\": { \"limits\": {\"memory\": \"500M\", \"cpu\": 1}, " +
                            "\"requests\": {\"memory\": \"500M\", \"cpu\": 1} } }")
    })
    @Test
    public void testJvmAndResources() {
        assertResources("jvm-resource-cluster-kafka-0",
                "2Gi", "2", "2Gi", "2");
        assertExpectedJavaOpts("jvm-resource-cluster-kafka-0",
                "-Xmx512m", "-Xms256m");

        assertResources("jvm-resource-cluster-zookeeper-0",
                "1G", "1", "1G", "1");
        assertExpectedJavaOpts("jvm-resource-cluster-zookeeper-0",
                "-Xmx600m", "-Xms300m");

        String podName = client.pods().list().getItems().stream().filter(p -> p.getMetadata().getName().startsWith("jvm-resource-cluster-topic-controller-")).findFirst().get().getMetadata().getName();

        assertResources(podName,
                "500M", "1", "500M", "1");
    }
}