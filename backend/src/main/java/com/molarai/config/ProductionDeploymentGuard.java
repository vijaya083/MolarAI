package com.molarai.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Profile({"prod", "production"})
public class ProductionDeploymentGuard {
    public ProductionDeploymentGuard(
            @Value("${molarai.cancellation.enabled:false}") boolean cancellationEnabled,
            @Value("${molarai.cancellation.otp.provider:mock}") String otpProvider,
            @Value("${molarai.embedding.provider}") String embeddingProvider,
            @Value("${molarai.embedding.api-key:}") String embeddingApiKey,
            @Value("${molarai.ollama.embedding-base-url:}") String ollamaEmbeddingBaseUrl,
            @Value("${molarai.ollama.chat.api-key:}") String chatApiKey) {
        if (cancellationEnabled && "mock".equalsIgnoreCase(otpProvider)) {
            throw new IllegalStateException(
                    "Production startup requires a real OTP delivery provider; mock delivery is development-only");
        }
        if ("openai".equalsIgnoreCase(embeddingProvider) && embeddingApiKey.isBlank()) {
            throw new IllegalStateException("EMBEDDING_API_KEY is required for the OpenAI embedding provider");
        }
        if ("ollama".equalsIgnoreCase(embeddingProvider) && ollamaEmbeddingBaseUrl.isBlank()) {
            throw new IllegalStateException("OLLAMA_EMBEDDING_BASE_URL is required for the Ollama embedding provider");
        }
        if (chatApiKey.isBlank()) {
            throw new IllegalStateException("OLLAMA_API_KEY is required for production chat");
        }
    }
}
