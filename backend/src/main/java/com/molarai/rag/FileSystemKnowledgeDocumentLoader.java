package com.molarai.rag;

import com.molarai.model.KnowledgeDocument;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Component
public class FileSystemKnowledgeDocumentLoader implements KnowledgeDocumentLoader {
    private static final Pattern TITLE = Pattern.compile("(?m)^#\\s+(.+?)\\s*$");

    private final Path knowledgeBasePath;

    @Autowired
    public FileSystemKnowledgeDocumentLoader(
            @Value("${molarai.knowledge-base.path:../docs/knowledge-base}") String knowledgeBasePath) {
        this(Path.of(knowledgeBasePath));
    }

    public FileSystemKnowledgeDocumentLoader(Path knowledgeBasePath) {
        this.knowledgeBasePath = knowledgeBasePath.toAbsolutePath().normalize();
    }

    @Override
    public List<KnowledgeDocument> loadDocuments() {
        if (!Files.isDirectory(knowledgeBasePath)) {
            throw new IllegalStateException("Knowledge-base directory does not exist: " + knowledgeBasePath);
        }
        try (Stream<Path> paths = Files.list(knowledgeBasePath)) {
            List<Path> markdownFiles = paths
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase().endsWith(".md"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
            if (markdownFiles.isEmpty()) {
                throw new IllegalStateException("No Markdown knowledge documents found in " + knowledgeBasePath);
            }
            return markdownFiles.stream().map(this::loadDocument).toList();
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read knowledge-base directory " + knowledgeBasePath, exception);
        }
    }

    private KnowledgeDocument loadDocument(Path path) {
        String filename = path.getFileName().toString();
        String documentId = filename.substring(0, filename.length() - ".md".length());
        try {
            String content = Files.readString(path);
            Matcher titleMatcher = TITLE.matcher(content);
            String title = titleMatcher.find() ? titleMatcher.group(1) : documentId;
            return new KnowledgeDocument(documentId, title, content,
                    Map.of("sourceFilename", filename, "format", "markdown"));
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read knowledge document " + filename, exception);
        }
    }
}
