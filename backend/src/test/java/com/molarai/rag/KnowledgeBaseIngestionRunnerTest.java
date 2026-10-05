package com.molarai.rag;

import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class KnowledgeBaseIngestionRunnerTest {
    @Test
    void skipsConfiguredIngestionWhenEmbeddingsAreDisabled() {
        KnowledgeBaseIngestionService ingestionService = mock(KnowledgeBaseIngestionService.class);
        KnowledgeBaseIngestionRunner runner = new KnowledgeBaseIngestionRunner(ingestionService, "disabled");

        runner.run(new DefaultApplicationArguments(new String[0]));

        verifyNoInteractions(ingestionService);
    }

    @Test
    void runsConfiguredIngestionForEnabledProvider() {
        KnowledgeBaseIngestionService ingestionService = mock(KnowledgeBaseIngestionService.class);
        KnowledgeBaseIngestionRunner runner = new KnowledgeBaseIngestionRunner(ingestionService, "ollama");

        runner.run(new DefaultApplicationArguments(new String[0]));

        org.mockito.Mockito.verify(ingestionService).ingest();
    }
}
