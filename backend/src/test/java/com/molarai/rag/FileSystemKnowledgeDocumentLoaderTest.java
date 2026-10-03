package com.molarai.rag;

import com.molarai.model.KnowledgeDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileSystemKnowledgeDocumentLoaderTest {
    @TempDir
    Path directory;

    @Test
    void loadsMarkdownInStableOrderWithSourceMetadata() throws Exception {
        Files.writeString(directory.resolve("b.md"), "# Second\n\nSecond content.");
        Files.writeString(directory.resolve("a.md"), "# First\n\nFirst content.");
        Files.writeString(directory.resolve("ignored.txt"), "not a knowledge document");

        List<KnowledgeDocument> documents = new FileSystemKnowledgeDocumentLoader(directory).loadDocuments();

        assertEquals(List.of("a", "b"), documents.stream().map(KnowledgeDocument::documentId).toList());
        assertEquals("First", documents.getFirst().documentName());
        assertEquals("a.md", documents.getFirst().metadata().get("sourceFilename"));
        assertTrue(documents.getFirst().content().contains("First content."));
    }

    @Test
    void failsClearlyWhenDirectoryDoesNotExist() {
        assertThrows(IllegalStateException.class,
                () -> new FileSystemKnowledgeDocumentLoader(directory.resolve("missing")).loadDocuments());
    }

    @Test
    void failsClearlyWhenNoMarkdownDocumentsExist() throws Exception {
        Files.writeString(directory.resolve("notes.txt"), "ignored");
        assertThrows(IllegalStateException.class, () -> new FileSystemKnowledgeDocumentLoader(directory).loadDocuments());
    }
}
