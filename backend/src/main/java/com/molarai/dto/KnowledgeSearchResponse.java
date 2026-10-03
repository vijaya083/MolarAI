package com.molarai.dto;

import java.util.List;

public record KnowledgeSearchResponse(String query, List<KnowledgeSearchResult> results) {
}
