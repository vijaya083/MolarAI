package com.molarai.model;

import java.util.Map;

public record KnowledgeSearchMatch(
        String documentId,
        String documentName,
        int chunkIndex,
        String content,
        Map<String, Object> metadata,
        double cosineDistance) {
}
