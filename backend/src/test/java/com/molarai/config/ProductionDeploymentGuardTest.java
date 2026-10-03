package com.molarai.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

class ProductionDeploymentGuardTest {
    @Test
    void rejectsMockOtpDeliveryInProduction() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new ProductionDeploymentGuard("mock", "openai", "key", "", "chat-key"));

        org.junit.jupiter.api.Assertions.assertTrue(error.getMessage().contains("real OTP delivery provider"));
    }

    @Test
    void requiresProviderCredentialsForProduction() {
        assertThrows(IllegalStateException.class,
                () -> new ProductionDeploymentGuard("sms", "openai", "", "", "chat-key"));
        assertThrows(IllegalStateException.class,
                () -> new ProductionDeploymentGuard("sms", "ollama", "", "", "chat-key"));
        assertThrows(IllegalStateException.class,
                () -> new ProductionDeploymentGuard("sms", "openai", "key", "", ""));
    }
}
