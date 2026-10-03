package com.molarai.dto;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class KnowledgeSearchRequestValidationTest {
    @Test
    void rejectsBlankQueryAndOutOfRangeTopK() {
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

        assertEquals(2, validator.validate(new KnowledgeSearchRequest("  ", 0)).size());
        assertEquals(1, validator.validate(new KnowledgeSearchRequest("clinic hours", 11)).size());
    }
}
