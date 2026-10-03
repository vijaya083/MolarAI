package com.molarai.ai;

import java.util.List;

public record LlmChatResult(String content, List<LlmToolCall> toolCalls) {
    public LlmChatResult {
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }
}
