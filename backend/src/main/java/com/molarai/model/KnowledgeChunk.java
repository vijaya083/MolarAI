package com.molarai.model;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record KnowledgeChunk(
        UUID id,
        String documentId,
        String documentName,
        int chunkIndex,
        String content,
        Map<String, Object> metadata,
        List<Double> embedding) {
}
