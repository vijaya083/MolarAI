package com.molarai.repository;

import com.molarai.model.KnowledgeChunk;
import com.molarai.model.KnowledgeSearchMatch;
import com.molarai.model.StoredKnowledgeChunk;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface KnowledgeChunkRepository {
    void replaceDocumentChunks(String documentId, List<KnowledgeChunk> chunks);

    Optional<StoredKnowledgeChunk> findById(UUID id);

    int countChunks();

    int countDocuments();

    List<Map<String, Object>> findMetadataByDocumentId(String documentId);

    List<KnowledgeSearchMatch> searchSimilar(List<Double> queryEmbedding, int topK);
}
