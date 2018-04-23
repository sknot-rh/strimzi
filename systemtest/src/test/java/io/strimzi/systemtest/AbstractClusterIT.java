/*
 * Copyright 2018, Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.systemtest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.jayway.jsonpath.JsonPath;
import io.fabric8.kubernetes.api.model.Event;
import io.fabric8.kubernetes.client.DefaultKubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.strimzi.test.TestUtils;
import io.strimzi.test.k8s.KubeClient;
import io.strimzi.test.k8s.KubeClusterException;
import io.strimzi.test.k8s.KubeClusterResource;
import org.junit.ClassRule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static io.strimzi.test.TestUtils.indent;

public class AbstractClusterIT {

    private static final Logger LOGGER = LoggerFactory.getLogger(AbstractClusterIT.class);

    @ClassRule
    public static KubeClusterResource cluster = new KubeClusterResource();

    static KubernetesClient client = new DefaultKubernetesClient();
    KubeClient<?> kubeClient = cluster.client();

    // can be used as kafka stateful set or service names
    static String kafkaClusterName(String clusterName) {
        return clusterName + "-kafka";
    }

    static String kafkaPodName(String clusterName, int podId) {
        return kafkaClusterName(clusterName) + "-" + podId;
    }

    static String kafkaHeadlessServiceName(String clusterName) {
        return kafkaClusterName(clusterName) + "-headless";
    }

    static String kafkaMetricsConfigName(String clusterName) {
        return kafkaClusterName(clusterName) + "-metrics-config";
    }

    static String kafkaPVCName(String clusterName, int podId) {
        return "data-" + kafkaClusterName(clusterName) + "-" + podId;
    }

    // can be used as zookeeper stateful set or service names
    static String zookeeperClusterName(String clusterName) {
        return clusterName + "-zookeeper";
    }

    static String zookeeperPodName(String clusterName, int podId) {
        return zookeeperClusterName(clusterName) + "-" + podId;
    }

    static String zookeeperHeadlessServiceName(String clusterName) {
        return zookeeperClusterName(clusterName) + "-headless";
    }

    static String zookeeperMetricsConfigName(String clusterName) {
        return zookeeperClusterName(clusterName) + "-metrics-config";
    }

    static String zookeeperPVCName(String clusterName, int podId) {
        return "data-" + zookeeperClusterName(clusterName) + "-" + podId;
    }

    static String topicControllerDeploymentName(String clusterName) {
        return clusterName + "-topic-controller";
    }

    void replaceCm(String cmName, String fieldName, String fieldValue) {
        Map<String, String> changes = new HashMap<>();
        changes.put(fieldName, fieldValue);
        replaceCm(cmName, changes);
    }

    void replaceCm(String cmName, Map<String, String> changes) {
        try {
            String jsonString = kubeClient.get("cm", cmName);
            YAMLMapper mapper = new YAMLMapper();
            JsonNode node = mapper.readTree(jsonString);

            for (Map.Entry<String, String> change : changes.entrySet()) {
                ((ObjectNode) node.get("data")).put(change.getKey(), change.getValue());
            }

            String content = mapper.writeValueAsString(node);
            kubeClient.replaceContent(content);
            LOGGER.info("Value in Config Map replaced");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    String getBrokerApiVersions(String podName) {
        AtomicReference<String> versions = new AtomicReference<>();
        TestUtils.waitFor("kafka-broker-api-versions.sh success", 1_000L, 30_000L, () -> {
            try {
                String output = kubeClient.exec(podName,
                        "/opt/kafka/bin/kafka-broker-api-versions.sh", "--bootstrap-server", "localhost:9092").out();
                versions.set(output);
                return true;
            } catch (KubeClusterException e) {
                LOGGER.trace("/opt/kafka/bin/kafka-broker-api-versions.sh: {}", e.getMessage());
                return false;
            }
        });
        return versions.get();
    }

    void waitForZkMntr(String pod, Pattern pattern) {
        long timeoutMs = 120_000L;
        long pollMs = 1_000L;
        TestUtils.waitFor("mntr", pollMs, timeoutMs, () -> {
            try {
                String output = kubeClient.exec(pod,
                    "/bin/bash", "-c", "echo mntr | nc localhost 2181").out();

                if (pattern.matcher(output).find()) {
                    return true;
                }
            } catch (KubeClusterException e) {
                LOGGER.trace("Exception while waiting for ZK to become leader/follower, ignoring", e);
            }
                return false;
            },
            () -> LOGGER.info("zookeeper `mntr` output at the point of timeout does not match {}:{}{}",
                pattern.pattern(),
                System.lineSeparator(),
                indent(kubeClient.exec(pod, "/bin/bash", "-c", "echo mntr | nc localhost 2181").out()))
        );
    }

    String getValueFromJson(String json, String jsonPath) {
        String value = JsonPath.parse(json).read(jsonPath).toString().replaceAll("\\p{P}", "");
        return value;
    }

    String globalVariableJsonPathBuilder(String variable) {
        String path = "$.spec.containers[*].env[?(@.name=='" + variable + "')].value";
        return path;
    }

    List<Event> getEvents(String resourceType, String resourceName) {
        return client.events().inNamespace(kubeClient.namespace()).list().getItems().stream()
                .filter(event -> event.getInvolvedObject().getKind().equals(resourceType))
                .filter(event -> event.getInvolvedObject().getName().equals(resourceName))
                .collect(Collectors.toList());
    }

    public void sendMessages(String clusterName, String topic, int messagesCount, int kafkaPodID) {
        LOGGER.info("Sending messages");
        String command = "sh bin/kafka-verifiable-producer.sh --broker-list " +
                clusterName + "-kafka:9092 --topic " + topic + " --max-messages " + messagesCount + "";
        kubeClient.exec(kafkaPodName(clusterName, kafkaPodID), "/bin/bash", "-c", command);
    }

    public String consumeMessages(String clusterName, String topic, int groupID, int timeout, int kafkaPodID) {

        LOGGER.info("Consuming messages");
        return  kubeClient.exec(kafkaPodName(clusterName, kafkaPodID), "/bin/bash", "-c",
                    "bin/kafka-verifiable-consumer.sh --broker-list " + clusterName +
                            "-kafka:9092 --topic " + topic + " --group-id " + groupID + " & sleep "
                            + timeout + "; kill %1").out();
    }
}
