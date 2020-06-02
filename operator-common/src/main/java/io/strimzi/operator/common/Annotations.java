/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.common;

import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.PodTemplateSpec;

import java.util.HashMap;
import java.util.Map;

import static java.lang.Boolean.parseBoolean;
import static java.lang.Integer.parseInt;

public class Annotations {

    public static final String STRIMZI_DOMAIN = "strimzi.io/";
    public static final String STRIMZI_LOGGING_ANNOTATION = STRIMZI_DOMAIN + "logging";
    public static final String STRIMZI_DYNAMIC_LOGGING_ANNOTATION = STRIMZI_DOMAIN + "dynamic-logging";
    public static final String STRIMZI_IO_USE_CONNECTOR_RESOURCES = STRIMZI_DOMAIN + "use-connector-resources";
    public static final String ANNO_STRIMZI_IO_MANUAL_ROLLING_UPDATE = STRIMZI_DOMAIN + "manual-rolling-update";
    @Deprecated
    public static final String ANNO_OP_STRIMZI_IO_MANUAL_ROLLING_UPDATE = "operator." + Annotations.STRIMZI_DOMAIN + "manual-rolling-update";

    public static final String ANNO_DEP_KUBE_IO_REVISION = "deployment.kubernetes.io/revision";

    private static Map<String, String> annotations(ObjectMeta metadata) {
        Map<String, String> annotations = metadata.getAnnotations();
        if (annotations == null) {
            annotations = new HashMap<>(3);
            metadata.setAnnotations(annotations);
        }
        return annotations;
    }

    public static Map<String, String> annotations(HasMetadata resource) {
        return annotations(resource.getMetadata());
    }

    public static Map<String, String> annotations(PodTemplateSpec podSpec) {
        return annotations(podSpec.getMetadata());
    }

    public static boolean booleanAnnotation(HasMetadata resource, String annotation, boolean defaultValue, String... deprecatedAnnotations) {
        ObjectMeta metadata = resource.getMetadata();
        String str = annotation(annotation, null, metadata, deprecatedAnnotations);
        return str != null ? parseBoolean(str) : defaultValue;
    }

    public static int intAnnotation(HasMetadata resource, String annotation, int defaultValue, String... deprecatedAnnotations) {
        ObjectMeta metadata = resource.getMetadata();
        String str = annotation(annotation, null, metadata, deprecatedAnnotations);
        return str != null ? parseInt(str) : defaultValue;
    }

    public static String stringAnnotation(HasMetadata resource, String annotation, String defaultValue, String... deprecatedAnnotations) {
        ObjectMeta metadata = resource.getMetadata();
        String str = annotation(annotation, null, metadata, deprecatedAnnotations);
        return str != null ? str : defaultValue;
    }

    public static int intAnnotation(PodTemplateSpec podSpec, String annotation, int defaultValue, String... deprecatedAnnotations) {
        ObjectMeta metadata = podSpec.getMetadata();
        String str = annotation(annotation, null, metadata, deprecatedAnnotations);
        return str != null ? parseInt(str) : defaultValue;
    }

    public static String stringAnnotation(PodTemplateSpec podSpec, String annotation, String defaultValue, String... deprecatedAnnotations) {
        ObjectMeta metadata = podSpec.getMetadata();
        String str = annotation(annotation, null, metadata, deprecatedAnnotations);
        return str != null ? str : defaultValue;
    }

    public static boolean hasAnnotation(HasMetadata resource, String annotation) {
        ObjectMeta metadata = resource.getMetadata();
        String str = annotation(annotation, null, metadata, null);
        return str != null;
    }

    public static int incrementIntAnnotation(PodTemplateSpec podSpec, String annotation, int defaultValue, String... deprecatedAnnotations) {
        ObjectMeta metadata = podSpec.getMetadata();
        return incrementIntAnnotation(annotation, defaultValue, metadata, deprecatedAnnotations);
    }

    public static int incrementIntAnnotation(HasMetadata podSpec, String annotation, int defaultValue, String... deprecatedAnnotations) {
        ObjectMeta metadata = podSpec.getMetadata();
        return incrementIntAnnotation(annotation, defaultValue, metadata, deprecatedAnnotations);
    }

    private static int incrementIntAnnotation(String annotation, int defaultValue, ObjectMeta metadata, String... deprecatedAnnotations) {
        Map<String, String> annos = annotations(metadata);
        String str = annotation(annotation, null, annos, deprecatedAnnotations);
        int v = str != null ? parseInt(str) : defaultValue;
        v++;
        annos.put(annotation, Integer.toString(v));
        return v;
    }

    private static String annotation(String annotation, String defaultValue, ObjectMeta metadata, String... deprecatedAnnotations) {
        Map<String, String> annotations = annotations(metadata);
        return annotation(annotation, defaultValue, annotations, deprecatedAnnotations);
    }

    private static String annotation(String annotation, String defaultValue, Map<String, String> annotations, String... deprecatedAnnotations) {
        String value = annotations.get(annotation);
        if (value == null) {
            if (deprecatedAnnotations != null) {
                for (String deprecated : deprecatedAnnotations) {
                    value = annotations.get(deprecated);
                    if (value != null) {
                        break;
                    }
                }
            }

            if (value == null) {
                value = defaultValue;
            }
        }
        return value;
    }

}
