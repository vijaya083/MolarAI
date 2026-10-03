package com.molarai.evaluation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class EvaluationDatasetLoader {
    private final ObjectMapper objectMapper;
    private final Path datasetPath;

    public EvaluationDatasetLoader(
            ObjectMapper objectMapper,
            @Value("${molarai.evaluation.dataset-path:../docs/evaluation/rag-evaluation.json}") String datasetPath) {
        this.objectMapper = objectMapper;
        this.datasetPath = Path.of(datasetPath);
    }

    public List<RagEvaluationCase> loadConfiguredDataset() {
        return load(datasetPath);
    }

    public List<RagEvaluationCase> load(Path path) {
        try {
            List<RagEvaluationCase> cases = objectMapper.readValue(
                    Files.readString(path), new TypeReference<>() { });
            validate(cases, path);
            return cases;
        } catch (IOException exception) {
            throw new IllegalStateException("Could not read evaluation dataset at " + path, exception);
        }
    }

    private static void validate(List<RagEvaluationCase> cases, Path path) {
        if (cases == null || cases.isEmpty()) {
            throw new IllegalStateException("Evaluation dataset is empty: " + path);
        }
        Set<String> ids = new HashSet<>();
        for (RagEvaluationCase testCase : cases) {
            if (testCase.id() == null || testCase.id().isBlank() || !ids.add(testCase.id())) {
                throw new IllegalStateException("Evaluation case IDs must be non-blank and unique: " + path);
            }
            if (testCase.question() == null || testCase.question().isBlank() || testCase.category() == null
                    || testCase.category().isBlank() || testCase.expectedBehavior() == null) {
                throw new IllegalStateException("Evaluation case has missing required fields: " + testCase.id());
            }
            if (testCase.expectedBehavior() == ExpectedBehavior.ANSWER_FROM_KNOWLEDGE
                    && testCase.expectedDocumentIds().isEmpty()) {
                throw new IllegalStateException("Supported case must name an expected document ID: " + testCase.id());
            }
            if (testCase.expectedDocumentIds().stream().anyMatch(id -> id == null || id.isBlank())
                    || testCase.expectedDocumentIds().stream().distinct().count() != testCase.expectedDocumentIds().size()) {
                throw new IllegalStateException("Expected document IDs must be non-blank and unique: " + testCase.id());
            }
            if (testCase.expectedBehavior() == ExpectedBehavior.ANSWER_FROM_KNOWLEDGE
                    && testCase.expectedAnswerPhrases().isEmpty()) {
                throw new IllegalStateException("Supported case must name answer evidence phrases: " + testCase.id());
            }
            if (testCase.expectedBehavior() == ExpectedBehavior.ACKNOWLEDGE_MISSING_INFORMATION
                    && !testCase.expectedDocumentIds().isEmpty()) {
                throw new IllegalStateException("Unsupported case must have no expected document ID: " + testCase.id());
            }
            try {
                testCase.forbiddenAnswerPatterns().forEach(Pattern::compile);
            } catch (RuntimeException exception) {
                throw new IllegalStateException("Evaluation case has an invalid answer pattern: " + testCase.id(), exception);
            }
        }
    }
}
