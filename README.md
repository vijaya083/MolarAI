# MolarAI

MolarAI is a portfolio project for a fictional dental clinic support agent. The backend includes a Spring Boot/JDBC scaffold, PostgreSQL with pgvector schema management, document-to-embedding ingestion, vector similarity retrieval, grounded LLM answers, RAG evaluation, appointment availability, and LLM-driven appointment lookup. The React/Vite frontend provides a responsive assistant chat experience.

## Project structure

- `backend/` — Java 21, Spring Boot 3.5, Spring JDBC, Flyway migrations, and ingestion code
- `frontend/` — React/Vite chat interface and backend API client
- `docs/knowledge-base/` — 10 fictional Markdown clinic documents
- `docs/` — project documentation

Deployment preparation for Vercel, Render, and managed PostgreSQL is documented in [`docs/DEPLOYMENT.md`](docs/DEPLOYMENT.md). It does not create cloud resources. Production startup is intentionally blocked until an actual OTP delivery provider is implemented; the current mock does not send SMS.

## Local requirements

- Java 21+
- Maven 3.9+
- Node.js 22.12+ and npm 10+ (needed for the frontend)
- Docker with the Compose plugin (needed for local PostgreSQL)
- Ollama with `embeddinggemma` for local query embeddings
- Ollama Cloud chat configuration (`OLLAMA_API_KEY`) for grounded chat and appointment tool calling
- An OpenAI API key only if explicitly selecting the optional OpenAI provider

## Start PostgreSQL and apply the schema

From the `MolarAI/` directory, copy `.env.example` to `.env`, then start PostgreSQL:

```sh
cp .env.example .env
docker compose config
docker compose up -d postgres
docker compose ps
```

The `pgvector/pgvector:0.8.6-pg17-bookworm` image supplies PostgreSQL 17 with pgvector 0.8.6. The named `molarai-postgres-data` volume persists database files. The container healthcheck uses `pg_isready`. The host port defaults to 5432 and can be changed with `POSTGRES_HOST_PORT` if that port is occupied; set `DATABASE_URL` to the matching host port as well. In this environment the database was started and verified on host port 5433 because port 5432 could not be bound.

Set `DATABASE_URL`, `DATABASE_USERNAME`, and `DATABASE_PASSWORD` in the shell before running the backend. Docker Compose reads `.env` automatically for its own substitutions; Spring Boot does not load `.env` files itself. For a local shell, load the file with `set -a; source .env; set +a` from the project root. Keep real credentials in the ignored `.env` file.

Start the backend from `backend/`:

```sh
SPRING_PROFILES_ACTIVE=dev mvn spring-boot:run
```

Flyway runs the migrations in `backend/src/main/resources/db/migration/` at application startup. V1 enables pgvector and creates `document_chunks`; V2 converts the embedding column to `vector(768)`. The dimension is configured centrally with `EMBEDDING_DIMENSION` and defaults to 768. V1 is retained as the original schema migration so existing databases do not get a Flyway checksum mismatch; V2 applies the dimension change to both existing and fresh databases. If you intentionally choose another model dimension later, add a new migration and re-embed the corpus.

## Knowledge documents and ingestion

The 10 sample documents in `docs/knowledge-base/` cover hours, services, insurance, example pricing, cancellations, new patients, pediatric dentistry, urgent care, payments, and contact/location. All clinic details are explicitly fictional.

The loader reads sorted Markdown files and records a stable identifier from each filename, the document title, source filename, and format. The chunker uses configurable character windows (`CHUNK_SIZE`, default 1200) and overlap (`CHUNK_OVERLAP`, default 150), preferring whitespace boundaries. Chunk order is preserved and blank chunks are omitted. Embeddings go through the `EmbeddingService` abstraction. By default, `OllamaEmbeddingService` sends batches to `POST http://localhost:11434/api/embed` using `EMBEDDING_MODEL=embeddinggemma`; it does not require an external API key. Set `OLLAMA_EMBEDDING_BASE_URL` to change the embedding server URL. Chat has a separate endpoint, model, and optional API key configured through `OLLAMA_CHAT_BASE_URL`, `OLLAMA_CHAT_MODEL`, and `OLLAMA_API_KEY`; changing chat settings does not alter embedding configuration. The configured vector width defaults to `EMBEDDING_DIMENSION=768`, and provider responses are checked for batch count, configured model, finite numeric values, and exact width before persistence. `OpenAiEmbeddingService` remains available by setting `EMBEDDING_PROVIDER=openai` and providing an API key. When using OpenAI, set `EMBEDDING_MODEL` and `EMBEDDING_DIMENSION` to values compatible with that model.

