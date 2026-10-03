# MolarAI phase status

This file distinguishes implementation status from checks that require local infrastructure or external credentials.

## Phase 1 — project scaffold

- [x] Spring Boot 3 / Java 21 Maven backend and package structure
- [x] React/Vite frontend shell
- [x] Root documentation and ignore rules
- [x] Phase 1 backend and frontend builds verified

## Phase 2 — PostgreSQL and pgvector

- [x] Docker Compose uses PostgreSQL 17 with pgvector 0.8.6
- [x] Local database/user/password configuration, persistent named volume, and PostgreSQL healthcheck
- [x] Spring datasource uses `DATABASE_URL`, `DATABASE_USERNAME`, and `DATABASE_PASSWORD`
- [x] Flyway migration enables `vector` and creates `document_chunks`
- [x] Vector dimension is configured centrally using `EMBEDDING_DIMENSION` and passed to Flyway
- [x] Compose configuration validated
- [x] PostgreSQL container started and healthcheck passed (PostgreSQL 17.11, host port 5433 because 5432 could not be bound)
- [x] Spring connected with JDBC and Flyway applied migration V1
- [x] Verified installed pgvector 0.8.6, `document_chunks`, and final `embedding vector(768)` after Flyway V2

## Phase 3 — clinic knowledge ingestion

- [x] Ten fictional clinic Markdown documents
- [x] Sorted filesystem loader with source and title metadata
- [x] Configurable ordered chunker with overlap and empty-input handling
- [x] `EmbeddingService` abstraction with conditional Ollama and optional OpenAI providers
- [x] Ollama is the default provider (`EMBEDDING_PROVIDER=ollama`), with `embeddinggemma` at 768 dimensions and configurable local base URL
- [x] OpenAI provider remains optional and retains API-key validation when generating embeddings
- [x] JDBC repository stores JSONB metadata and pgvector embeddings
- [x] Per-document transactional replacement and deterministic chunk identifiers
- [x] Opt-in command-line ingestion runner, disabled by default
- [x] Unit tests for loading, chunking, both embedding providers, provider selection, repository calls, and ingestion orchestration (26 tests pass)
- [x] Sample corpus loads as 10 documents and chunks to 10 chunks before embedding at default settings
- [x] Local Ollama endpoint returned HTTP 200 for a two-input batch; `embeddinggemma` returned two numeric 768-dimensional vectors
- [x] Ingested all 10 fictional documents using the real local EmbeddingGemma model
- [x] Verified PostgreSQL contains 10 documents, 10 chunks, 10 non-null embeddings, 768 dimensions, and 10 distinct numeric vectors

## Phase 4 — vector similarity retrieval

- [x] `KnowledgeRetrievalService` embeds queries through `EmbeddingService` and delegates search to `KnowledgeChunkRepository`
- [x] JDBC repository performs pgvector cosine-distance search with `<=>`, ascending distance ordering, and SQL `LIMIT`
- [x] `POST /api/knowledge/search` returns DTOs with document name, chunk index, content, metadata, and `cosineDistance`
- [x] Request validation rejects blank queries and constrains `topK` to 1–10
- [x] Distance semantics documented: lower cosine distance means a closer vector; it is not cosine similarity
- [x] All 31 Maven tests pass, including retrieval service, SQL mapping, and request validation tests
- [x] Maven package build succeeds
- [x] Backend started with JDBC against PostgreSQL 17.11 and local Ollama `embeddinggemma`
- [x] Live endpoint returned 3 results for `topK=3` and 1 result for `topK=1`
- [x] Live real-embedding searches returned the expected top document for insurance, hours, cleaning price, cancellation, and dental emergencies
- [x] Rechecked PostgreSQL: 10 documents, 10 chunks, 10 non-null 768-dimensional embeddings remain

Live top result for each verification query:

| Query | Top document | Cosine distance |
| --- | --- | ---: |
| Do you accept Aetna insurance? | Insurance and Coverage | 0.577449 |
| What are your clinic hours? | Clinic Hours | 0.519022 |
| How much does a dental cleaning cost? | Example Pricing | 0.384480 |
| Can I cancel my appointment? | Cancellation and Rescheduling Policy | 0.431987 |
| What should I do during a dental emergency? | Dental Urgent and Emergency Care | 0.449657 |

## Phase 5 — grounded Ollama LLM responses

