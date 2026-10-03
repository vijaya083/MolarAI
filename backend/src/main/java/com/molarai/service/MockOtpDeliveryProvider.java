package com.molarai.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "molarai.cancellation.otp.provider", havingValue = "mock", matchIfMissing = true)
public class MockOtpDeliveryProvider implements OtpDeliveryProvider {
    private final boolean exposeCode;

    public MockOtpDeliveryProvider(
            @Value("${molarai.cancellation.otp.expose-code:false}") boolean exposeCode,
            Environment environment) {
        this.exposeCode = exposeCode
                && environment.acceptsProfiles(Profiles.of("dev", "development"))
                && !environment.acceptsProfiles(Profiles.of("prod", "production"));
    }

    @Override
    public DeliveryResult send(String destination, String otp) {
        // Deliberately do not log or persist the destination or raw OTP.
        return new DeliveryResult(exposeCode ? otp : null);
    }
}
