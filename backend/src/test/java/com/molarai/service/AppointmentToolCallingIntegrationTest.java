package com.molarai.service;

import com.molarai.dto.GroundedAnswerResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Opt-in check against a running database. Availability no longer calls a language model.
 */
@SpringBootTest
@EnabledIfSystemProperty(named = "molarai.tools.live", matches = "true")
class AppointmentToolCallingIntegrationTest {
    @Autowired
    private GroundedResponseService groundedResponseService;

    @Test
    void looksUpSeededJuneSlotsWithoutSelectingAnLlmTool() {
        GroundedAnswerResponse response = groundedResponseService.answer(
                "Do you have appointments available on June 10, 2030?");
        String answer = response.answer().toLowerCase();

        assertTrue(answer.contains("dr. maya chen"), response.answer());
        assertTrue(answer.contains("dr. jordan lee"), response.answer());
        assertTrue(answer.contains("9:00") || answer.contains("10:00") || answer.contains("1:00"), response.answer());
    }
}
