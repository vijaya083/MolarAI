package com.molarai.ai;

public interface LlmService {
    String generate(String systemPrompt, String userPrompt);
}
