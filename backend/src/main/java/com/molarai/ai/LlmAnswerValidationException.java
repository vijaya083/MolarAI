package com.molarai.ai;

/** A model response violated the customer-answer contract. */
public class LlmAnswerValidationException extends LlmProviderException {
    private final String category;

    public LlmAnswerValidationException(String category, String message) {
        super(message);
        this.category = category;
    }

    public String category() {
        return category;
    }
}
