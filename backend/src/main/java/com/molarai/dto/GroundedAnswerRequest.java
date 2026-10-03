package com.molarai.dto;

import jakarta.validation.constraints.NotBlank;

public record GroundedAnswerRequest(@NotBlank String query) {
}
