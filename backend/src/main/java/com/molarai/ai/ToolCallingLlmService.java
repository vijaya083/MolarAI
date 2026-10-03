package com.molarai.ai;

import java.util.List;

public interface ToolCallingLlmService extends LlmService {
    LlmChatResult chatWithTools(
            String systemPrompt,
            List<LlmChatMessage> messages,
            List<LlmToolDefinition> tools);
}
