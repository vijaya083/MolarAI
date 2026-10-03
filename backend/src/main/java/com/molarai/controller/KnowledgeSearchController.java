package com.molarai.controller;

import com.molarai.dto.KnowledgeSearchRequest;
import com.molarai.dto.KnowledgeSearchResponse;
import com.molarai.dto.KnowledgeSearchResult;
import com.molarai.service.KnowledgeRetrievalService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeSearchController {
    private final KnowledgeRetrievalService retrievalService;

    public KnowledgeSearchController(KnowledgeRetrievalService retrievalService) {
        this.retrievalService = retrievalService;
    }

    @PostMapping("/search")
    public KnowledgeSearchResponse search(@Valid @RequestBody KnowledgeSearchRequest request) {
        return new KnowledgeSearchResponse(
                request.query(),
                retrievalService.search(request.query(), request.topK()).stream()
                        .map(KnowledgeSearchResult::from)
                        .toList());
    }
}
