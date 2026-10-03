package com.molarai.rag;

import com.molarai.model.DocumentChunk;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentChunkerTest {
    @Test
    void keepsShortDocumentInOneChunk() {
        assertEquals(List.of(new DocumentChunk(0, "short note")), new DocumentChunker(20, 3).chunk(" short note "));
    }

    @Test
    void splitsLongDocumentAndKeepsSequentialIndexes() {
        List<DocumentChunk> chunks = new DocumentChunker(10, 2).chunk("abcdefghijklmnopqrstuvwxyz0123456789");

        assertTrue(chunks.size() > 1);
        for (int index = 0; index < chunks.size(); index++) {
            assertEquals(index, chunks.get(index).chunkIndex());
            assertTrue(chunks.get(index).content().length() <= 10);
        }
    }

    @Test
    void exactlyAtBoundaryProducesOneChunk() {
        assertEquals(List.of(new DocumentChunk(0, "0123456789")),
                new DocumentChunker(10, 2).chunk("0123456789"));
    }

    @Test
    void emptyDocumentProducesNoChunks() {
        assertTrue(new DocumentChunker(10, 2).chunk("").isEmpty());
    }

    @Test
    void whitespaceOnlyDocumentProducesNoChunks() {
        assertTrue(new DocumentChunker(10, 2).chunk(" \n \t ").isEmpty());
    }

    @Test
    void overlappingChunksRetainSharedText() {
        List<DocumentChunk> chunks = new DocumentChunker(10, 2).chunk("abcdefghijklmnopqrstuvwxyz");

        assertTrue(chunks.size() > 1);
        String first = chunks.get(0).content();
        String second = chunks.get(1).content();
        assertEquals(first.substring(first.length() - 2), second.substring(0, 2));
    }
}
