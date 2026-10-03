package com.molarai.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.molarai.model.KnowledgeChunk;
import com.molarai.model.KnowledgeSearchMatch;
import com.molarai.model.StoredKnowledgeChunk;
import com.molarai.model.VectorCodec;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcKnowledgeChunkRepository implements KnowledgeChunkRepository {
    private static final int MAX_SEARCH_RESULTS = 10;

    private static final String INSERT_CHUNK = """
            INSERT INTO document_chunks
                (id, document_id, document_name, chunk_index, content, metadata, embedding)
            VALUES (?, ?, ?, ?, ?, CAST(? AS jsonb), CAST(? AS vector))
            """;
    private static final String SEARCH_SIMILAR = """
            WITH query_embedding AS (
                SELECT CAST(? AS vector) AS embedding
            )
            SELECT document_id, document_name, chunk_index, content, metadata::text AS metadata,
                   document_chunks.embedding <=> query_embedding.embedding AS cosine_distance
            FROM document_chunks
            CROSS JOIN query_embedding
            ORDER BY document_chunks.embedding <=> query_embedding.embedding
            LIMIT ?
            """;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final RowMapper<StoredKnowledgeChunk> rowMapper;

    public JdbcKnowledgeChunkRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.rowMapper = (resultSet, rowNumber) -> {
            Timestamp createdAt = resultSet.getTimestamp("created_at");
            return new StoredKnowledgeChunk(
                    resultSet.getObject("id", UUID.class),
                    resultSet.getString("document_id"),
                    resultSet.getString("document_name"),
                    resultSet.getInt("chunk_index"),
                    resultSet.getString("content"),
                    readMetadata(resultSet.getString("metadata")),
                    VectorCodec.decode(resultSet.getString("embedding")),
                    createdAt == null ? null : createdAt.toInstant().atOffset(java.time.ZoneOffset.UTC));
        };
    }

    @Override
    @Transactional
    public void replaceDocumentChunks(String documentId, List<KnowledgeChunk> chunks) {
        jdbcTemplate.update("DELETE FROM document_chunks WHERE document_id = ?", documentId);
        if (chunks.isEmpty()) {
            return;
        }
        List<Object[]> arguments = chunks.stream()
                .map(chunk -> new Object[]{
                        chunk.id(),
                        chunk.documentId(),
                        chunk.documentName(),
                        chunk.chunkIndex(),
                        chunk.content(),
                        toJson(chunk.metadata()),
                        VectorCodec.encode(chunk.embedding())
                })
                .toList();
        jdbcTemplate.batchUpdate(INSERT_CHUNK, arguments);
    }

    @Override
    public Optional<StoredKnowledgeChunk> findById(UUID id) {
        List<StoredKnowledgeChunk> rows = jdbcTemplate.query("""
                SELECT id, document_id, document_name, chunk_index, content, metadata::text AS metadata,
                       embedding::text AS embedding, created_at
                FROM document_chunks WHERE id = ?
                """, rowMapper, id);
        return rows.stream().findFirst();
    }

    @Override
    public int countChunks() {
        Integer count = jdbcTemplate.queryForObject("SELECT count(*) FROM document_chunks", Integer.class);
        return count == null ? 0 : count;
    }

    @Override
    public int countDocuments() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(DISTINCT document_id) FROM document_chunks", Integer.class);
        return count == null ? 0 : count;
    }

    @Override
    public List<Map<String, Object>> findMetadataByDocumentId(String documentId) {
        return jdbcTemplate.query("""
                SELECT metadata FROM document_chunks WHERE document_id = ? ORDER BY chunk_index
                """, (resultSet, rowNumber) -> readMetadata(resultSet.getString("metadata")), documentId);
    }

    @Override
    public List<KnowledgeSearchMatch> searchSimilar(List<Double> queryEmbedding, int topK) {
        if (queryEmbedding == null || queryEmbedding.isEmpty()
                || queryEmbedding.stream().anyMatch(value -> value == null || !Double.isFinite(value))) {
            throw new IllegalArgumentException("Query embedding must contain finite numeric values");
        }
        if (topK < 1 || topK > MAX_SEARCH_RESULTS) {
            throw new IllegalArgumentException("topK must be between 1 and " + MAX_SEARCH_RESULTS);
        }
        return jdbcTemplate.query(SEARCH_SIMILAR, (resultSet, rowNumber) -> new KnowledgeSearchMatch(
                        resultSet.getString("document_id"),
                        resultSet.getString("document_name"),
                        resultSet.getInt("chunk_index"),
                        resultSet.getString("content"),
                        readMetadata(resultSet.getString("metadata")),
                        resultSet.getDouble("cosine_distance")),
                VectorCodec.encode(queryEmbedding), topK);
    }

    private String toJson(Map<String, Object> metadata) {
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Knowledge metadata could not be serialized", exception);
        }
    }

    private Map<String, Object> readMetadata(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() { });
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored knowledge metadata is invalid JSON", exception);
        }
    }

}
