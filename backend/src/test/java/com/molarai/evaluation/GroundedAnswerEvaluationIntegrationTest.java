package com.molarai.evaluation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
@EnabledIfSystemProperty(named = "molarai.evaluation.generation", matches = "true")
class GroundedAnswerEvaluationIntegrationTest {
    @Autowired
    private EvaluationDatasetLoader datasetLoader;

    @Autowired
    private RagEvaluationService evaluationService;

    @Autowired
    private RagEvaluationReportFormatter formatter;

    @Test
    void evaluatesAllGroundedAnswersUsingLocalQwen() {
        var dataset = datasetLoader.loadConfiguredDataset();
        RagEvaluationReport report = evaluationService.evaluateGeneration(dataset);
        System.out.print(formatter.format(report));

        assertEquals(25, report.totalCases(), "Update the documented dataset size when cases are added or removed");
        assertEquals(5, report.unsupportedCases());
    }
}
