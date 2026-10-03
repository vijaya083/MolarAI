package com.molarai.ai;

import java.util.List;

public record LlmChatMessage(String role, String content, String toolName, List<LlmToolCall> toolCalls) {
    public LlmChatMessage {
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }

    public static LlmChatMessage user(String content) {
        return new LlmChatMessage("user", content, null, List.of());
    }

    public static LlmChatMessage assistant(String content, List<LlmToolCall> toolCalls) {
        return new LlmChatMessage("assistant", content, null, toolCalls);
    }

    public static LlmChatMessage tool(String toolName, String content) {
        return new LlmChatMessage("tool", content, toolName, List.of());
    }
}
