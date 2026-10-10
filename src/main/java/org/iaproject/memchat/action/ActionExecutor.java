package org.iaproject.memchat.action;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.iaproject.memchat.tools.ToolParser.*;
import org.iaproject.memchat.tools.ToolResult;
import org.iaproject.memchat.tools.UserInteractionTools;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

public class ActionExecutor {
    private final Map<String, ToolRegister> cachedRegister;
    private static final ObjectMapper mapper = new ObjectMapper();

    public ActionExecutor(List<ToolRegister> registers) {
        this.cachedRegister = registers.stream()
                .collect(Collectors.toMap(t -> t.toolDescription().name().toUpperCase(), Function.identity()));
    }


    public ToolResult execute(ActionParser.Action action) {


        if (action == null || action.name() == null) {
            return ToolResult.failure(
                    "unknown",
                    "No valid tool call found"
            );
        }

        String toolName = action.name().toLowerCase(Locale.ROOT);
        ToolRegister register = cachedRegister.get(toolName);

        if (register == null) {
            return ToolResult.failure(
                    toolName,
                    "Unknown tool: " + toolName
            );
        }

        try {
            Object result = call(register, action.args());
            if (result instanceof UserInteractionTools.UserInteractionResult(
                    UserInteractionTools.InteractionType type, String message
            ) && type == UserInteractionTools.InteractionType.FINISH) {
                return ToolResult.stop(toolName);
            }
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
