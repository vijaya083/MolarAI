package com.molarai.model;

import java.util.Map;

public record GroundedAnswerSource(
        String documentId,
        String documentName,
        int chunkIndex,
        Map<String, Object> metadata,
        double cosineDistance) {
    public static GroundedAnswerSource from(KnowledgeSearchMatch match) {
        return new GroundedAnswerSource(
                match.documentId(), match.documentName(), match.chunkIndex(), match.metadata(), match.cosineDistance());
    }
}
