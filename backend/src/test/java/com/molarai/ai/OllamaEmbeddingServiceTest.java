package com.molarai.ai;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class OllamaEmbeddingServiceTest {
    @Test
    void embedsSingleInputUsingConfiguredUrlAndModel() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://ollama.test:11434/api/embed"))
                .andExpect(method(POST))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().json("""
                        {"model":"embeddinggemma:latest","input":["clinic hours"]}
                        """))
                .andRespond(withSuccess("""
                        {"model":"embeddinggemma:latest","embeddings":[[0.1,0.2,0.3]]}
                        """, MediaType.APPLICATION_JSON));
        OllamaEmbeddingService service = new OllamaEmbeddingService(
                builder, "http://ollama.test:11434", "embeddinggemma:latest", 3);

        assertEquals(List.of(List.of(0.1, 0.2, 0.3)), service.embedAll(List.of("clinic hours")));
        server.verify();
    }

    @Test
    void sendsBatchAsOneRequestAndReturnsOneVectorPerInput() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://localhost:11434/api/embed"))
                .andExpect(content().json("""
                        {"model":"embeddinggemma","input":["clinic hours","pediatric care"]}
                        """))
                .andRespond(withSuccess("""
                        {"model":"embeddinggemma","embeddings":[[0.1,0.2,0.3],[0.4,0.5,0.6]]}
                        """, MediaType.APPLICATION_JSON));
        OllamaEmbeddingService service = new OllamaEmbeddingService(
                builder, "http://localhost:11434", "embeddinggemma", 3);

        assertEquals(List.of(List.of(0.1, 0.2, 0.3), List.of(0.4, 0.5, 0.6)),
                service.embedAll(List.of("clinic hours", "pediatric care")));
        server.verify();
    }

    @Test
    void rejectsWrongVectorDimension() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://localhost:11434/api/embed"))
                .andRespond(withSuccess("""
                        {"model":"embeddinggemma","embeddings":[[0.1,0.2]]}
                        """, MediaType.APPLICATION_JSON));
        OllamaEmbeddingService service = new OllamaEmbeddingService(
                builder, "http://localhost:11434", "embeddinggemma", 3);

        assertThrows(EmbeddingProviderException.class, () -> service.embedAll(List.of("content")));
        server.verify();
    }

    @Test
    void rejectsMalformedResponse() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://localhost:11434/api/embed"))
                .andRespond(withSuccess("""
                        {"model":"embeddinggemma","embeddings":[]}
                        """, MediaType.APPLICATION_JSON));
        OllamaEmbeddingService service = new OllamaEmbeddingService(
                builder, "http://localhost:11434", "embeddinggemma", 3);

        assertThrows(EmbeddingProviderException.class, () -> service.embedAll(List.of("content")));
        server.verify();
    }

    @Test
    void wrapsHttpFailure() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://localhost:11434/api/embed")).andRespond(withServerError());
        OllamaEmbeddingService service = new OllamaEmbeddingService(
                builder, "http://localhost:11434", "embeddinggemma", 3);

        assertThrows(EmbeddingProviderException.class, () -> service.embedAll(List.of("content")));
        server.verify();
    }
}
