package com.molarai.ai;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
@ConditionalOnProperty(name = "molarai.embedding.provider", havingValue = "openai")
public class OpenAiEmbeddingService implements EmbeddingService {
    private static final int MAX_INPUTS_PER_REQUEST = 64;

    private final RestClient restClient;
    private final String apiKey;
    private final String model;
    private final int dimension;

    public OpenAiEmbeddingService(
            RestClient.Builder restClientBuilder,
            @Value("${molarai.embedding.api-key:}") String apiKey,
            @Value("${molarai.embedding.model:text-embedding-3-small}") String model,
            @Value("${molarai.embedding.dimension:768}") int dimension,
            @Value("${molarai.embedding.base-url:https://api.openai.com/v1}") String baseUrl) {
        if (dimension <= 0) {
            throw new IllegalArgumentException("Embedding dimension must be positive");
        }
        this.restClient = restClientBuilder.baseUrl(baseUrl).build();
        this.apiKey = apiKey;
        this.model = model;
        this.dimension = dimension;
    }

    @Override
    public List<List<Double>> embedAll(List<String> inputs) {
        if (inputs.isEmpty()) {
            return List.of();
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new EmbeddingProviderException(
                    "EMBEDDING_API_KEY is required to generate embeddings; no embeddings were generated");
        }
        if (inputs.stream().anyMatch(input -> input == null || input.isBlank())) {
            throw new IllegalArgumentException("Embedding inputs must not be blank");
        }

        List<List<Double>> results = new ArrayList<>(inputs.size());
        for (int start = 0; start < inputs.size(); start += MAX_INPUTS_PER_REQUEST) {
            int end = Math.min(start + MAX_INPUTS_PER_REQUEST, inputs.size());
            results.addAll(embedBatch(inputs.subList(start, end)));
        }
        return List.copyOf(results);
    }

    private List<List<Double>> embedBatch(List<String> inputs) {
        EmbeddingResponse response;
        try {
            response = restClient.post()
                    .uri("/embeddings")
                    .header("Authorization", "Bearer " + apiKey)
                    .body(new EmbeddingRequest(model, inputs, dimension))
                    .retrieve()
                    .body(EmbeddingResponse.class);
        } catch (RestClientException exception) {
            throw new EmbeddingProviderException("Embedding provider request failed", exception);
        }
        if (response == null || response.data() == null || response.data().size() != inputs.size()) {
            throw new EmbeddingProviderException("Embedding provider returned an incomplete response");
        }

        List<EmbeddingDatum> ordered = response.data().stream()
                .sorted(Comparator.comparingInt(EmbeddingDatum::index))
                .toList();
        List<List<Double>> embeddings = new ArrayList<>(ordered.size());
        for (int index = 0; index < ordered.size(); index++) {
            EmbeddingDatum datum = ordered.get(index);
            if (datum.index() != index || datum.embedding() == null || datum.embedding().size() != dimension) {
                throw new EmbeddingProviderException(
                        "Embedding provider response index or vector dimension did not match configuration");
            }
            if (datum.embedding().stream().anyMatch(value -> value == null || !Double.isFinite(value))) {
                throw new EmbeddingProviderException("Embedding provider returned a non-finite vector component");
            }
            embeddings.add(List.copyOf(datum.embedding()));
        }
        return embeddings;
    }

    private record EmbeddingRequest(String model, List<String> input, int dimensions) {
    }

    private record EmbeddingResponse(List<EmbeddingDatum> data) {
    }

    private record EmbeddingDatum(int index, List<Double> embedding) {
    }
}
