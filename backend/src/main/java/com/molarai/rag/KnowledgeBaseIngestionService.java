package com.molarai.rag;

import com.molarai.ai.EmbeddingService;
import com.molarai.model.DocumentChunk;
import com.molarai.model.KnowledgeChunk;
import com.molarai.model.KnowledgeDocument;
import com.molarai.repository.KnowledgeChunkRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class KnowledgeBaseIngestionService {
    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseIngestionService.class);

    private final KnowledgeDocumentLoader documentLoader;
    private final DocumentChunker chunker;
    private final EmbeddingService embeddingService;
    private final KnowledgeChunkRepository repository;
    private final int embeddingDimension;

    public KnowledgeBaseIngestionService(
            KnowledgeDocumentLoader documentLoader,
            DocumentChunker chunker,
            EmbeddingService embeddingService,
            KnowledgeChunkRepository repository,
            @Value("${molarai.embedding.dimension:768}") int embeddingDimension) {
        this.documentLoader = documentLoader;
        this.chunker = chunker;
        this.embeddingService = embeddingService;
        this.repository = repository;
        this.embeddingDimension = embeddingDimension;
    }

    public IngestionResult ingest() {
        List<KnowledgeDocument> documents = documentLoader.loadDocuments();
        int persistedChunks = 0;
        for (KnowledgeDocument document : documents) {
            List<DocumentChunk> documentChunks = chunker.chunk(document.content());
            List<List<Double>> embeddings = embeddingService.embedAll(
                    documentChunks.stream().map(DocumentChunk::content).toList());
            if (embeddings.size() != documentChunks.size()) {
                throw new IllegalStateException("Embedding count did not match chunks for " + document.documentId());
            }

            List<KnowledgeChunk> records = new ArrayList<>(documentChunks.size());
            for (int index = 0; index < documentChunks.size(); index++) {
                List<Double> embedding = embeddings.get(index);
                if (embedding.size() != embeddingDimension) {
                    throw new IllegalStateException("Embedding dimension for " + document.documentId()
                            + " was " + embedding.size() + " but configured dimension is " + embeddingDimension);
                }
                DocumentChunk chunk = documentChunks.get(index);
                records.add(new KnowledgeChunk(
                        stableChunkId(document.documentId(), chunk.chunkIndex()),
                        document.documentId(),
                        document.documentName(),
                        chunk.chunkIndex(),
                        chunk.content(),
                        chunkMetadata(document, chunk),
                        embedding));
            }
            repository.replaceDocumentChunks(document.documentId(), records);
            persistedChunks += records.size();
            log.info("Ingested knowledge document '{}' with {} chunks", document.documentId(), records.size());
        }
        IngestionResult result = new IngestionResult(documents.size(), persistedChunks);
        log.info("Knowledge-base ingestion finished: {} documents, {} chunks", result.documentCount(), result.chunkCount());
        return result;
    }

    private Map<String, Object> chunkMetadata(KnowledgeDocument document, DocumentChunk chunk) {
        Map<String, Object> metadata = new HashMap<>(document.metadata());
        metadata.put("documentId", document.documentId());
        metadata.put("documentName", document.documentName());
        metadata.put("chunkIndex", chunk.chunkIndex());
        return Map.copyOf(metadata);
    }

    private UUID stableChunkId(String documentId, int chunkIndex) {
        return UUID.nameUUIDFromBytes((documentId + ":" + chunkIndex).getBytes(StandardCharsets.UTF_8));
    }

    public record IngestionResult(int documentCount, int chunkCount) {
    }
}
