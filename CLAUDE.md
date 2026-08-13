# CVEvaluator

Spring Boot CV evaluation platform. Recruiters post jobs, candidates
upload CVs, an LLM produces a structured assessment where every claim
is backed by a verbatim quote from the CV, and a grounding checker
verifies those quotes are real.

Internship project. Demo is **Aug 15, 2026**. Optimise for shipping
and for explainability at demo time — not for generality, not for
future-proofing. I have to defend every decision out loud.

---

## Stack — these versions matter

- **Spring Boot 4.1**, Spring Framework 7
- **Java 21**
- **Hibernate 7 / JPA 3.2**
- **Jackson 3**
- **JUnit 5 only**
- PostgreSQL 17 + **pgvector**
- **Spring AI 2.0** with Google GenAI (Gemini) starters
- Apache PDFBox (PDF), Apache POI XWPF (DOCX)
- Frontend: React + Vite + TanStack Query + React Router

---

## Version rules — violating these produces code that will not compile

Your training data likely skews toward Spring Boot 3. It does not
apply here. Check against this list before writing anything.

- **JUnit 4 is removed.** Never `@RunWith`. Never `org.junit.Test`.
- **`@MockBean` and `@SpyBean` are removed.** Use **`@MockitoBean`**
  and **`@MockitoSpyBean`**, imported from
  `org.springframework.test.context.bean.override.mockito`.
  The package moved — it is no longer under
  `org.springframework.boot.test.mock.mockito`.
- **Jackson 3**, not Jackson 2. Package roots changed.
- **Spring Security 7:** `requestMatchers`, lambda DSL only. Never
  `antMatchers`, never `.and()` chaining, never
  `WebSecurityConfigurerAdapter`.
- **`@MockBean` is gone here too** — security slices use
  `@MockitoBean`. And **`@WebMvcTest` does not pick up
  `SecurityConfig`**: the slice auto-configures Boot's *default*
  chain instead, so a test without `@WithSecurityRules` asserts
  against rules the application does not have, and passes.
- **Boot 4 security artifact ids are the modular ones:**
  `spring-boot-starter-security-oauth2-resource-server`, not the
  Boot 3 `spring-boot-starter-oauth2-resource-server`.
- **Spring AI:** `initialize-schema=true` is opt-in for
  `PgVectorStore`. Nothing works until it is set.
- **pgvector:** use **HNSW**, never IVFFlat. IVFFlat caps at 2000
  dimensions and fails at index creation above that.
- **Gemini:** set **only** `spring.ai.google.genai.api-key`. Any
  `project-id` or `location` property — even experimentally — flips
  the client into Vertex AI mode and rejects the Developer key with
  a confusing 400.
- **`ChatClient` prompt templates use `{}`** — the same braces as
  JSON. Any literal JSON example in a prompt, or any retrieved text
  that might contain braces, must bypass the default
  `StTemplateRenderer`. Use `NoOpTemplateRenderer` or change the
  delimiters.
- **Structured output:** never call `.entity()` on a bare `List<T>`.
  Native provider structured output rejects a top-level JSON array
  schema. Wrap lists in a record.
- **File magic bytes:** DOCX, XLSX, PPTX and ZIP all share
  `50 4B 03 04`. They are all zip archives. Extension checks and
  magic-byte checks are not interchangeable.

---

## Architecture decisions — do not revisit or "improve" these

Each of these was decided deliberately and I have to defend it.
If you think one is wrong, say so in one line and then follow it
anyway.

- **`ddl-auto: update`. No Flyway.** Schema is still moving daily and
  hand-written migrations are not worth the time at this scale.
- **Auth is real, and it is JWT over Spring Security.** This reverses
  the earlier "auth is stubbed, do not add Spring Security" decision —
  the seam did its job: `HeaderCurrentUserProvider` was deleted and
  `JwtCurrentUserProvider` took its place, and no caller changed.
  - **HS256, symmetric, self-issued.** This application is both
    issuer and verifier, so there is no keypair to manage and no
    authorization server. `spring-boot-starter-security-oauth2-resource-server`
    is on the classpath for `NimbusJwtEncoder`/`Decoder` and the
    bearer-token filter only — nothing here talks OAuth2 to anyone.
  - **Stateless. No refresh token, no revocation list.** A token is
    good until it expires; logout is a client-side discard. Accepted
    cost, not an oversight.
  - **The whole policy is URL rules in `SecurityConfig`**, readable
    top to bottom. Ownership rules, which need the row, are scoped
    repository queries instead — `findByIdAndCreatedByRecruiter_Id`,
    `findByIdAndJob_CreatedByRecruiter_Id`.
  - **Wrong role is 403; foreign-owned resource is 404.** The 404 is
    deliberate: a 403 would confirm the row exists and let a
    recruiter enumerate other people's applications by id.
  - **Candidates never see evaluations at all** — not the verdict,
    not the scores, not the reasoning. `/api/applications/**` is
    RECRUITER-only. Product decision.
