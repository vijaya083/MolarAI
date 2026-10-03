package com.molarai.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvaluationDatasetLoaderTest {
    @TempDir
    Path tempDir;

    private final EvaluationDatasetLoader loader = new EvaluationDatasetLoader(new ObjectMapper(), "unused.json");

    @Test
    void loadsDatasetWithSnakeCaseBehaviorAndOptionalChecks() throws IOException {
        Path dataset = tempDir.resolve("dataset.json");
        Files.writeString(dataset, """
                [{
                  "id":"hours-001",
                  "question":"When are you open?",
                  "expectedSourceDocuments":["Clinic Hours"],
                  "expectedDocumentIds":["01-clinic-hours"],
                  "category":"clinic_hours",
                  "expectedBehavior":"answer_from_knowledge",
                  "expectedAnswerPhrases":["Monday"]
                }]
                """);

        List<RagEvaluationCase> loaded = loader.load(dataset);

        assertEquals(1, loaded.size());
        assertEquals(ExpectedBehavior.ANSWER_FROM_KNOWLEDGE, loaded.getFirst().expectedBehavior());
        assertEquals(List.of("Monday"), loaded.getFirst().expectedAnswerPhrases());
        assertEquals(List.of("01-clinic-hours"), loaded.getFirst().expectedDocumentIds());
        assertEquals(List.of(), loaded.getFirst().forbiddenAnswerPatterns());
    }

    @Test
    void rejectsDuplicateCaseIds() throws IOException {
        Path dataset = tempDir.resolve("duplicate.json");
        String one = "{\"id\":\"same\",\"question\":\"q\",\"expectedSourceDocuments\":[\"x\"],\"expectedDocumentIds\":[\"x\"],\"category\":\"x\",\"expectedBehavior\":\"answer_from_knowledge\",\"expectedAnswerPhrases\":[\"x\"]}";
        Files.writeString(dataset, "[" + one + "," + one + "]");

        assertThrows(IllegalStateException.class, () -> loader.load(dataset));
    }

    @Test
    void committedDatasetHasTwentyFiveCasesAndOnlyExistingKnowledgeDocumentNames() throws IOException {
        Path projectRoot = Path.of("..").toAbsolutePath().normalize();
        Path datasetPath = projectRoot.resolve("docs/evaluation/rag-evaluation.json");
        List<RagEvaluationCase> cases = loader.load(datasetPath);
        Set<String> knownDocumentNames = new HashSet<>();
        try (var documents = Files.list(projectRoot.resolve("docs/knowledge-base"))) {
            for (Path document : documents.filter(path -> path.toString().endsWith(".md")).toList()) {
                String heading = Files.readAllLines(document).stream()
                        .filter(line -> line.startsWith("# ")).findFirst().orElseThrow().substring(2);
                knownDocumentNames.add(heading);
            }
        }

        assertEquals(25, cases.size());
        assertEquals(20, cases.stream().filter(testCase ->
                testCase.expectedBehavior() == ExpectedBehavior.ANSWER_FROM_KNOWLEDGE).count());
        assertEquals(5, cases.stream().filter(testCase ->
                testCase.expectedBehavior() == ExpectedBehavior.ACKNOWLEDGE_MISSING_INFORMATION).count());
        assertTrue(cases.stream().flatMap(testCase -> testCase.expectedSourceDocuments().stream())
                .allMatch(knownDocumentNames::contains));
        Set<String> knownDocumentIds = new HashSet<>();
        try (var documents = Files.list(projectRoot.resolve("docs/knowledge-base"))) {
            documents.filter(path -> path.toString().endsWith(".md"))
                    .map(path -> path.getFileName().toString().replaceFirst("[.]md$", ""))
                    .forEach(knownDocumentIds::add);
        }
        assertEquals(10, cases.stream().flatMap(testCase -> testCase.expectedDocumentIds().stream())
                .distinct().count());
        assertTrue(cases.stream().flatMap(testCase -> testCase.expectedDocumentIds().stream())
                .allMatch(knownDocumentIds::contains));
        Set<String> categories = cases.stream().map(RagEvaluationCase::category).collect(Collectors.toSet());
        assertTrue(categories.containsAll(Set.of("clinic_hours", "services", "insurance", "pricing",
                "cancellation_rescheduling", "new_patients", "pediatric_dentistry", "dental_emergencies",
                "payments", "contact_location", "unsupported")));
    }
}
