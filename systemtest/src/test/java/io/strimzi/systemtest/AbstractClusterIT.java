/*
 * Copyright 2018, Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.systemtest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.jayway.jsonpath.JsonPath;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.Event;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.client.DefaultKubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.strimzi.test.TestUtils;
import io.strimzi.test.k8s.KubeClient;
import io.strimzi.test.k8s.KubeClusterException;
import io.strimzi.test.k8s.KubeClusterResource;
import io.strimzi.test.k8s.ProcessResult;
import org.junit.ClassRule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static io.strimzi.test.TestUtils.indent;
import static java.util.Arrays.asList;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

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
        try {
            String jsonString = kubeClient.get("cm", cmName);
            YAMLMapper mapper = new YAMLMapper();
            JsonNode node = mapper.readTree(jsonString);
            ((ObjectNode) node.get("data")).put(fieldName, fieldValue);
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

    protected void assertResources(String podName, String memoryLimit, String cpuLimit, String memoryRequest, String cpuRequest) {
        Pod po = client.pods().withName(podName).get();
        assertNotNull("Expected a pod called " + podName, po);
        Container container = po.getSpec().getContainers().get(0);
        Map<String, Quantity> limits = container.getResources().getLimits();
        assertEquals(memoryLimit, limits.get("memory").getAmount());
        assertEquals(cpuLimit, limits.get("cpu").getAmount());
        Map<String, Quantity> requests = container.getResources().getRequests();
        assertEquals(memoryRequest, requests.get("memory").getAmount());
        assertEquals(cpuRequest, requests.get("cpu").getAmount());
    }

    protected void assertExpectedJavaOpts(String podName, String expectedXmx, String expectedXms) {
        List<List<String>> cmdLines = commandLines(podName, "java");
        assertEquals("Expected exactly 1 java process to be running",
                1, cmdLines.size());
        List<String> cmd = cmdLines.get(0);
        int toIndex = cmd.indexOf("-jar");
        if (toIndex != -1) {
            // Just consider arguments to the JVM, not the application running in it
            cmd = cmd.subList(0, toIndex);
            // We should do something similar if the class not -jar was given, but that's
            // hard to do properly.
        }
        assertCmdOption(cmd, expectedXmx);
        assertCmdOption(cmd, expectedXms);
    }

    private void assertCmdOption(List<String> cmd, String expectedXmx) {
        if (!cmd.contains(expectedXmx)) {
            fail("Failed to find argument matching " + expectedXmx + " in java command line " +
                    cmd.stream().collect(Collectors.joining("\n")));
        }
    }

    private List<List<String>> commandLines(String podName, String cmd) {
        List<List<String>> result = new ArrayList<>();
        ProcessResult pr = kubeClient.exec(podName, "/bin/bash", "-c",
                "for pid in $(ps -C java -o pid h); do cat /proc/$pid/cmdline; done"
        );
        for (String cmdLine : pr.out().split("\n")) {
            result.add(asList(cmdLine.split("\0")));
        }
        return result;
    }
}