To run ingestion, make sure PostgreSQL and Ollama are running, and that `embeddinggemma` is available locally. Copy `.env.example` to `.env` if you have not already, then from `backend/`:

```sh
set -a; source ../.env; set +a
INGEST_KNOWLEDGE_BASE=true mvn spring-boot:run
```

Ingestion is disabled on ordinary startup. The opt-in runner embeds and persists each document's chunks through `JdbcTemplate`. Each document replacement is transactional and repeated ingestion replaces prior chunks rather than accumulating duplicates. Provider failures stop ingestion and are not silently ignored. No fake embeddings are generated.

## Vector retrieval (Phase 4)

`KnowledgeRetrievalService` embeds each natural-language query through the existing `EmbeddingService` abstraction, then passes that vector and the requested `topK` to `KnowledgeChunkRepository`. The JDBC repository performs the search in PostgreSQL with pgvector's cosine-distance operator (`<=>`), orders from smallest distance to largest, and applies `LIMIT` in SQL. Embeddings are not loaded into Java for local distance calculations. The API returns the actual cosine distance as `cosineDistance`; **lower values indicate closer vectors**, and the value is not a cosine-similarity score.

The retrieval-only endpoint is `POST /api/knowledge/search`. `query` must not be blank, and `topK` must be from 1 through 10.

Request:

```json
{
  "query": "Do you accept Aetna insurance?",
  "topK": 3
}
```

Example response shape:

```json
{
  "query": "Do you accept Aetna insurance?",
  "results": [
    {
      "documentName": "03-insurance.md",
      "chunkIndex": 0,
      "content": "...",
      "metadata": { "sourceFilename": "03-insurance.md" },
      "cosineDistance": 0.12
    }
  ]
}
```

With PostgreSQL and Ollama running, start the backend from `backend/`, then call:

```sh
curl -X POST http://localhost:8080/api/knowledge/search \
  -H 'Content-Type: application/json' \
  -d '{"query":"Do you accept Aetna insurance?","topK":3}'
```

The query embedding uses the configured embedding provider/model just like ingestion. The search endpoint returns retrieved chunks without generating an answer.

## Grounded answers (Phase 5)

The development endpoint `POST /api/knowledge/answer` accepts a non-blank question, retrieves the three closest knowledge chunks through `KnowledgeRetrievalService`, and gives their document identifiers, names, chunk indexes, metadata, cosine distances, and content to the configured Ollama chat model. `GroundedPromptBuilder` supplies a system prompt that limits factual claims to the clinic context, treats user text as untrusted input, and requires an explicit unavailable response when context is insufficient. `LlmService` keeps generation behind an interface; chat calls Ollama's non-streaming `/api/chat` endpoint, with either a local Ollama server or Ollama Cloud. The chat API key is optional and is sent only as a Bearer authorization header when configured. The embedding provider and local embedding endpoint remain independent of chat configuration.

Local chat defaults are `OLLAMA_CHAT_BASE_URL=http://localhost:11434/api` and `OLLAMA_CHAT_MODEL=qwen3:4b`, with no `OLLAMA_API_KEY`. For Ollama Cloud, configure `OLLAMA_CHAT_BASE_URL=https://ollama.com/api`, `OLLAMA_CHAT_MODEL=gpt-oss:120b-cloud`, and `OLLAMA_API_KEY` in an untracked local environment file. Keep embeddings local and unchanged with `OLLAMA_EMBEDDING_BASE_URL=http://localhost:11434`, `EMBEDDING_PROVIDER=ollama`, and `EMBEDDING_MODEL=embeddinggemma`. Query retrieval still uses this configured embedding provider; only chat generation is switched to the cloud. Chat generation uses `OLLAMA_CHAT_MAX_OUTPUT_TOKENS=512` through Ollama's `options.num_predict`. A response stopped at the output limit is discarded and fails safely; the application does not automatically retry it. The legacy `OLLAMA_BASE_URL` variable remains a fallback for local chat host configuration.

