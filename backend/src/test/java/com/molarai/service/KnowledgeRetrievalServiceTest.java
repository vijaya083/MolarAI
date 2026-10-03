package com.molarai.service;

import com.molarai.ai.EmbeddingService;
import com.molarai.model.KnowledgeSearchMatch;
import com.molarai.repository.KnowledgeChunkRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeRetrievalServiceTest {
    @Test
    void embedsQueryAndPassesEmbeddingAndTopKToRepository() {
        EmbeddingService embeddingService = mock(EmbeddingService.class);
        KnowledgeChunkRepository repository = mock(KnowledgeChunkRepository.class);
        List<Double> vector = List.of(0.1, 0.2, 0.3);
        List<KnowledgeSearchMatch> matches = List.of(new KnowledgeSearchMatch(
                "insurance", "03-insurance.md", 0, "Out-of-network clinic.", Map.of("sourceFilename", "03-insurance.md"), 0.08));
        when(embeddingService.embedAll(List.of("Do you accept Aetna insurance?"))).thenReturn(List.of(vector));
        when(repository.searchSimilar(vector, 3)).thenReturn(matches);
        KnowledgeRetrievalService service = new KnowledgeRetrievalService(embeddingService, repository);

        assertEquals(matches, service.search("  Do you accept Aetna insurance?  ", 3));
        verify(embeddingService).embedAll(List.of("Do you accept Aetna insurance?"));
        verify(repository).searchSimilar(vector, 3);
    }

    @Test
    void rejectsBlankQuery() {
        KnowledgeRetrievalService service = new KnowledgeRetrievalService(
                mock(EmbeddingService.class), mock(KnowledgeChunkRepository.class));

        assertThrows(IllegalArgumentException.class, () -> service.search(" \n ", 3));
    }

    @Test
    void rejectsUnreasonableTopK() {
        KnowledgeRetrievalService service = new KnowledgeRetrievalService(
                mock(EmbeddingService.class), mock(KnowledgeChunkRepository.class));

        assertThrows(IllegalArgumentException.class, () -> service.search("clinic hours", 11));
    }
}
