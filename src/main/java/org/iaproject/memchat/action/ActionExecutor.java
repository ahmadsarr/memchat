package org.iaproject.memchat.action;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.iaproject.memchat.tools.ToolParser.*;
import org.iaproject.memchat.tools.ToolResult;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

public class ActionExecutor {
    private final Map<String, ToolRegister> cachedRegister;
    private static final ObjectMapper mapper = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT);

    public ActionExecutor(List<ToolRegister> registers) {
        this.cachedRegister = registers.stream()
                .collect(Collectors.toMap(t -> t.toolDescription().name().toUpperCase(Locale.ROOT), Function.identity()));
    }

    public ToolResult execute(String name, String arguments) {
        if (name == null || name.isBlank()) {
            return ToolResult.failure("UNKNOWN", "Missing tool name");
        }

        try {
            JsonNode args = mapper.readTree(
                    arguments == null || arguments.isBlank()
                            ? "{}"
                            : arguments
            );

            if (!args.isObject()) {
                return ToolResult.failure(name, "Arguments must be a JSON object");
            }

            return execute(name, args);

        } catch (JsonProcessingException e) {
            return ToolResult.failure(name, "Invalid JSON arguments: " + e.getOriginalMessage());
        }
    }

    public ToolResult execute(String name, JsonNode arguments) {
        return execute(new ActionParser.Action("", name, arguments));
    }

    public ToolResult execute(ActionParser.Action action) {


        if (action == null || action.name() == null || action.name().isBlank()) {
            return ToolResult.failure("UNKNOWN", "Missing tool name");
        }
        if (action.args() == null || !action.args().isObject()) {
            return ToolResult.failure(action.name(), "Arguments must be a JSON object");
        }

        String toolName = action.name().toUpperCase(Locale.ROOT);
        ToolRegister register = cachedRegister.get(toolName);

        if (register == null) {
            return ToolResult.failure(
                    toolName,
                    "Unknown tool: " + toolName
            );
        }

        try {
            Object result = call(register, action.args());
            return ToolResult.success(
                    toolName,
                    result
            );

        } catch (Exception e) {
            Throwable cause = e instanceof InvocationTargetException ite
                    ? ite.getCause()
                    : e;

            return ToolResult.failure(
                    toolName,
                    cause == null ? e.toString() : cause.toString()
            );
        }
    }

    public List<ToolDescription> getToolsDescriptions() {
        return cachedRegister.values()
                .stream().map(ToolRegister::toolDescription)
                .toList();
    }

    public boolean isTerminal(String name) {
        if (name == null) {
            return false;
        }
        ToolRegister register = cachedRegister.get(name.toUpperCase(Locale.ROOT));
        return register != null && register.toolDescription().terminal();
    }

    public Object call(ToolRegister toolRegister,
                       JsonNode arguments
    ) throws InvocationTargetException, IllegalAccessException {
        Method method = toolRegister.method();
        Object tool = toolRegister.instance();
        Objects.requireNonNull(method);
        Objects.requireNonNull(tool);
        Objects.requireNonNull(arguments);
        Object[] params = Arrays.stream(method.getParameters())
                .map(p -> {
                    String name = p.getName();
                    JsonNode value = arguments.get(name);

                    if (value == null || value.isNull()) {
                        throw new IllegalArgumentException(
                                "Missing required parameter: " + name
                        );
                    }

                    return mapper.convertValue(
                            value,
                            mapper.constructType(p.getParameterizedType())
                    );
                })
                .toArray();

        return method.invoke(tool, params);
    }
}
