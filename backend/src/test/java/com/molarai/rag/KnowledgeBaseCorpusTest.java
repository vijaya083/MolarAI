package com.molarai.rag;

import com.molarai.model.KnowledgeDocument;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class KnowledgeBaseCorpusTest {
    @Test
    void loadsAndChunksAllSampleClinicDocumentsWithoutCallingAnEmbeddingProvider() {
        List<KnowledgeDocument> documents =
                new FileSystemKnowledgeDocumentLoader(Path.of("../docs/knowledge-base")).loadDocuments();
        DocumentChunker chunker = new DocumentChunker(1200, 150);
        int chunkCount = documents.stream().mapToInt(document -> chunker.chunk(document.content()).size()).sum();

        assertEquals(10, documents.size());
        assertFalse(documents.stream().anyMatch(document -> document.content().isBlank()));
        assertEquals(10, chunkCount);
        System.out.printf("Sample clinic corpus: %d documents, %d chunks before embedding%n", documents.size(), chunkCount);
    }
}
