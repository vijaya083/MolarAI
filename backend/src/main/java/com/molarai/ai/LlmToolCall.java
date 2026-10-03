package com.molarai.ai;

import com.fasterxml.jackson.databind.JsonNode;

public record LlmToolCall(String id, String name, JsonNode arguments) {
}