## Frontend chat

The frontend is a React 19 + Vite app. It sends questions through a small API client to `POST /api/knowledge/answer`, renders the returned answer and source document names, and displays appointment availability only when the backend returns it. During local development, Vite proxies `/api` to `VITE_API_BASE_URL`, so browser requests use the frontend origin and do not require broad backend CORS rules.

Start the backend first as described above, with PostgreSQL and backend environment configured. In another terminal:

```sh
cd frontend
cp .env.example .env
npm run dev
```

Open the local URL printed by Vite (normally `http://localhost:5173`). `VITE_API_BASE_URL` defaults to `http://localhost:8080`; set it in `frontend/.env` to change the backend origin. The same variable is used as the direct backend origin in production builds. Install frontend dependencies with `npm install` if `node_modules/` is not present. Build with `npm run build` from `frontend/`.

Request:

```json
{
  "query": "Do you accept Aetna insurance?"
}
```

Example response shape (answer wording varies with local generation):

```json
{
  "answer": "MolarAI Dental Studio is out of network and does not promise direct billing. You can request an itemized receipt to submit to your plan.",
  "sources": [
    {
      "documentId": "03-insurance",
      "documentName": "Insurance and Coverage",
      "chunkIndex": 0,
      "metadata": { "sourceFilename": "03-insurance.md" },
      "cosineDistance": 0.12
    }
  ]
}
```

Try a real local response after starting the backend:

```sh
curl -X POST http://localhost:8080/api/knowledge/answer \
  -H 'Content-Type: application/json' \
  -d '{"query":"Do you accept Aetna insurance?"}'
```

Blank questions return HTTP 400. Ollama/model failures return a generic HTTP 502 response without exposing provider response bodies or stack traces. The endpoint remains a stateless grounded answer path; it does not implement conversation history, booking/cancellation through the LLM, escalation, or streaming.

The normal answer path sends the RAG context and a strict appointment-availability function schema to Ollama's `/api/chat`. Ollama decides whether the question needs live availability. If it calls the function, MolarAI executes it through the appointment service, returns the safe result to Ollama, and requests a final answer. Other questions can be answered from RAG without executing the appointment tool. This does not guarantee factual correctness; verify important clinic details against the returned `sources`.

## RAG evaluation (Phase 6)

The version-controlled benchmark is [`docs/evaluation/rag-evaluation.json`](docs/evaluation/rag-evaluation.json). It contains 25 cases: 20 answerable questions across all ten clinic documents and 5 intentionally unsupported questions. Every supported case names its expected stable `documentId`, display title, category, and answer evidence phrases; unsupported cases have no expected document IDs and can list patterns for obvious fabricated claims.

Retrieval evaluation sends every question through the existing `KnowledgeRetrievalService` and reports the top five sources. Top-1/top-3 accuracy (also Recall@1/3) is calculated over the 20 supported questions; unsupported questions are reported but do not lower supported retrieval accuracy. The evaluator records retrieved document IDs, titles, chunk indexes, and cosine distances, and includes per-category accuracy and failed cases. Retrieval-only evaluation does not call the grounded answer or LLM service. The previously documented live baseline measured Recall@1 **95%** (19/20), Recall@3 **100%** (20/20), and Recall@5 **100%** (20/20), with 0 retrieval failures.

Run the quick retrieval benchmark against local PostgreSQL/pgvector and Ollama EmbeddingGemma from `backend/`:

```sh
set -a; source ../.env; set +a
mvn -Dtest=RetrievalEvaluationIntegrationTest -Dmolarai.evaluation.retrieval=true test
```

