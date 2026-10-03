package com.molarai.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Set;

@Component
public class EmbeddingProviderConfiguration {
    private static final Set<String> SUPPORTED_PROVIDERS = Set.of("ollama", "openai");

    public EmbeddingProviderConfiguration(@Value("${molarai.embedding.provider:ollama}") String provider) {
        String normalizedProvider = provider.toLowerCase(Locale.ROOT);
        if (!SUPPORTED_PROVIDERS.contains(normalizedProvider)) {
            throw new IllegalStateException("Unsupported EMBEDDING_PROVIDER '" + provider
                    + "'. Supported values are: ollama, openai");
        }
    }
}
