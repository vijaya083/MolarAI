package com.molarai.evaluation;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

public enum ExpectedBehavior {
    ANSWER_FROM_KNOWLEDGE,
    ACKNOWLEDGE_MISSING_INFORMATION;

    @JsonCreator
    public static ExpectedBehavior fromJson(String value) {
        return value == null ? null : valueOf(value.trim().toUpperCase(Locale.ROOT));
    }

    @JsonValue
    public String toJson() {
        return name().toLowerCase(Locale.ROOT);
    }
}
