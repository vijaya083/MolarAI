package com.molarai.ai;

/** Signals that Ollama stopped generation at its configured output limit. */
public class LlmOutputLimitException extends LlmProviderException {
    public LlmOutputLimitException(String message) {
        super(message);
    }
}
