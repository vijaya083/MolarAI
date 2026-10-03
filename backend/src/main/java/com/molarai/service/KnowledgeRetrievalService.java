package com.molarai.service;

import com.molarai.ai.EmbeddingProviderException;
import com.molarai.ai.EmbeddingService;
import com.molarai.model.KnowledgeSearchMatch;
import com.molarai.repository.KnowledgeChunkRepository;
import com.molarai.performance.PerformanceTiming;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class KnowledgeRetrievalService {
    public static final int MAX_TOP_K = 10;

    private final EmbeddingService embeddingService;
    private final KnowledgeChunkRepository repository;
    private final PerformanceTiming timing;

    @Autowired
    public KnowledgeRetrievalService(
            EmbeddingService embeddingService,
            KnowledgeChunkRepository repository,
            PerformanceTiming timing) {
        this.embeddingService = embeddingService;
        this.repository = repository;
        this.timing = timing;
    }

    KnowledgeRetrievalService(EmbeddingService embeddingService, KnowledgeChunkRepository repository) {
        this(embeddingService, repository, new PerformanceTiming());
    }

    public List<KnowledgeSearchMatch> search(String query, int topK) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("query must not be blank");
        }
        if (topK < 1 || topK > MAX_TOP_K) {
            throw new IllegalArgumentException("topK must be between 1 and " + MAX_TOP_K);
        }

        timing.increment("embedding_calls");
        List<List<Double>> queryEmbeddings;
        try (var ignored = timing.stage("query_embedding")) {
            queryEmbeddings = embeddingService.embedAll(List.of(query.trim()));
        }
        if (queryEmbeddings == null || queryEmbeddings.size() != 1
                || queryEmbeddings.getFirst() == null || queryEmbeddings.getFirst().isEmpty()) {
            throw new EmbeddingProviderException("Embedding provider returned no query embedding");
        }
        try (var ignored = timing.stage("pgvector_retrieval")) {
            return repository.searchSimilar(queryEmbeddings.getFirst(), topK);
        }
    }
}
