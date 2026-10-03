package com.molarai.config;

import org.junit.jupiter.api.Test;

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
}
