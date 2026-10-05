package com.molarai.service;

public class KnowledgeUnavailableException extends RuntimeException {
    public KnowledgeUnavailableException() {
        super("Knowledge-based answers are temporarily unavailable.");
    }
}