The separate live generation benchmark runs all 25 questions through `GroundedResponseService` and local `qwen3:4b`:

```sh
set -a; source ../.env; set +a
mvn -Dtest=GroundedAnswerEvaluationIntegrationTest -Dmolarai.evaluation.generation=true test
```

This generation run is opt-in and slow on the current machine; its last Surefire-reported duration was 2,265 seconds. It generated 25 non-empty answers with 0 generation failures. The deterministic answer checks passed for 20/20 supported responses and 5/5 unsupported responses. The unsupported answers acknowledged the missing information and did not match the dataset's curated claim patterns. Those checks also require an expected document in the answer's sources, one expected evidence phrase for supported cases, and numeric values to appear in retrieved context. Curated forbidden patterns catch known contradictions or unsupported claims.

The post-evaluation HTTP smoke test returned HTTP 200 from `/api/knowledge/search`. The `/api/knowledge/answer` request reached the local backend but curl timed out before receiving a response (curl reported 924,293 ms); the same grounded response service completed all 25 cases directly in the live generation evaluation. The HTTP response timeout is recorded as a local latency limitation, not counted as an answer-generation success.

## Appointment availability backend foundation

Flyway migration V3 creates `appointment_slots` and seeds six deterministic fictional slots on June 10–12, 2030. Slots include date, start/end time, status, and optional provider. Booking stores a patient name/contact on the slot; API responses omit those fields. Booking and cancellation run transactionally. Booking uses one conditional `UPDATE ... WHERE status = 'AVAILABLE' RETURNING ...`, so concurrent attempts cannot both claim the same slot. Cancellation makes a booked slot available again.

Available slots can be queried by date:

```sh
curl 'http://localhost:8080/api/appointments/slots?date=2030-06-10'
```

Or by a half-open date/time range (`from` included, `to` excluded):

```sh
curl 'http://localhost:8080/api/appointments/slots?from=2030-06-10T08:00:00&to=2030-06-11T00:00:00'
```

Book a returned slot ID:

```sh
curl -X POST http://localhost:8080/api/appointments/book \
  -H 'Content-Type: application/json' \
  -d '{"slotId":"6d9802e8-6fce-4e1c-9d47-5c6b1f8d1001","patientName":"Sam Patient","patientContact":"555-0100"}'
```

The default OTP provider is a local mock and does not send SMS. To retrieve OTPs during local testing, start the backend with `SPRING_PROFILES_ACTIVE=dev CANCELLATION_OTP_EXPOSE_CODE=true`. Exposure requires both the explicit setting and a `dev` or `development` profile. The setting defaults to `false`, the controller omits it from production responses, and the `prod` or `production` profile always suppresses code exposure. Production startup rejects the mock provider.

Start a cancellation request using the booked slot ID and matching patient details:

```sh
curl -X POST http://localhost:8080/api/appointments/cancellation-requests \
  -H 'Content-Type: application/json' \
  -d '{"appointmentSlotId":"6d9802e8-6fce-4e1c-9d47-5c6b1f8d1001","patientName":"Sam Patient","patientContact":"555-0100"}'
```

With local exposure enabled, the JSON response includes `developmentOtp`. Submit that value with the response's `requestId` to verify:

```sh
curl -X POST http://localhost:8080/api/appointments/cancellation-requests/<requestId>/verify \
  -H 'Content-Type: application/json' \
  -d '{"otp":"<developmentOtp>"}'
```

After successful verification, cancel using the same request ID:

```sh
curl -X DELETE http://localhost:8080/api/appointments/cancellation-requests/<requestId>
```

The direct `DELETE /api/appointments/slots/{slotId}/booking` endpoint is disabled when OTP cancellation is active. Invalid query/body values return HTTP 400, missing slots return 404, and slots that have already been booked or cancelled return 409. The appointment backend remains a foundation: it has no authentication, patient identity verification, audit history, schedule administration, reminders, or production privacy controls.

