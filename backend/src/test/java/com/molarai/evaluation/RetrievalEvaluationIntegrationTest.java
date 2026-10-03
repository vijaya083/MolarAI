package com.molarai.evaluation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
@EnabledIfSystemProperty(named = "molarai.evaluation.retrieval", matches = "true")
class RetrievalEvaluationIntegrationTest {
    @Autowired
    private EvaluationDatasetLoader datasetLoader;

    @Autowired
    private RagEvaluationService evaluationService;

    @Autowired
    private RagEvaluationReportFormatter formatter;

    @Test
    void evaluatesAllDatasetQuestionsAgainstLocalPgvectorAndEmbeddingGemma() {
        var dataset = datasetLoader.loadConfiguredDataset();
        RagEvaluationReport report = evaluationService.evaluateRetrieval(dataset);
        System.out.print(formatter.format(report));

        assertEquals(25, report.totalCases(), "Update the documented dataset size when cases are added or removed");
        assertEquals(20, report.supportedCases());
        assertEquals(5, report.unsupportedCases());
    }
}
