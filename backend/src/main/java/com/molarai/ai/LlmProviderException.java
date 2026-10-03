package com.molarai.ai;

public class LlmProviderException extends RuntimeException {
    public LlmProviderException(String message) {
        super(message);
    }

    public LlmProviderException(String message, Throwable cause) {
        super(message, cause);
    }
}
