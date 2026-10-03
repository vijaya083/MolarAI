package com.molarai.service;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class MockOtpDeliveryProviderTest {
    private static final String OTP = "123456";

    @Test
    void keepsOtpHiddenByDefault() {
        MockOtpDeliveryProvider provider = new MockOtpDeliveryProvider(false, new MockEnvironment());

        assertNull(provider.send("555-0100", OTP).developmentCode());
    }

    @Test
    void exposesOtpOnlyWhenExplicitlyEnabledInDevelopment() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("dev");
        MockOtpDeliveryProvider provider = new MockOtpDeliveryProvider(true, environment);

        assertEquals(OTP, provider.send("555-0100", OTP).developmentCode());
    }

    @Test
    void doesNotExposeOtpWhenEnabledWithoutDevelopmentProfile() {
        MockOtpDeliveryProvider provider = new MockOtpDeliveryProvider(true, new MockEnvironment());

        assertNull(provider.send("555-0100", OTP).developmentCode());
    }

    @Test
    void neverExposesOtpUnderProductionProfilesEvenWhenEnabled() {
        for (String profile : new String[]{"prod", "production"}) {
            MockEnvironment environment = new MockEnvironment().withProperty("spring.profiles.active", profile);
            environment.setActiveProfiles(profile);
            MockOtpDeliveryProvider provider = new MockOtpDeliveryProvider(true, environment);

            assertNull(provider.send("555-0100", OTP).developmentCode(), profile);
        }
    }

    @Test
    void doesNotExposeOtpWhenProductionProfileIsCombinedWithDevelopment() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("dev", "prod");
        MockOtpDeliveryProvider provider = new MockOtpDeliveryProvider(true, environment);

        assertNull(provider.send("555-0100", OTP).developmentCode());
    }
}
