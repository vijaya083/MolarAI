package com.molarai.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

class ApiCorsConfigurationTest {
    @Test
    void rejectsWildcardOrigins() {
        assertThrows(IllegalArgumentException.class, () -> new ApiCorsConfiguration("*"));
    }
}
