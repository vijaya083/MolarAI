package com.molarai.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiErrorResponse(String error, String code) {
    public ApiErrorResponse(String error) {
        this(error, null);
    }
}
