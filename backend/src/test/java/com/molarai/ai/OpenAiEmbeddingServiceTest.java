package com.molarai.ai;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.http.HttpMethod.POST;

class OpenAiEmbeddingServiceTest {
    @Test
    void sendsConfiguredModelAndDimensionAndReturnsProviderVector() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://embeddings.test/v1/embeddings"))
                .andExpect(method(POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer test-key"))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().json("""
                        {"model":"text-embedding-test","input":["clinic hours"],"dimensions":3}
                        """))
                .andRespond(withSuccess("""
                        {"data":[{"index":0,"embedding":[0.1,0.2,0.3]}]}
                        """, MediaType.APPLICATION_JSON));

        OpenAiEmbeddingService service = new OpenAiEmbeddingService(
                builder, "test-key", "text-embedding-test", 3, "https://embeddings.test/v1");

        assertEquals(List.of(List.of(0.1, 0.2, 0.3)), service.embedAll(List.of("clinic hours")));
        server.verify();
    }

    @Test
    void refusesToCallProviderWithoutKey() {
        OpenAiEmbeddingService service = new OpenAiEmbeddingService(
                RestClient.builder(), "", "text-embedding-test", 3, "https://embeddings.test/v1");

        assertThrows(EmbeddingProviderException.class, () -> service.embedAll(List.of("content")));
    }

    @Test
    void rejectsResponseWithWrongDimension() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://embeddings.test/v1/embeddings"))
                .andRespond(withSuccess("""
                        {"data":[{"index":0,"embedding":[0.1,0.2]}]}
                        """, MediaType.APPLICATION_JSON));
        OpenAiEmbeddingService service = new OpenAiEmbeddingService(
                builder, "test-key", "text-embedding-test", 3, "https://embeddings.test/v1");

        assertThrows(EmbeddingProviderException.class, () -> service.embedAll(List.of("content")));
        server.verify();
    }
}
