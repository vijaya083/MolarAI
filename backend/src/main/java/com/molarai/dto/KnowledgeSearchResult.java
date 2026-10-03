package com.molarai.dto;

import com.molarai.model.KnowledgeSearchMatch;

import java.util.Map;

public record KnowledgeSearchResult(
        String documentName,
        int chunkIndex,
        String content,
        Map<String, Object> metadata,
        double cosineDistance) {
    public static KnowledgeSearchResult from(KnowledgeSearchMatch match) {
        return new KnowledgeSearchResult(
                match.documentName(),
                match.chunkIndex(),
                match.content(),
                match.metadata(),
                match.cosineDistance());
    }
}
