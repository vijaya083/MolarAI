package com.molarai.config;

import com.molarai.service.CancellationOtpPolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.security.SecureRandom;
import java.time.Duration;

@Configuration(proxyBeanMethods = false)
public class CancellationOtpConfiguration {
    @Bean
    SecureRandom cancellationOtpRandom() {
        return new SecureRandom();
    }

    @Bean
    CancellationOtpPolicy cancellationOtpPolicy(
            @Value("${molarai.cancellation.otp.expiry-seconds:300}") long expirySeconds,
            @Value("${molarai.cancellation.otp.resend-cooldown-seconds:60}") long resendCooldownSeconds,
            @Value("${molarai.cancellation.otp.max-attempts:5}") int maxAttempts) {
        return new CancellationOtpPolicy(Duration.ofSeconds(expirySeconds), Duration.ofSeconds(resendCooldownSeconds), maxAttempts);
    }
}
