package com.molarai.rag;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "molarai.ingestion.enabled", havingValue = "true")
public class KnowledgeBaseIngestionRunner implements ApplicationRunner {
    private final KnowledgeBaseIngestionService ingestionService;

    public KnowledgeBaseIngestionRunner(KnowledgeBaseIngestionService ingestionService) {
        this.ingestionService = ingestionService;
    }

    @Override
    public void run(ApplicationArguments args) {
        ingestionService.ingest();
    }
}
