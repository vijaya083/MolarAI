package com.molarai.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.molarai.model.KnowledgeChunk;
import com.molarai.model.KnowledgeSearchMatch;
import com.molarai.model.VectorCodec;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.SQLException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JdbcKnowledgeChunkRepositoryTest {
    @Test
    void replacesDocumentChunksUsingJsonAndPgvectorParameters() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        JdbcKnowledgeChunkRepository repository = new JdbcKnowledgeChunkRepository(jdbcTemplate, new ObjectMapper());
        KnowledgeChunk chunk = new KnowledgeChunk(UUID.randomUUID(), "hours", "Clinic Hours", 0,
                "Open Monday", Map.of("sourceFilename", "hours.md"), List.of(0.1, 0.2));

        repository.replaceDocumentChunks("hours", List.of(chunk));

        verify(jdbcTemplate).update("DELETE FROM document_chunks WHERE document_id = ?", "hours");
        verify(jdbcTemplate).batchUpdate(anyString(), anyList());
    }

    @Test
    void countsStoredChunks() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class))).thenReturn(7);

        assertEquals(7, new JdbcKnowledgeChunkRepository(jdbcTemplate, new ObjectMapper()).countChunks());
    }

    @Test
    void vectorCodecRoundTripsValuesAndRejectsNonFiniteValues() {
        assertEquals(List.of(0.1, -0.25, 1.0), VectorCodec.decode(VectorCodec.encode(List.of(0.1, -0.25, 1.0))));
        assertThrows(IllegalArgumentException.class, () -> VectorCodec.encode(List.of(Double.NaN)));
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void searchesWithPgvectorCosineDistanceAndMapsMetadataAndDistance() throws SQLException {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ResultSet resultSet = mock(ResultSet.class);
        when(resultSet.getString("document_id")).thenReturn("insurance");
        when(resultSet.getString("document_name")).thenReturn("03-insurance.md");
        when(resultSet.getInt("chunk_index")).thenReturn(0);
        when(resultSet.getString("content")).thenReturn("The clinic is out of network.");
        when(resultSet.getString("metadata")).thenReturn("{\"sourceFilename\":\"03-insurance.md\"}");
        when(resultSet.getDouble("cosine_distance")).thenReturn(0.08);
        when(jdbcTemplate.query(contains("<=>"), any(RowMapper.class), any(Object[].class)))
                .thenAnswer(invocation -> {
                    @SuppressWarnings("unchecked")
                    RowMapper<KnowledgeSearchMatch> mapper = invocation.getArgument(1);
                    return List.of(mapper.mapRow(resultSet, 0));
                });
        JdbcKnowledgeChunkRepository repository = new JdbcKnowledgeChunkRepository(jdbcTemplate, new ObjectMapper());

        List<KnowledgeSearchMatch> results = repository.searchSimilar(List.of(0.1, 0.2, 0.3), 3);

        assertEquals(1, results.size());
        assertEquals("03-insurance.md", results.getFirst().documentName());
        assertEquals(Map.of("sourceFilename", "03-insurance.md"), results.getFirst().metadata());
        assertEquals(0.08, results.getFirst().cosineDistance());
        org.mockito.ArgumentCaptor<String> sql = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.ArgumentCaptor<Object[]> parameters = org.mockito.ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).query(sql.capture(), any(RowMapper.class), parameters.capture());
        org.junit.jupiter.api.Assertions.assertTrue(sql.getValue().contains("ORDER BY document_chunks.embedding <=>"));
        org.junit.jupiter.api.Assertions.assertTrue(sql.getValue().contains("LIMIT ?"));
        assertEquals("[0.1,0.2,0.3]", parameters.getValue()[0]);
        assertEquals(3, parameters.getValue()[1]);
    }
}
