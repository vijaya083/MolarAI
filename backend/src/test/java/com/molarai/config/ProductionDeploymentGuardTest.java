package com.molarai.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProductionDeploymentGuardTest {
    @Test
    void allowsProductionStartupWithMockProviderWhenCancellationIsDisabled() {
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                () -> new ProductionDeploymentGuard(false, "mock", "openai", "key", "", "chat-key"));
    }

    @Test
    void rejectsMockOtpDeliveryWhenCancellationIsEnabledInProduction() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new ProductionDeploymentGuard(true, "mock", "openai", "key", "", "chat-key"));

        org.junit.jupiter.api.Assertions.assertTrue(error.getMessage().contains("real OTP delivery provider"));
    }

    @Test
    void requiresProviderCredentialsForProduction() {
        assertThrows(IllegalStateException.class,
                () -> new ProductionDeploymentGuard(false, "sms", "openai", "", "", "chat-key"));
        assertThrows(IllegalStateException.class,
                () -> new ProductionDeploymentGuard(false, "sms", "ollama", "", "", "chat-key"));
        assertThrows(IllegalStateException.class,
                () -> new ProductionDeploymentGuard(true, "sms", "openai", "key", "", ""));
    }

    @Test
    void productionProfileReadsRenderEnvironmentVariablesAndAllowsDisabledMock() {
        productionContext("false").run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void productionProfileStillRejectsMockWhenCancellationIsEnabled() {
        productionContext("true").run(context -> assertThat(context)
                .hasFailed()
                .getFailure()
                .hasRootCauseMessage("Production startup requires a real OTP delivery provider; mock delivery is development-only"));
    }

    @Test
    void productionProfileAllowsDisabledEmbeddingsWithoutOllamaUrlOrApiKey() {
        new ApplicationContextRunner()
                .withUserConfiguration(ProductionDeploymentGuard.class)
                .withPropertyValues(
                        "spring.profiles.active=prod",
                        "CANCELLATION_ENABLED=false",
                        "molarai.cancellation.otp.provider=mock",
                        "molarai.embedding.provider=disabled",
                        "molarai.ollama.chat.api-key=test-key")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void productionDisabledProviderHasNoEmbeddingCredentialRequirementButStillRequiresChatKey() {
        new ApplicationContextRunner()
                .withUserConfiguration(ProductionDeploymentGuard.class)
                .withPropertyValues(
                        "spring.profiles.active=prod",
                        "CANCELLATION_ENABLED=false",
                        "molarai.cancellation.otp.provider=mock",
                        "molarai.embedding.provider=disabled")
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .hasRootCauseMessage("OLLAMA_API_KEY is required for production chat"));
    }

    @Test
    void productionOllamaProviderRejectsMissingEmbeddingUrl() {
        productionGuardContext("ollama", "molarai.embedding.api-key=", "molarai.ollama.embedding-base-url=")
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .hasRootCauseMessage("OLLAMA_EMBEDDING_BASE_URL is required for the Ollama embedding provider"));
    }

    @Test
    void productionOpenAiProviderRejectsMissingEmbeddingApiKey() {
        productionGuardContext("openai", "molarai.embedding.api-key=", "molarai.ollama.embedding-base-url=")
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .hasRootCauseMessage("EMBEDDING_API_KEY is required for the OpenAI embedding provider"));
    }

    private ApplicationContextRunner productionContext(String cancellationEnabled) {
        return new ApplicationContextRunner()
                .withUserConfiguration(ProductionDeploymentGuard.class)
                .withPropertyValues(
                        "spring.profiles.active=prod",
                        "CANCELLATION_ENABLED=" + cancellationEnabled,
                        "molarai.cancellation.otp.provider=mock",
                        "molarai.embedding.provider=openai",
                        "molarai.embedding.api-key=test-key",
                        "molarai.ollama.chat.api-key=test-key");
    }

    private ApplicationContextRunner productionGuardContext(String embeddingProvider, String... additionalProperties) {
        String[] properties = new String[additionalProperties.length + 5];
        properties[0] = "spring.profiles.active=prod";
        properties[1] = "CANCELLATION_ENABLED=false";
        properties[2] = "molarai.cancellation.otp.provider=mock";
        properties[3] = "molarai.embedding.provider=" + embeddingProvider;
        properties[4] = "molarai.ollama.chat.api-key=test-key";
        for (int index = 0; index < additionalProperties.length; index++) {
            properties[index + 5] = additionalProperties[index];
        }
        return new ApplicationContextRunner()
                .withUserConfiguration(ProductionDeploymentGuard.class)
                .withPropertyValues(properties);
    }
}
