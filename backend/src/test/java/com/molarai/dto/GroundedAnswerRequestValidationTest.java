package com.molarai.dto;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GroundedAnswerRequestValidationTest {
    @Test
    void rejectsNullAndBlankQueries() {
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

        assertEquals(1, validator.validate(new GroundedAnswerRequest(null)).size());
        assertEquals(1, validator.validate(new GroundedAnswerRequest("  ")).size());
    }
}
