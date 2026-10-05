package com.molarai.rag;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "molarai.ingestion.enabled", havingValue = "true")
public class KnowledgeBaseIngestionRunner implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseIngestionRunner.class);

    private final KnowledgeBaseIngestionService ingestionService;
    private final boolean embeddingEnabled;

    public KnowledgeBaseIngestionRunner(
            KnowledgeBaseIngestionService ingestionService,
            @Value("${molarai.embedding.provider:ollama}") String embeddingProvider) {
        this.ingestionService = ingestionService;
        this.embeddingEnabled = !"disabled".equalsIgnoreCase(embeddingProvider);
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!embeddingEnabled) {
            log.warn("Knowledge-base ingestion skipped because embedding generation is disabled");
            return;
        }
        ingestionService.ingest();
    }
}
