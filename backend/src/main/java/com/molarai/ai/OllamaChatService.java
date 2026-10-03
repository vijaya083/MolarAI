package com.molarai.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.molarai.performance.PerformanceTiming;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

@Service
public class OllamaChatService implements ToolCallingLlmService {
    private static final int DEFAULT_CHAT_OUTPUT_TOKENS = 256;
    private static final int DEFAULT_FAQ_OUTPUT_TOKENS = 128;
    private static final int MAX_FINAL_ANSWER_WORDS = 50;
    private static final Map<String, Object> RESPONSE_FORMAT = Map.of(
            "type", "object",
            "properties", Map.of("answer", Map.of(
                    "type", "string",
                    "description", "A direct, concise 1 to 3 sentence response to the user, with no reasoning or source analysis.")),
            "required", List.of("answer"),
            "additionalProperties", false);

    private final RestClient restClient;
    private final String model;
    private final ObjectMapper objectMapper;
    private final PerformanceTiming timing;
    private final int maxChatOutputTokens;
    private final int faqMaxOutputTokens;

    @Autowired
    public OllamaChatService(
            RestClient.Builder restClientBuilder,
            @Value("${molarai.ollama.chat.base-url:http://localhost:11434/api}") String baseUrl,
            @Value("${molarai.ollama.chat.model:qwen3:4b}") String model,
            @Value("${molarai.ollama.chat.api-key:}") String apiKey,
            ObjectMapper objectMapper,
            PerformanceTiming timing,
            @Value("${molarai.ollama.chat.max-output-tokens:256}") int maxChatOutputTokens,
            @Value("${molarai.ollama.chat.faq-max-output-tokens:128}") int faqMaxOutputTokens,
            @Value("${molarai.ollama.chat.timeout-seconds:45}") int timeoutSeconds) {
        RestClient.Builder configuredBuilder = restClientBuilder.baseUrl(baseUrl);
        if (apiKey != null && !apiKey.isBlank()) {
            configuredBuilder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey.trim());
        }
        if (timeoutSeconds > 0) {
            HttpClient httpClient = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(Math.min(timeoutSeconds, 10)))
                    .build();
            JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
            requestFactory.setReadTimeout(Duration.ofSeconds(timeoutSeconds));
            configuredBuilder.requestFactory(requestFactory);
        }
        this.restClient = configuredBuilder.build();
        this.model = model;
        this.objectMapper = objectMapper;
        this.timing = timing;
        if (maxChatOutputTokens < 1 || faqMaxOutputTokens < 1) {
            throw new IllegalArgumentException("Ollama chat output token limit is invalid");
        }
        this.maxChatOutputTokens = maxChatOutputTokens;
        this.faqMaxOutputTokens = faqMaxOutputTokens;
    }

    OllamaChatService(
            RestClient.Builder restClientBuilder,
            String baseUrl,
            String model,
            String apiKey,
            ObjectMapper objectMapper) {
        this(restClientBuilder, baseUrl, model, apiKey, objectMapper, new PerformanceTiming(),
                DEFAULT_CHAT_OUTPUT_TOKENS, DEFAULT_FAQ_OUTPUT_TOKENS, 0);
    }

    @Override
    public String generate(String systemPrompt, String userPrompt) {
        if (systemPrompt == null || systemPrompt.isBlank() || userPrompt == null || userPrompt.isBlank()) {
            throw new IllegalArgumentException("System and user prompts must not be blank");
        }

        ChatResponse response;
        timing.increment("llm_calls");
        try {
            try (var ignored = timing.stage("faq_llm_request")) {
                response = restClient.post()
                        .uri("/chat")
                        .body(new ChatRequest(model, List.of(
                                new RequestMessage("system", systemPrompt),
                                new RequestMessage("user", userPrompt)), false, false,
                                Map.of("num_predict", faqMaxOutputTokens), RESPONSE_FORMAT))
                        .retrieve()
                        .body(ChatResponse.class);
            }
        } catch (RestClientException exception) {
            throw new LlmProviderException("Ollama chat request failed", exception);
        }

        if (response == null || !model.equals(response.model()) || response.message() == null
                || response.message().content() == null || response.message().content().isBlank()) {
            throw new LlmProviderException("Language model returned an incomplete response");
        }
        if ("length".equals(response.completionReason())) {
            timing.event("llm_output_limit_exceeded", "path", "faq");
            throw new LlmOutputLimitException("The assistant could not complete that response");
        }
        return extractCustomerAnswer(response.message().content());
    }

    @Override
    public LlmChatResult chatWithTools(
            String systemPrompt,
            List<LlmChatMessage> messages,
            List<LlmToolDefinition> tools) {
        if (systemPrompt == null || systemPrompt.isBlank() || messages == null || messages.isEmpty()
                || tools == null || tools.isEmpty()) {
            throw new IllegalArgumentException("System prompt, conversation messages, and tools are required");
        }

        boolean afterToolResult = messages.stream().anyMatch(message -> "tool".equals(message.role()));
        ToolChatResponse response = requestToolChat(systemPrompt, messages, tools, afterToolResult, maxChatOutputTokens);
        List<LlmToolCall> toolCalls = mapToolCalls(response);
        String content = response.message().content();
        String finishReason = safeFinishReason(response.completionReason());

        // Never return a response terminated at the output limit or execute a
        // potentially truncated native tool call. FAQ answers use a separate,
        // concise generation path without appointment tools.
        if ("length".equals(response.completionReason())) {
            logResponseShape(content, toolCalls, finishReason);
            timing.event("llm_output_limit_exceeded", "path", afterToolResult ? "appointment_final" : "appointment_tool_selection");
            throw new LlmOutputLimitException("The assistant could not complete that response");
        }
        if (toolCalls.isEmpty()) {
            toolCalls = recoverToolCallFromContent(content, tools);
            if (!toolCalls.isEmpty()) {
                timing.event("tool_call_recovered_from_content", "tool", toolCalls.getFirst().name());
            }
        }
        logResponseShape(content, toolCalls, finishReason);
        if (!toolCalls.isEmpty()) {
            timing.event("tool_call_detected", "tool", toolCalls.getFirst().name());
            // The assistant's free-form content can contain analysis; only the call itself
            // is needed. The appointment flow formats the tool result locally.
            return new LlmChatResult("", toolCalls);
        }
        timing.event("tool_call_not_emitted");
        // A no-tool response is the model's customer-facing answer for this turn.
        // Validate it on both the initial (FAQ) path and the post-tool path. The
        // separate Ollama `thinking` field is intentionally never considered.
        return new LlmChatResult(extractCustomerAnswer(content), List.of());
    }

    private ToolChatResponse requestToolChat(String systemPrompt, List<LlmChatMessage> messages,
            List<LlmToolDefinition> tools, boolean afterToolResult, int outputTokens) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("model", model);
        request.put("messages", toOllamaMessages(systemPrompt, messages));
        request.put("stream", false);
        request.put("think", false);
        request.put("options", Map.of("num_predict", outputTokens));
        request.put("tools", tools.stream().map(this::toOllamaTool).toList());
        // Preserve unconstrained native tool selection on the initial turn.
        if (afterToolResult) request.put("format", RESPONSE_FORMAT);

        timing.increment("llm_calls");
        String stage = afterToolResult ? "appointment_final_llm_request" : "appointment_tool_selection_llm_request";
        try {
            try (var ignored = timing.stage(stage)) {
                ToolChatResponse response = restClient.post().uri("/chat").body(request)
                        .retrieve().body(ToolChatResponse.class);
                if (response == null || !model.equals(response.model()) || response.message() == null) {
                    throw new LlmProviderException("Language model returned an incomplete response");
                }
                return response;
            }
        } catch (RestClientException exception) {
            throw new LlmProviderException("Ollama chat request failed", exception);
        }
    }

    private List<LlmToolCall> mapToolCalls(ToolChatResponse response) {
        return response.message().toolCalls() == null ? List.of() : response.message().toolCalls().stream()
                .filter(call -> call != null && call.function() != null && call.function().name() != null)
                .map(call -> new LlmToolCall(call.id(), call.function().name(),
                        normalizeArguments(call.function().arguments())))
                .filter(call -> call.arguments() != null && call.arguments().isObject())
                .toList();
    }

    /**
     * gpt-oss sometimes prints a single function call in {@code message.content}
     * instead of {@code tool_calls}. Accept only a JSON object naming one supplied tool.
     * Prose that mentions the tool is left for customer-answer validation.
     */
    private List<LlmToolCall> recoverToolCallFromContent(String content, List<LlmToolDefinition> tools) {
        if (content == null || content.isBlank()) return List.of();
        String trimmed = content.trim();
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            int lastFence = trimmed.lastIndexOf("```");
            if (firstNewline > 0 && lastFence > firstNewline) {
                trimmed = trimmed.substring(firstNewline + 1, lastFence).trim();
            }
        }
        JsonNode node;
        try {
            node = objectMapper.readTree(trimmed);
        } catch (JsonProcessingException exception) {
            return List.of();
        }
        if (node == null || !node.isObject() || node.has("answer")) return List.of();
        JsonNode callNode = node;
        if (node.has("tool_calls") && node.get("tool_calls").isArray()) {
            if (node.get("tool_calls").size() != 1) return List.of();
            callNode = node.get("tool_calls").get(0);
        }
        JsonNode function = callNode != null && callNode.has("function") ? callNode.get("function") : callNode;
        if (function == null || !function.isObject()) return List.of();
        String name = function.path("name").asText("");
        if (tools.stream().noneMatch(tool -> tool.name().equals(name))) return List.of();
        JsonNode arguments = normalizeArguments(function.get("arguments"));
        if (arguments == null || !arguments.isObject()) return List.of();
        return List.of(new LlmToolCall(null, name, arguments));
    }

    private JsonNode normalizeArguments(JsonNode arguments) {
        if (arguments != null && arguments.isTextual()) {
            try {
                return objectMapper.readTree(arguments.asText());
            } catch (JsonProcessingException exception) {
                return arguments;
            }
        }
        return arguments;
    }

    private void logResponseShape(String content, List<LlmToolCall> toolCalls, String finishReason) {
        timing.event("llm_response_shape", "content_present", Boolean.toString(content != null));
        timing.event("llm_response_shape", "content_length", Integer.toString(content == null ? 0 : content.length()));
        timing.event("llm_response_shape", "native_tool_call_count", Integer.toString(toolCalls.size()));
        timing.event("llm_response_shape", "finish_reason", finishReason);
    }

    private String extractCustomerAnswer(String content) {
        if (content == null || content.isBlank()) {
            throw validationFailure("empty_content", "Ollama returned no customer-facing answer");
        }
        String trimmedContent = content.trim();
        try {
            JsonNode responseNode = objectMapper.readTree(trimmedContent);
            if (responseNode != null && responseNode.isTextual()) {
                // gpt-oss may encode a direct no-tool answer as a JSON string scalar.
                // That is still message.content; validate its text exactly like a plain
                // response instead of rejecting it because it is not an answer object.
                return validateCustomerAnswer(responseNode.asText());
            }
            if (responseNode == null || !responseNode.isObject()) {
                throw validationFailure("structured_value_not_object", "Ollama returned no customer-facing answer");
            }
            JsonNode answerNode = responseNode.path("answer");
            if (!answerNode.isTextual() || answerNode.asText().isBlank()) {
                throw validationFailure("structured_answer_missing_or_blank", "Ollama returned no customer-facing answer");
            }
            return validateCustomerAnswer(answerNode.asText());
        } catch (JsonProcessingException exception) {
            // Ollama Cloud can return a direct answer as plain text even when a
            // structured response was requested. Accept concise customer-facing text
            // while rejecting JSON-like malformed output and obvious tool/analysis narration.
            if (looksLikeStructuredContent(trimmedContent)) {
                timing.event("llm_answer_validation_failed", "category", "malformed_structured_content");
                throw new LlmProviderException("Ollama returned an invalid customer-answer format", exception);
            }
            return validateCustomerAnswer(trimmedContent);
        }
    }

    private String validateCustomerAnswer(String answer) {
        String trimmedAnswer = answer.trim();
        if (trimmedAnswer.isBlank()) {
            throw validationFailure("empty_answer", "Ollama returned no customer-facing answer");
        }
        if (trimmedAnswer.split("\\s+").length > MAX_FINAL_ANSWER_WORDS) {
            throw validationFailure("answer_over_word_limit", "Ollama returned an overlong customer-facing answer");
        }
        if (containsInternalNarration(trimmedAnswer)) {
            throw validationFailure("internal_or_tool_narration", "Ollama returned non-customer-facing content");
        }
        return trimmedAnswer;
    }

    private LlmAnswerValidationException validationFailure(String category, String safeMessage) {
        timing.event("llm_answer_validation_failed", "category", category);
        return new LlmAnswerValidationException(category, safeMessage);
    }

    private String safeFinishReason(String reason) {
        if (reason == null || reason.isBlank()) return "not_provided";
        return switch (reason) {
            case "stop", "length", "tool_calls" -> reason;
            default -> "other";
        };
    }

    private boolean looksLikeStructuredContent(String content) {
        return content.startsWith("{") || content.startsWith("[") || content.startsWith("\"");
    }

    private boolean containsInternalNarration(String answer) {
        String normalized = answer.toLowerCase(Locale.ROOT);
        return normalized.contains("get_available_appointment_slots")
                || normalized.contains("tool call")
                || normalized.contains("function call")
                || normalized.contains("i must call")
                || normalized.contains("i need to call")
                || normalized.contains("i should call")
                || normalized.contains("i will call")
                || normalized.contains("let me call")
                || normalized.contains("i need to check")
                || normalized.contains("i should check")
                || normalized.contains("let me check")
                || normalized.contains("the user is asking")
                || normalized.contains("first, i need")
                || normalized.contains("okay, let's tackle")
                || normalized.contains("let's analyze")
                || normalized.contains("looking at the context")
                || normalized.contains("wait, the key point")
                || normalized.contains("not available in the clinic records")
                || normalized.contains("not available in the records")
                || normalized.contains("clinic context does not contain")
                || normalized.contains("context does not contain live appointment")
                || normalized.contains("information is not available in the records")
                || normalized.contains("private reasoning")
                || normalized.contains("internal analysis");
    }

    private List<Map<String, Object>> toOllamaMessages(String systemPrompt, List<LlmChatMessage> messages) {
        List<Map<String, Object>> result = new ArrayList<>();
        result.add(Map.of("role", "system", "content", systemPrompt));
        for (LlmChatMessage message : messages) {
            Map<String, Object> serialized = new LinkedHashMap<>();
            serialized.put("role", message.role());
            serialized.put("content", message.content() == null ? "" : message.content());
            if (message.toolName() != null) serialized.put("tool_name", message.toolName());
            if (!message.toolCalls().isEmpty()) {
                serialized.put("tool_calls", message.toolCalls().stream().map(call -> {
                    Map<String, Object> function = new LinkedHashMap<>();
                    function.put("name", call.name());
                    function.put("arguments", call.arguments());
                    return Map.of("function", function);
                }).toList());
            }
            result.add(serialized);
        }
        return result;
    }

    private Map<String, Object> toOllamaTool(LlmToolDefinition tool) {
        return Map.of("type", "function", "function", Map.of(
                "name", tool.name(), "description", tool.description(), "parameters", tool.parameters()));
    }

    private record ChatRequest(
            String model,
            List<RequestMessage> messages,
            boolean stream,
            boolean think,
            Map<String, Object> options,
            Map<String, Object> format) {
    }

    private record RequestMessage(String role, String content) {
    }

    private record ChatResponse(
            String model,
            ChatResponseMessage message,
            @JsonProperty("done_reason") String doneReason,
            @JsonProperty("finish_reason") String finishReason) {
        String completionReason() {
            if ("length".equals(doneReason) || "length".equals(finishReason)) return "length";
            return doneReason != null ? doneReason : finishReason;
        }
    }

    private record ChatResponseMessage(String role, String content, String thinking) {
        // `thinking` is intentionally deserialized but never used as answer content.
    }

    private record ToolChatResponse(
            String model,
            ToolChatMessage message,
            @JsonProperty("done_reason") String doneReason,
            @JsonProperty("finish_reason") String finishReason) {
        String completionReason() {
            if ("length".equals(doneReason) || "length".equals(finishReason)) return "length";
            return doneReason != null ? doneReason : finishReason;
        }
    }

    private record ToolChatMessage(
            String role,
            String content,
            String thinking,
            @JsonProperty("tool_calls") List<ToolCallResponse> toolCalls) {
        // Ollama Cloud may return private reasoning separately; it is never forwarded to callers.
    }

    private record ToolCallResponse(String id, FunctionCall function) {
    }

    private record FunctionCall(String name, JsonNode arguments) {
    }
}
