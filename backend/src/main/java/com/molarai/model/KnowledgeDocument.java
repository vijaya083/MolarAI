package com.molarai.model;

import java.util.Map;

public record KnowledgeDocument(
        String documentId,
        String documentName,
        String content,
        Map<String, Object> metadata) {
}
