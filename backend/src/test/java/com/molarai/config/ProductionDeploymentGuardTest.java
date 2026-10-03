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
                () -> new ProductionDeploymentGuard(true, "sms", "openai", "", "", "chat-key"));
        assertThrows(IllegalStateException.class,
                () -> new ProductionDeploymentGuard(true, "sms", "ollama", "", "", "chat-key"));
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
}