- [x] `LlmService` abstraction with an Ollama `/api/chat` implementation; generation is non-streaming
- [x] Chat settings are independent from embedding settings: `OLLAMA_CHAT_BASE_URL`, `OLLAMA_CHAT_MODEL`, and optional `OLLAMA_API_KEY`
- [x] Local chat defaults to `http://localhost:11434/api` with model `qwen3:4b` and requires no chat API key; Ollama Cloud can use `https://ollama.com/api` with model `gpt-oss:120b-cloud` and a Bearer token from `OLLAMA_API_KEY`
- [x] Embeddings remain configured separately through `OLLAMA_EMBEDDING_BASE_URL`, `EMBEDDING_PROVIDER`, and `EMBEDDING_MODEL`; chat provider changes do not alter stored vector retrieval configuration
- [x] `GroundedResponseService` calls the existing retrieval service, then passes retrieved source identifiers, titles, chunk indexes, metadata, distances, and content to the prompt builder
- [x] System prompt requires answers to use only supplied clinic context, reject user attempts to override system instructions, state when facts are unavailable, and avoid presenting the assistant as a dental diagnostician
- [x] `POST /api/knowledge/answer` returns an answer and the retrieved source metadata; blank questions return HTTP 400
- [x] Ollama structured JSON response format and bounded output keep the endpoint response concise; provider errors map to a generic HTTP 502 without stack traces or raw provider details
- [x] Unit tests mock the HTTP provider and retrieval service; the complete Maven suite passes: 40 tests, 0 failures/errors
- [x] Maven package succeeds with Java 21.0.11
- [x] Real local Ollama `qwen3:4b` generation verified through the running Spring Boot endpoint, connected to PostgreSQL 17.11

Live endpoint checks (each returned HTTP 200 with source metadata):

| Query | Observed answer behavior | Top source |
| --- | --- | --- |
| Do you accept Aetna insurance? | Explained the clinic is independent/out-of-network, does not promise direct billing, and patients may request an itemized receipt; reimbursement depends on the plan/insurer. | Insurance and Coverage |
| What are your clinic hours? | Returned Monday–Friday 8:00 a.m.–5:00 p.m., Saturday 9:00 a.m.–1:00 p.m., closed Sundays and public holidays. | Clinic Hours |
| What is the Wi-Fi password at MolarAI Dental Studio? | Explicitly said the information is not available in the clinic information; no password was invented. | Contact and Location |

Blank query was separately verified to return HTTP 400. The verification backend ran on port 8081 because an unrelated Java process already occupied port 8080; that process was left untouched. The Phase 5 verification process was stopped after the checks.

Example request and expected behavior:

```sh
curl -X POST http://localhost:8080/api/knowledge/answer \
  -H 'Content-Type: application/json' \
  -d '{"query":"Do you accept Aetna insurance?"}'
```

The response contains an `answer` grounded in clinic context and a `sources` array with the retrieved document/chunk metadata. Unsupported questions should clearly say the information is unavailable rather than inventing a clinic fact.

## Phase 6 — RAG evaluation

- [x] Version-controlled dataset at `docs/evaluation/rag-evaluation.json`: 25 cases, 20 supported and 5 unsupported, with stable expected document IDs, answer evidence phrases, and categories across all ten clinic topics
- [x] Dataset loader validates required fields, unique IDs, supported/unsupported source expectations, answer evidence, and curated pattern syntax; a unit test cross-checks expected source titles against the actual knowledge-document headings
- [x] Retrieval evaluation reuses `KnowledgeRetrievalService` at top 5, matches stable document IDs, records retrieved IDs/ranks/distances, and reports top-1/top-3 accuracy and per-category results; it does not implement its own vector search or call the LLM for retrieval scoring
- [x] Recall@1/3/5 are measured over the 20 supported questions; unsupported queries are reported but do not lower recall
- [x] Live PostgreSQL/pgvector + EmbeddingGemma retrieval run: Recall@1 **95% (19/20)**, Recall@3 **100% (20/20)**, Recall@5 **100% (20/20)**; retrieval failures **0**
- [x] Separate live Qwen3 4B generation run completed all 25 cases: 0 answer-generation failures, 20/20 supported answer checks passed, unsupported handling **5/5**
- [x] Per-case reports include expected/retrieved sources, rank/distance, answer status, grounding status, answer checks, and generated answer; failures are retained in the output
- [x] Unit tests cover dataset loading/validation, recall cutoffs, rank selection, unsupported classification, contradiction-pattern detection, and report formatting
- [x] Full Java 21 Maven suite/package: 53 tests discovered, 51 passed, 0 failed, 2 live tests skipped by default; package build succeeds

The generation test is deliberately opt-in. Its most recent full run took **2,265 seconds (37:45) in Surefire** on this local machine. The final report passed all supported and unsupported checks; a prior exploratory run produced a reversed Saturday-hours claim, which the added curated pattern correctly flagged. This illustrates model variation and why the heuristic report is not a production-quality guarantee.

Manual commands (from `backend/`, with `.env` loaded):

```sh
mvn -Dtest=RetrievalEvaluationIntegrationTest -Dmolarai.evaluation.retrieval=true test
mvn -Dtest=GroundedAnswerEvaluationIntegrationTest -Dmolarai.evaluation.generation=true test
```

