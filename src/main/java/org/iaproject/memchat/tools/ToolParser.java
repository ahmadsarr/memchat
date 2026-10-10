package org.iaproject.memchat.tools;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class ToolParser {
    public record ToolDescription(String name, String description, Map<String, ParameterDescription> params) {
    }

    public record ParameterDescription(
            String type,
            String description,
            boolean required
    ) {
    }

    public static List<ToolDescription> parse(List<Object> tools) {
        return tools.stream()
                .flatMap(ToolParser::extract).toList();
    }

    private static Stream<ToolDescription> extract(Object tool) {
        Objects.requireNonNull(tool);

        return Arrays.stream(tool.getClass().getMethods())
                .filter(m -> m.isAnnotationPresent(Tool.class))
                .map(m -> {
                    Map<String, ParameterDescription> params =
                            Arrays.stream(m.getParameters())
                                    .collect(Collectors.toMap(
                                            Parameter::getName,
                                            p -> {
                                                Param annotation =
                                                        p.getAnnotation(Param.class);

                                                return new ParameterDescription(
                                                        jsonType(p.getType()),
                                                        annotation == null
                                                                ? ""
                                                                : annotation.description(),
                                                        true
                                                );
                                            },
                                            (a, b) -> a,
                                            LinkedHashMap::new
                                    ));

                    return new ToolDescription(
                            m.getName(),
                            m.getAnnotation(Tool.class).description(),
                            params
                    );
                });
    }

    private static String jsonType(Class<?> type) {
        if (type == String.class || type.isEnum()) {
            return "string";
        }
        if (type == int.class || type == Integer.class
                || type == long.class || type == Long.class) {
            return "integer";
        }
        if (type == double.class || type == Double.class
                || type == float.class || type == Float.class) {
            return "number";
        }
        if (type == boolean.class || type == Boolean.class) {
            return "boolean";
        }
        if (type.isArray() || Collection.class.isAssignableFrom(type)) {
            return "array";
        }
        return "object";
    }
}
