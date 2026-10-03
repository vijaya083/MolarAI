package com.molarai.model;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record StoredKnowledgeChunk(
        UUID id,
        String documentId,
        String documentName,
        int chunkIndex,
        String content,
        Map<String, Object> metadata,
        List<Double> embedding,
        OffsetDateTime createdAt) {
}
