# Deployment preparation

This repository is prepared for a static Vercel frontend, a Docker-based Render backend, and managed PostgreSQL with pgvector. No cloud resources are created by these instructions. Do not deploy the backend with the `prod` profile until a real OTP delivery provider is implemented and configured; startup deliberately rejects the current mock provider.

## Backend image

Build from the `MolarAI/` directory so Docker can use the root build context:

```sh
docker build -f backend/Dockerfile -t molarai-backend .
```

The multi-stage image builds with Java 21/Maven and runs the packaged Spring Boot application as a non-root user. `.dockerignore` excludes environment files, Git metadata, build output, and frontend dependencies. The Render service should use the repository root as its build context, `backend/Dockerfile` as its Dockerfile, and `/actuator/health/readiness` as its health check path.

## Managed PostgreSQL

Create/select a managed PostgreSQL database in the same region as the Render backend. Render Postgres supports pgvector; the existing Flyway V1 migration enables the `vector` extension. The database user must be allowed to run `CREATE EXTENSION vector`. Do not reset or recreate an existing database: Flyway validates and advances its schema in place, with `clean-disabled=true`.

Set `DATABASE_URL` to the Render PostgreSQL connection URL (`postgres://` or `postgresql://`) and set `DATABASE_USERNAME` and `DATABASE_PASSWORD` using the provider's separate credential values. The production datasource converts the Render URL to JDBC form and removes any URL-embedded user information before passing it to PostgreSQL JDBC; the separate username/password properties are used for authentication. Existing `jdbc:postgresql://` URLs remain supported. Use an internal connection URL when available. `DATABASE_POOL_SIZE` is optional and defaults to 10.

## Render backend environment

Set `SPRING_PROFILES_ACTIVE=prod`. Required variables are:

| Variable | Purpose |
| --- | --- |
| `DATABASE_URL` | Managed PostgreSQL `postgres://`, `postgresql://`, or JDBC URL |
| `DATABASE_USERNAME`, `DATABASE_PASSWORD` | Managed database credentials |
| `CORS_ALLOWED_ORIGINS` | Exact Vercel production origin(s), comma-separated; no wildcard |
| `CANCELLATION_ENABLED` | `false` for the public demo; server rejects all cancellation endpoints with HTTP 403 |
| `APPOINTMENT_TIME_ZONE`, `CLINIC_*` schedule variables | Real local timezone, opening hours, slot cadence, booking limits, and provider names |
| `EMBEDDING_PROVIDER` | `disabled` for the zero-additional-embedding-cost demo, or `openai` / `ollama` when configured |
| `EMBEDDING_MODEL`, `EMBEDDING_DIMENSION` | Must match the existing database vector width |
| `OLLAMA_CHAT_BASE_URL`, `OLLAMA_CHAT_MODEL`, `OLLAMA_API_KEY` | Existing hosted chat provider settings |
| `CANCELLATION_OTP_PROVIDER` | Must name a future real provider; `mock` is rejected in production |

For the temporary `EMBEDDING_PROVIDER=disabled` demo, no embedding URL or key is needed; knowledge search and RAG-backed chat return HTTP 503 with `KNOWLEDGE_UNAVAILABLE`, while deterministic appointment availability, date, and clinic-hours answers remain active. This mode does not generate embeddings, ingest documents, query pgvector, or modify existing knowledge records. Keep `EMBEDDING_DIMENSION=768`; do not change the existing schema or embeddings. For `EMBEDDING_PROVIDER=openai`, also set `EMBEDDING_API_KEY`; `EMBEDDING_API_BASE_URL` defaults to OpenAI's public API endpoint and usage may be billable. For `EMBEDDING_PROVIDER=ollama`, set `OLLAMA_EMBEDDING_BASE_URL` to a reachable Ollama API server. `PORT` is supplied by Render; the application listens on it. Never put secret values in this repository, Docker build arguments, or Vercel frontend variables.

Production Flyway migrations run at application startup and schema validation is enabled. The readiness endpoint is `/actuator/health/readiness`; health details are hidden. The existing V1 migration creates pgvector and the configured-dimension V2 migration must agree with `EMBEDDING_DIMENSION`. The existing appointment seed migration contains fictional sample data; review that separately before serving real clinic traffic.

## Vercel frontend

Import the `MolarAI/frontend/` directory as the Vercel project root (or set the project root to `frontend/`). Use `npm run build` and `dist` as the output directory. Configure `VITE_API_BASE_URL` as the backend's HTTPS origin, for example `https://your-backend.onrender.com`, without a trailing slash. This is a public build-time setting, not a secret. In disabled embedding mode, knowledge-based requests show that answers are temporarily unavailable; deterministic availability/date/hours replies continue working. Set `VITE_CANCELLATION_ENABLED=false` in Vercel to hide the cancellation suggestion and prevent starting cancellation through chat. Also set backend `CANCELLATION_ENABLED=false`; the frontend flag is presentation only, and the server-side guard is authoritative. Set the exact frontend origin in Render's `CORS_ALLOWED_ORIGINS`; add any preview origins individually only if needed. Redeploy the frontend after changing build-time variables.

## Local development

Continue to use the root `.env.example` and Docker Compose configuration for the local database. Those settings are development-only. Cancellation is enabled locally by default (`CANCELLATION_ENABLED=true`). Start the frontend with `npm run dev` in `frontend/` and the backend with Maven from `backend/`. For local OTP testing only, start the backend with `SPRING_PROFILES_ACTIVE=dev CANCELLATION_OTP_EXPOSE_CODE=true`; the mock does not send SMS, and the API returns a local `developmentOtp`. Exposure defaults off and is suppressed under both `prod` and `production` profiles.

Use a real, untracked `.env` for local credentials. The root `.gitignore` ignores `.env` files other than committed examples, and Docker excludes all `.env` files from its build context.

## Hosting limitations and release blockers

- The only implemented OTP delivery provider is a mock. It performs no SMS delivery; production refuses to start with it, and no real provider is implemented. Production deployment is blocked until one is added and verified.
- The API currently has no authentication or patient identity verification. CORS is not access control. Do not expose appointment/patient endpoints to real users until those controls and a privacy review are in place.
- The OTP process verifies a code but does not authenticate a user. Cancellation match/request/resend/verify/direct-cancel endpoints are unauthenticated; set backend `CANCELLATION_ENABLED=false` for a public demo to have the server reject each with HTTP 403. The Vercel `VITE_CANCELLATION_ENABLED=false` setting hides the frontend option but is not an access control. Public booking also accepts patient name/contact without authentication; use an isolated demo database and synthetic-only input, and never enter real patient data.
- Confirm production appointment schedule/provider settings and replace the fictional seeded appointment data before real clinic use.
- Use a paid/stable Render web-service and managed-database plan for persistent production hosting; free/ephemeral tiers may sleep, have resource limits, or not provide production data durability. Provider prices, limits, and pgvector availability vary by region and plan.
- The external chat and embedding providers may incur usage charges and have their own rate limits/data-processing terms. Vercel's Hobby plan is currently free for eligible personal use; Render compute/database and model provider costs are separate. Confirm current pricing and terms before provisioning anything.
