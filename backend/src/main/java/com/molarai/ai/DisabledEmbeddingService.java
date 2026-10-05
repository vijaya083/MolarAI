package com.molarai.ai;

import com.molarai.service.KnowledgeUnavailableException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@ConditionalOnProperty(name = "molarai.embedding.provider", havingValue = "disabled")
public class DisabledEmbeddingService implements EmbeddingService {
    @Override
    public List<List<Double>> embedAll(List<String> inputs) {
        throw new KnowledgeUnavailableException();
    }
}
