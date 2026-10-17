package org.iaproject.memchat.tools;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class ToolParser {
    public record ToolDescription(
            String name,
            String description,
            Map<String, ParameterDescription> params,
            boolean terminal
    ) {
    }

    public record ParameterDescription(
            String type,
            String description,
            boolean required
    ) {
    }

    public record ToolRegister(ToolDescription toolDescription, Method method, Object instance) {
    }

    public static List<ToolRegister> parse(List<Object> tools) {
        return tools.stream()
                .flatMap(ToolParser::extract).toList();
    }

    private static Stream<ToolRegister> extract(Object tool) {
        Objects.requireNonNull(tool);

        List<ToolRegister> toolRegisters = new ArrayList<>();

        for (Method m : tool.getClass().getMethods()) {
            if (!m.isAnnotationPresent(Tool.class)) {
                continue;
            }

            Map<String, ParameterDescription> params = new LinkedHashMap<>();
            for (Parameter p : m.getParameters()) {
                Param annotation = p.getAnnotation(Param.class);

                params.put(
                        p.getName(),
                        new ParameterDescription(
                                jsonType(p.getType()),
                                annotation == null ? "" : annotation.description(),
                                true
                        )
                );
            }

            toolRegisters.add(new ToolRegister(
                    new ToolDescription(
                        m.getName(),
                        m.getAnnotation(Tool.class).description(),
                            params,
                            m.getAnnotation(Tool.class).terminal()
                    ),
                    m, tool
            ));
        }

        return toolRegisters.stream();
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
