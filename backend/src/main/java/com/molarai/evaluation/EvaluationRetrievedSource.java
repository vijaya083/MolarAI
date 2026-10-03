package com.molarai.evaluation;

public record EvaluationRetrievedSource(String documentId, String documentName, int chunkIndex, double cosineDistance) {
}
