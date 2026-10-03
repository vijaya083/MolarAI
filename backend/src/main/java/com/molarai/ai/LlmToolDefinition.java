package com.molarai.ai;

import java.util.Map;

public record LlmToolDefinition(String name, String description, Map<String, Object> parameters) {
}
