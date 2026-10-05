package com.molarai.config;

import com.molarai.ai.EmbeddingService;
import com.molarai.ai.DisabledEmbeddingService;
import com.molarai.ai.OllamaEmbeddingService;
import com.molarai.ai.OpenAiEmbeddingService;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmbeddingProviderSelectionTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(ProviderBeans.class);

    @Test
    void ollamaActivatesWhenSelected() {
        contextRunner.withPropertyValues("molarai.embedding.provider=ollama")
                .run(context -> {
                    assertTrue(context.getStartupFailure() == null);
                    assertEquals(1, context.getBeansOfType(EmbeddingService.class).size());
                    assertTrue(context.getBean(EmbeddingService.class) instanceof OllamaEmbeddingService);
                });
    }

    @Test
    void openAiActivatesWhenSelected() {
        contextRunner.withPropertyValues("molarai.embedding.provider=openai")
                .run(context -> {
                    assertTrue(context.getStartupFailure() == null);
                    assertEquals(1, context.getBeansOfType(EmbeddingService.class).size());
                    assertTrue(context.getBean(EmbeddingService.class) instanceof OpenAiEmbeddingService);
                });
    }

    @Test
    void disabledProviderActivatesWithoutRemoteEmbeddingConfiguration() {
        contextRunner.withPropertyValues("molarai.embedding.provider=disabled")
                .run(context -> {
                    assertTrue(context.getStartupFailure() == null);
                    assertEquals(1, context.getBeansOfType(EmbeddingService.class).size());
                    EmbeddingService service = context.getBean(EmbeddingService.class);
                    assertTrue(service instanceof DisabledEmbeddingService);
                    assertThrows(com.molarai.service.KnowledgeUnavailableException.class,
                            () -> service.embedAll(java.util.List.of("clinic hours")));
                });
    }

    @Test
    void unsupportedProviderFailsWithClearConfigurationError() {
        contextRunner.withPropertyValues("molarai.embedding.provider=remote-unknown")
                .run(context -> {
                    assertTrue(context.getStartupFailure() != null);
                    assertTrue(rootMessages(context.getStartupFailure()).contains("Unsupported EMBEDDING_PROVIDER"));
                });
    }

    private String rootMessages(Throwable failure) {
        StringBuilder messages = new StringBuilder();
        for (Throwable current = failure; current != null; current = current.getCause()) {
            messages.append(current.getMessage()).append('\n');
        }
        return messages.toString();
    }

    @Configuration(proxyBeanMethods = false)
    @Import({EmbeddingProviderConfiguration.class, OllamaEmbeddingService.class,
            OpenAiEmbeddingService.class, DisabledEmbeddingService.class})
    static class ProviderBeans {
        @Bean
        RestClient.Builder restClientBuilder() {
            return RestClient.builder();
        }
    }
}