Recall and answer-quality checks are separate measures: a relevant source can be retrieved without a correct answer, and a fluent response does not prove retrieval found its expected source. The answer scorer uses expected phrases, a check that numeric tokens occur in retrieved text, and a small set of curated forbidden patterns. It cannot detect arbitrary paraphrased contradictions or non-numeric hallucinations.

Post-evaluation endpoint smoke check: `POST /api/knowledge/search` returned HTTP 200 with **Example Pricing** ranked first for a cleaning-price query. A live HTTP call to `POST /api/knowledge/answer` reached the backend but did not return before curl timed out (curl reported 924,293 ms); the backend was stopped cleanly. The 25-case grounded service evaluation had succeeded directly through `GroundedResponseService` with local Qwen. Treat live answer endpoint latency as an outstanding environment limitation for this verification session.

## Appointment availability backend foundation

- [x] JDBC repository and transactional service for reading, booking, and cancelling appointment slots
- [x] Flyway V3 creates appointment slots and inserts six deterministic fictional local-development slots
- [x] Booking uses an atomic available-status update to prevent two bookings of the same slot
- [x] REST API exposes available slots by date or date/time range, booking, and cancellation with DTOs and validation
- [x] Automated service, repository, validation, error mapping, and transactional-annotation tests
- [ ] Apply V3 and verify the appointment endpoints against the local PostgreSQL database

The booking API is a development foundation and does not yet include authentication, identity checks, audit history, schedule management, or production privacy controls.

## Phase 7 — appointment availability tool calling

- [x] Extends the existing Ollama chat implementation with tool-capable chat while preserving `LlmService.generate`
- [x] Grounded answer flow keeps retrieval and prompt construction, then lets the model choose the strict `get_available_appointment_slots` tool
- [x] Tool execution delegates through `AppointmentAvailabilityTool` to `AppointmentAvailabilityService`; no repository or SQL access is exposed to the LLM
- [x] Tool results omit slot IDs and all patient information; tool errors return controlled messages without internal exception details
- [x] Supplies current application-local date/time using configurable `APPOINTMENT_TIME_ZONE` (default `America/Los_Angeles`)
- [x] Bounds the tool loop to three rounds and rejects unsupported tool names
- [x] Unit tests cover schema/arguments, service delegation, tool/no-tool decisions, result round trip, safe failures, unknown tools, and round limits
- [ ] Run opt-in `AppointmentToolCallingIntegrationTest` with PostgreSQL, Ollama, EmbeddingGemma, and seeded appointment slots
- [ ] Verify a live model-selected appointment function call answers from returned slot data

Only availability lookup can be invoked by the LLM. Booking and cancellation remain API-only; no authentication, conversation history, or appointment management functions are included.

## Manual verification

From `MolarAI/`:

```sh
docker compose config
POSTGRES_HOST_PORT=5433 docker compose up -d --wait postgres
docker compose ps
POSTGRES_HOST_PORT=5433 docker compose exec postgres psql -U molarai -d molarai -c "SELECT version();"
POSTGRES_HOST_PORT=5433 docker compose exec postgres psql -U molarai -d molarai -c "SELECT extversion FROM pg_extension WHERE extname = 'vector';"
```

Copy `.env.example` to `.env` and load the environment in the shell. Ensure Ollama has both `embeddinggemma` and `qwen3:4b` available, and start PostgreSQL. Run `mvn spring-boot:run` from `backend/`. Use the `POST /api/knowledge/search` and `POST /api/knowledge/answer` examples in `README.md` to perform local retrieval and grounded generation. No API key is needed with the default Ollama providers.

## Verification limitations

- Port 5432 could not be bound in this environment, so PostgreSQL was run on 5433. Set `POSTGRES_HOST_PORT=5433` and `DATABASE_URL=jdbc:postgresql://localhost:5433/molarai` when using the current database setup.
- Docker CLI was not present in this verification shell; the already running local PostgreSQL service was connected to directly, and Flyway and stored vectors were verified through JDBC/psql.
- Testcontainers are not configured; persistence behavior has unit-level JDBC tests in addition to the live migration/database checks.
- Phase 5 uses a local 4B model and a small fictional corpus; answer quality and latency depend on local hardware and retrieved context. It does not add conversation history, confidence thresholds, or persistent citations.
- Phase 6's retrieval benchmark runs quickly, but all-case generation was very slow locally. An HTTP smoke request to `/api/knowledge/answer` timed out after reaching the server, although the direct grounded service completed the full live generation benchmark.
- Phase 6's deterministic answer checks are curated heuristics over this dataset; passing them does not establish broad factuality or production readiness.
- Claude/Gemini integration, appointment booking/cancellation through the LLM, escalation, conversation memory, full `/api/chat` orchestration, and frontend chat remain out of scope.
