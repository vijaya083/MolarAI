package com.molarai.rag;

import com.molarai.ai.EmbeddingService;
import com.molarai.model.KnowledgeChunk;
import com.molarai.model.KnowledgeDocument;
import com.molarai.repository.KnowledgeChunkRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeBaseIngestionServiceTest {
    @Test
    void loadsChunksEmbedsAndPersistsWithStableIdsAndMetadata() {
        KnowledgeDocumentLoader loader = mock(KnowledgeDocumentLoader.class);
        EmbeddingService embedder = mock(EmbeddingService.class);
        KnowledgeChunkRepository repository = mock(KnowledgeChunkRepository.class);
        when(loader.loadDocuments()).thenReturn(List.of(new KnowledgeDocument(
                "hours", "Clinic Hours", "Clinic is open on weekdays.", Map.of("sourceFilename", "hours.md"))));
        when(embedder.embedAll(anyList())).thenAnswer(call -> {
            List<String> content = call.getArgument(0);
            return content.stream().map(ignored -> List.of(0.1, 0.2)).toList();
        });
        KnowledgeBaseIngestionService service = new KnowledgeBaseIngestionService(
                loader, new DocumentChunker(10, 2), embedder, repository, 2);

        KnowledgeBaseIngestionService.IngestionResult result = service.ingest();

        assertEquals(1, result.documentCount());
        assertEquals(4, result.chunkCount());
        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<List<KnowledgeChunk>> chunks = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(repository).replaceDocumentChunks(org.mockito.ArgumentMatchers.eq("hours"), chunks.capture());
        assertEquals(List.of(0, 1, 2, 3), chunks.getValue().stream().map(KnowledgeChunk::chunkIndex).toList());
        assertEquals("hours.md", chunks.getValue().getFirst().metadata().get("sourceFilename"));

        service.ingest();
        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<List<KnowledgeChunk>> secondRun = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(repository, org.mockito.Mockito.times(2))
                .replaceDocumentChunks(org.mockito.ArgumentMatchers.eq("hours"), secondRun.capture());
        assertEquals(chunks.getValue().stream().map(KnowledgeChunk::id).toList(),
                secondRun.getAllValues().get(1).stream().map(KnowledgeChunk::id).toList());
    }

    @Test
    void doesNotReplaceStoredDocumentWhenEmbeddingFails() {
        KnowledgeDocumentLoader loader = mock(KnowledgeDocumentLoader.class);
        EmbeddingService embedder = mock(EmbeddingService.class);
        KnowledgeChunkRepository repository = mock(KnowledgeChunkRepository.class);
        when(loader.loadDocuments()).thenReturn(List.of(
                new KnowledgeDocument("fees", "Fees", "Fees are explained.", Map.of())));
        when(embedder.embedAll(anyList())).thenThrow(new IllegalStateException("provider unavailable"));
        KnowledgeBaseIngestionService service = new KnowledgeBaseIngestionService(
                loader, new DocumentChunker(20, 2), embedder, repository, 2);

        assertThrows(IllegalStateException.class, service::ingest);
        verify(repository, never()).replaceDocumentChunks(org.mockito.ArgumentMatchers.anyString(), anyList());
    }
}
