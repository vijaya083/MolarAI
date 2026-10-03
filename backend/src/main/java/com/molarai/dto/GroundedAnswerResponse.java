package com.molarai.dto;

import com.molarai.model.GroundedAnswerSource;

import java.util.List;

public record GroundedAnswerResponse(String answer, List<GroundedAnswerSource> sources, String answerSource) {
    public GroundedAnswerResponse(String answer, List<GroundedAnswerSource> sources) {
        this(answer, sources, "knowledge_base");
    }
}