**Public demo limitation:** cancellation matching, OTP request/resend/verify, and cancellation routes are unauthenticated. For a public demo, set backend `CANCELLATION_ENABLED=false` to block every cancellation route with HTTP 403 and set frontend `VITE_CANCELLATION_ENABLED=false` to hide the cancellation option. The server guard is authoritative; the OTP check is not user authentication, and CORS is not access control. Keep the demo database isolated and use synthetic data only. Public booking also accepts patient name/contact without authentication, so do not collect real patient information.

### LLM appointment availability tool

`GroundedResponseService` retains its existing retrieval and prompt-building steps, then uses the bounded tool-call orchestrator and existing Ollama chat provider. The model receives the current application-local date/time (`APPOINTMENT_TIME_ZONE`, default `America/Los_Angeles`) so it can resolve relative dates such as “tomorrow” without relying on the host machine timezone. Ambiguous dates should be clarified rather than guessed.

The single supported function is `get_available_appointment_slots`:

```json
{
  "date": "2030-06-10",
  "from": "2030-06-10T09:00:00",
  "to": "2030-06-10T12:00:00"
}
```

`date` is required; `from` and `to` are optional but must be supplied together and refer to that date. Without a range, the tool searches the full date. It delegates to `AppointmentAvailabilityService`; tool results contain only date, times, and provider. Patient details, slot IDs, SQL, and database implementation details are excluded. At most three tool rounds are executed. Unknown tool names fail safely, and tool errors are summarized without internal exception details.

RAG handles clinic facts such as hours, insurance, and services using the knowledge base. The appointment function is reserved for current slot availability. The LLM cannot book or cancel appointments; those remain API-only operations.

To run the opt-in local integration test after V3 is applied and PostgreSQL, Ollama, `embeddinggemma`, and `qwen3:4b` are available, run from `backend/`:

```sh
set -a; source ../.env; set +a
mvn -Dtest=AppointmentToolCallingIntegrationTest -Dmolarai.tools.live=true test
```

The test asks the live model about the seeded June 10, 2030 slots and checks that its answer uses a seeded provider/time. It is disabled during the normal Maven suite and requires no cloud API key.

Recall and answer checks measure different things: a relevant document can be retrieved without the generated answer being accurate, and a plausible answer can appear even when retrieval missed its expected source. Answer scoring is deliberately heuristic. The phrase lists and forbidden patterns cover this small benchmark only; they are not a general contradiction detector or production quality evaluation. Model wording can vary across runs, so inspect every printed per-case result and answer.

To verify stored data with `psql`:

```sql
SELECT extversion FROM pg_extension WHERE extname = 'vector';
SELECT to_regclass('public.document_chunks');
SELECT count(DISTINCT document_id) AS documents,
       count(*) AS chunks,
       count(*) FILTER (WHERE embedding IS NOT NULL) AS embedded_chunks
FROM document_chunks;
```

## Development checks

```sh
cd backend && mvn test package
cd ../frontend && npm install && npm run build
```

Frontend chat, full `/api/chat` orchestration, Claude/Gemini, appointment booking/cancellation through the LLM, agent tools, and conversation memory remain out of scope. Appointment availability tool calling and the HTTP API are foundations only.

## Current limitations

- The grounded endpoint returns retrieved source records, but there is no conversation history, persistent citation tracking, or general chat orchestration.
- The sample database has only 10 chunks and no approximate-nearest-neighbor index; PostgreSQL computes and sorts cosine distances directly, which is suitable for this small corpus but should be revisited as it grows.
- The sample corpus currently yields 10 chunks before embedding at the default chunk settings.
- Ollama must be running locally with `embeddinggemma` and `qwen3:4b`. If either is unavailable, embedding/answer requests fail cleanly.
- Grounding depends on retrieval quality and corpus coverage. The small local model can still omit or misstate details; answers are informational and are not dental diagnoses.
- Phase 6's 25-question benchmark is a small regression set. Its numeric checks and curated text patterns can miss paraphrased or non-numeric hallucinations and should not be treated as production-level evaluation.
- Appointment tool calling is local to the grounded answer request, supports availability only, and has a three-round limit. It does not provide identity or access controls for clinical scheduling.
- Tests use unit-level repository mocks rather than Testcontainers because Docker is not available in the development environment.
