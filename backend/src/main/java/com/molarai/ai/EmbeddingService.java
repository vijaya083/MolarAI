package com.molarai.ai;

import java.util.List;

public interface EmbeddingService {
    List<List<Double>> embedAll(List<String> inputs);
}
