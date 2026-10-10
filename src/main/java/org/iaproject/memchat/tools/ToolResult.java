package org.iaproject.memchat.tools;

public record ToolResult(
        String action,
        boolean success,
        String result,
        String error,
        Boolean isFinish
) {

    public static ToolResult success(String action, Object result) {
        return new ToolResult(
                action,
                true,
                String.valueOf(result),
                null,
                false
        );
    }

    public static ToolResult failure(String action, String error) {
        return new ToolResult(
                action,
                false,
                null,
                error,
                false
        );
    }


    public static ToolResult stop(String action) {
        return new ToolResult(
                action,
                true,
                null,
                null,
                true
        );
    }
}
