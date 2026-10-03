package com.molarai.ai;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.ArrayList;
import java.util.List;

@Service
@ConditionalOnProperty(name = "molarai.embedding.provider", havingValue = "ollama")
public class OllamaEmbeddingService implements EmbeddingService {
    private final RestClient restClient;
    private final String model;
    private final int dimension;

    public OllamaEmbeddingService(
            RestClient.Builder restClientBuilder,
            @Value("${molarai.ollama.embedding-base-url:http://localhost:11434}") String baseUrl,
            @Value("${molarai.embedding.model:embeddinggemma}") String model,
            @Value("${molarai.embedding.dimension:768}") int dimension) {
        if (dimension <= 0) {
            throw new IllegalArgumentException("Embedding dimension must be positive");
        }
        this.restClient = restClientBuilder.baseUrl(baseUrl).build();
        this.model = model;
        this.dimension = dimension;
    }

    @Override
    public List<List<Double>> embedAll(List<String> inputs) {
        if (inputs == null) {
            throw new IllegalArgumentException("Embedding inputs must not be null");
        }
        if (inputs.isEmpty()) {
            return List.of();
        }
        if (inputs.stream().anyMatch(input -> input == null || input.isBlank())) {
            throw new IllegalArgumentException("Embedding inputs must not be blank");
        }

        EmbeddingResponse response;
        try {
            response = restClient.post()
                    .uri("/api/embed")
                    .body(new EmbeddingRequest(model, inputs))
                    .retrieve()
                    .body(EmbeddingResponse.class);
        } catch (RestClientException exception) {
            throw new EmbeddingProviderException("Ollama embedding request failed", exception);
        }

        if (response == null || response.model() == null || response.embeddings() == null
                || !model.equals(response.model()) || response.embeddings().size() != inputs.size()) {
            throw new EmbeddingProviderException("Ollama returned an incomplete or unexpected embedding response");
        }

        List<List<Double>> embeddings = new ArrayList<>(response.embeddings().size());
        for (List<Double> embedding : response.embeddings()) {
            if (embedding == null || embedding.size() != dimension) {
                throw new EmbeddingProviderException(
                        "Ollama embedding dimension did not match configured dimension " + dimension);
            }
            if (embedding.stream().anyMatch(value -> value == null || !Double.isFinite(value))) {
                throw new EmbeddingProviderException("Ollama returned a non-finite vector component");
            }
            embeddings.add(List.copyOf(embedding));
        }
        return List.copyOf(embeddings);
    }

    private record EmbeddingRequest(String model, List<String> input) {
    }

    private record EmbeddingResponse(String model, List<List<Double>> embeddings) {
    }
}