- **Vectors live in `PgVectorStore`, never as a column on an
  `@Entity`.** Hibernate has no native mapping for `VECTOR(n)`.
  `CvChunk` stores a `vectorDocId` reference only.
- **One vector table**, separated by metadata
  `type: CV_CHUNK | PRECEDENT`, filtered at query time via Spring
  AI's metadata filter API.
- **DTOs are records, always separate from entities.** An entity
  never goes on the wire — not once, not "temporarily."
- **All JPA associations explicitly LAZY**, owning side on the
  `@ManyToOne`.
- **All errors go through `GlobalExceptionHandler`** as an
  `ErrorResponse` record. Never a stack trace or a Spring default
  error page to the client. Never surface a raw
  `ex.getMessage()` from Jackson or Hibernate to the client.
- **File I/O never inside `@Transactional`.** The file is written to
  disk *before* the row is inserted.
- **Async evaluation with status polling.** `POST` returns 202
  immediately; the client polls for terminal status. No synchronous
  LLM calls on a request thread.
- **Redaction before embedding.** PII is stripped before any text
  leaves the process for an embedding or chat call.

---

## Current code layout

Base package: `com.apliman.cvevaluator`

Already built and working — **do not rewrite these unless asked**:

- `User`, `Job`, `Application` entities with the full relationship
  graph. `Application` already carries `storagePath`,
  `extractedText`, `extractionMethod`, and a status enum
  (`PENDING / PROCESSING / COMPLETED / FAILED`).
- `JobRepository`, `ApplicationRepository` (with
  `findByJobOrderBySubmittedAt`).
- `JobController` — `POST /api/jobs`, `GET /api/jobs`,
  `GET /api/jobs/{id}`.
- Request/response DTOs as records.
- `ErrorResponse` + `GlobalExceptionHandler` (`@RestControllerAdvice`).
- Bean Validation wired end to end.
- `com.apliman.cvevaluator.security`: `SecurityConfig` (the entire
  authn/authz policy), `AuthProperties`, `AccessTokenIssuer`,
  `JwtClaims`, `ErrorResponseWriter`, and `CurrentUserProvider` +
  `JwtCurrentUserProvider`.
- `com.apliman.cvevaluator.auth`: `AuthController`
  (`POST /api/auth/register`, `POST /api/auth/login`,
  `GET /api/auth/me`), `AuthService`, and record DTOs.
- A `@DataJpaTest` over the application query that clears the
  persistence context before fetching, to make the N+1 visible.

In progress: file upload and storage, then text extraction.

---

## Working rules

- **Do not add a dependency without asking me first.** Name it, say
  why, wait.
- **Do not add features I did not ask for.** No agents, no reranker,
  no OCR, no Redis, no Kafka, no caching layer, no rate limiter.
- **Prefer the smallest change that passes the stated check.** If I
  give you a "done when" condition, build to that condition and stop.
- **`spring.jpa.show-sql=true` stays on.** Do not turn it off.
- **When a design choice has a real alternative, say so in one line**
  — what you picked, what you rejected, why. I need to be able to
  answer that in the demo.
- **Commit after each working checkpoint** with a real message
  describing what changed and why.
- **Tell me when something you wrote is a place I should look
  carefully** — a non-obvious trap, a behaviour that only shows up
  under load, an assumption you made because I did not specify.

---

## Known limitations — intentional, do not "fix"

- No OCR. A scanned PDF with no text layer fails cleanly with a
  readable reason. That is the designed behaviour.
- Precedent corpus is synthetic, generated from fixture CVs.
- Rubric is English-only.
- Scores are not calibrated against human reviewers.
- **No rate limiting and no account lockout.** Login is unthrottled;
  a password can be guessed as fast as BCrypt allows. Out of scope.
- **No refresh tokens and no logout endpoint.** A stolen token is
  valid until it expires.
- **The JWT signing secret has a committed development default.**
  It warns loudly at startup when in use. Set `CVEVAL_JWT_SECRET`
  for anything reachable by anyone else.
- **Registration discloses which emails have accounts** (409 on a
  duplicate). Login does not — same 401 either way, and the password
  comparison runs even for unknown addresses so the timing matches.
