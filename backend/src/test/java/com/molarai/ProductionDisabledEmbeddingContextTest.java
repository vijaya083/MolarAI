package com.molarai;

import com.molarai.ai.DisabledEmbeddingService;
import com.molarai.ai.EmbeddingService;
import com.molarai.ai.OllamaEmbeddingService;
import com.molarai.ai.OpenAiEmbeddingService;
import com.molarai.config.ProductionDeploymentGuard;
import com.molarai.rag.KnowledgeBaseIngestionRunner;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "EMBEDDING_PROVIDER=disabled",
        "EMBEDDING_API_KEY=",
        "EMBEDDING_DIMENSION=768",
        "OLLAMA_EMBEDDING_BASE_URL=",
        "EMBEDDING_MODEL=embeddinggemma",
        "CANCELLATION_ENABLED=false",
        "CANCELLATION_OTP_PROVIDER=mock",
        "OLLAMA_API_KEY=test-only-chat-key",
        "OLLAMA_CHAT_BASE_URL=http://127.0.0.1:1/api",
        "OLLAMA_CHAT_MODEL=test-chat-model",
        "DATABASE_URL=postgresql://render-user:render-url-password@db.example.test:5432/molarai?sslmode=require",
        "DATABASE_USERNAME=test-user",
        "DATABASE_PASSWORD=test-password",
        "APPOINTMENT_TIME_ZONE=America/Los_Angeles",
        "CLINIC_WEEKDAY_OPEN=09:00",
        "CLINIC_WEEKDAY_CLOSE=17:00",
        "CLINIC_SATURDAY_OPEN=09:00",
        "CLINIC_SATURDAY_CLOSE=13:00",
        "CLINIC_SLOT_MINUTES=30",
        "CLINIC_DAILY_SLOT_STARTS=09:00,09:30,10:00",
        "CLINIC_BOOKING_HORIZON_DAYS=90",
        "CLINIC_MINIMUM_ADVANCE_MINUTES=60",
        "CLINIC_PROVIDERS=Test Provider",
        "CORS_ALLOWED_ORIGINS=https://demo.example.test",
        "INGEST_KNOWLEDGE_BASE=true",
        "spring.flyway.enabled=false"
})
@ActiveProfiles("prod")
class ProductionDisabledEmbeddingContextTest {
    @Autowired
    private ApplicationContext context;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private HikariDataSource dataSource;

    @Test
    void productionContextStartsWithDisabledEmbeddingsAndNoEmbeddingCredentials() {
        assertThat(context.getEnvironment().getActiveProfiles()).contains("prod");
        assertThat(context.getBean(ProductionDeploymentGuard.class)).isNotNull();
        assertThat(context.getBean(EmbeddingService.class)).isInstanceOf(DisabledEmbeddingService.class);
        assertThat(context.getBeansOfType(OllamaEmbeddingService.class)).isEmpty();
        assertThat(context.getBeansOfType(OpenAiEmbeddingService.class)).isEmpty();
        assertThat(context.getBeansOfType(KnowledgeBaseIngestionRunner.class)).hasSize(1);
        assertThat(jdbcTemplate).isNotNull();
        assertThat(dataSource.getJdbcUrl())
                .isEqualTo("jdbc:postgresql://db.example.test:5432/molarai?sslmode=require")
                .doesNotContain("render-user", "render-url-password");
        assertThat(dataSource.getUsername()).isEqualTo("test-user");
        assertThat(dataSource.getPassword()).isEqualTo("test-password");

        assertThat(context.getEnvironment().getProperty("molarai.ollama.embedding-base-url")).isBlank();
        assertThat(context.getEnvironment().getProperty("molarai.embedding.api-key")).isBlank();
        assertThat(context.getEnvironment().getProperty("molarai.embedding.dimension")).isEqualTo("768");
        assertThat(context.getEnvironment().getProperty("molarai.embedding.model")).isEqualTo("embeddinggemma");
    }
}
