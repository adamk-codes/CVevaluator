# CV Evaluator — Day-by-Day Execution Plan (v3)
### Apliman Phase 1 · Jul 22 → Aug 15, 2026
*Built for: strong Python/ML, solid Java coursework, **no prior backend or frontend experience.***

---

## 0. What Changed in v3, and Why

Two corrections from you, both important.

**You have no prior backend or frontend experience.** Not FastAPI, not Django REST — the RedReady and MineGuard web layers were teammates' work. So v2's core assumption ("you know REST, you just need Spring syntax") was wrong, and every timebox built on it was wrong.

**You want to keep scope, not cut it.** Fair — and on re-examination, mostly correct. I cut by reflex rather than by costing each item. Here's the actual cost of everything I proposed dropping:

| Item I cut in v2 | Real cost | Verdict |
|---|---|---|
| Recruiter dashboard | ~4h once the ranked endpoint exists | **Keep.** Without it the demo is candidate-only, which is a strange shape for a hiring tool. |
| Semantic search UI | ~2h (endpoint already built) | **Keep.** |
| Register flow | ~1h once auth exists | **Keep.** |
| Precedent panel | ~3h | **Keep.** It's what makes RAG visible. |
| Bias propagation experiment | ~1 full day | **Stretch goal.** Real cost, real value — but it's the one thing that can go if you're behind. |

So: **almost nothing is cut.** What I got right was the concern about the *total*, not any individual item — and the fix for that is the two fundamentals days below, not amputation.

**To be explicit, since it was unclear:** persistence was never in question. Users, jobs, applications, evaluations, criterion scores, chunks, retrieved precedents — all in PostgreSQL, all real. There is no mocked data layer anywhere in this plan.

---

## 1. On Adding More Time

You want to maximise hours. Here's the honest version.

**Hours help — up to a ceiling, and the ceiling is lower than you'd like.** Typing time scales linearly. Comprehension doesn't. You're learning genuinely new conceptual material (what a persistence context *is*, what a filter chain *is*), and that kind of learning consolidates during sleep, not during hour eleven. Past roughly 9–10 focused hours on new concepts, you stop absorbing and start producing code you won't be able to explain in the demo — which is precisely what Rawad is testing.

**This plan is built at ~9h weekdays and ~7–8h weekends.** That's ~200 hours from here, up from ~170 in v2. If a day runs long because you're in flow on something concrete, fine. If you're grinding at midnight on a concept that isn't landing, stop — that's a message-me moment, not a push-through moment. The 20-minute explain-back is your own early warning system for this.

---

## 2. Two Fundamentals Days — Non-Negotiable

Jul 22–23 are not Spring days. This is the single biggest change in v3, and skipping it will cost you a week later.

**Why.** Spring Academy felt like arbitrary magic because you were being asked to learn *two* things at once: what a web backend is, and how Spring expresses it. Nobody can learn both simultaneously — the annotations stay opaque because you have no model for what they're annotating. Two days now buys back far more than two days later, because every Spring concept afterward has somewhere to attach.

**The technique on Day 1 is deliberate:** you'll build a tiny throwaway API **in Python**, a language where syntax costs you nothing. That isolates the variable. When Spring arrives on Jul 24, HTTP and REST are already familiar and the only new thing is Java syntax — one unknown instead of two.

---

## 3. Four Decisions Made For You

Each goes in a `Decided:` line to Rawad.

**1. Native Google GenAI starters, not the OpenAI-compat trick.**
Spring AI ships `spring-ai-starter-model-google-genai` (chat) and `spring-ai-starter-model-google-genai-embedding` (embeddings). This eliminates the roadmap's flagged three-day risk about whether the compat endpoint serves embeddings.
> ⚠️ Set **only** `spring.ai.google.genai.api-key`. Any `project-id` or `location` — even experimentally — flips the client to Vertex AI mode and rejects your free Developer key with a confusing 400.

**2. Vectors live in `PgVectorStore`, not on a JPA entity.**
No `VECTOR(768)` column on an `@Entity` — Hibernate has no native mapping and you'd lose a day to custom types. `CvChunk` stores `content`, `charStart`, `charEnd`, `sectionType`, `vectorDocId`.

**3. One vector table, separated by metadata.**
`type: CV_CHUNK | PRECEDENT`, filtered at query time via Spring AI's metadata filter API.

**4. Auth is stubbed until Aug 6.**
`CurrentUserProvider` interface + `HeaderCurrentUserProvider` reading `X-User-Id` from Day 3. On Aug 6 you write `JwtCurrentUserProvider` and swap one bean. Security 7 is the highest-variance time sink in this project and it is not the differentiator.

*Skip Flyway. `ddl-auto: update` is correct at this scale.*

---

## 4. Send These to Rawad Today

1. **Show me one "detailed feedback" output you'd call good, and one you'd call bad.** *(Highest-risk ambiguity — you can build the wrong thing competently for a month.)*
2. Which LLM, whose API key, and does CV data have to stay internal? *(Needed before Jul 28.)*
3. Existing Apliman repo structure or Spring conventions to follow?

And — your call, but I'd say it plainly this week: *"I'm coming to Spring from a Python/ML background with no prior backend experience. Here's my plan, here's the gate I've set myself on Aug 1, and here's where I'll want your input."* Interns who calibrate early get help. Interns who go quiet and miss get read as having overpromised. You have a real asymmetric strength here — the AI layer is where you're strongest and probably where the other intern isn't — but that only pays off if expectations are set honestly.

---

## 5. How To Use The Resources

1. **Timeboxes are ceilings, not targets.** If the doc answers your question in 10 minutes, close it.
2. **Official docs beat tutorials for anything version-sensitive.** Spring Boot 4.0 shipped Nov 2025, 4.1 in Jun 2026. Anything written before ~Feb 2026 teaches Boot 3 — Security defaults changed, Jackson 3 replaced Jackson 2, JUnit 4 was removed. **Check the date on everything.**
3. **Never copy a code block.** Read it, close the tab, write it yourself. That's the entire reason we dropped the course.

**[core]** = read it · **[ref]** = look up the one thing you need · **[skim]** = 10 minutes max

---

# 6. The Schedule

**Format:** *Goal* → *Learn* → *Resources* → *Build* → **Done when** (a binary test — if you can't run it, the day isn't finished).

**Week 1 only:** show me your work every ~90 minutes, not at end of day. From zero, the failure mode isn't transcription — it's staring at an empty file not knowing what to type. Short loops fix that.

---

## FUNDAMENTALS (Jul 22–23)

### Wed Jul 22 · F1 — What A Backend Actually Is
**Goal:** HTTP and REST stop being magic. Zero Java today.

**Learn (4h):**
- Client/server: what runs where, and what "the server" physically is.
- **Statelessness** — why each request carries everything it needs, and why this is the single idea that makes REST make sense.
- HTTP: verbs (GET/POST/PUT/DELETE), status codes (200/201/202/400/401/403/404/422/500 — learn what each *means*, you'll be choosing them all month), headers, request body, response body.
- JSON as the wire format. Serialisation and deserialisation as a concept.
- REST resource modelling: why it's `/api/jobs/5/applications` and not `/api/getApplicationsForJob?id=5`.
- What a REST client is: curl, Postman, and a browser's network tab.

**Resources:**
- **[core]** MDN Web Docs → *HTTP → Overview*, *Methods*, *Status codes*: `developer.mozilla.org/en-US/docs/Web/HTTP` — the best free reference on this, and not framework-specific.
- **[core]** MDN → *An overview of HTTP* and *Client-Server overview* in the Learn section.
- **[skim]** Any "REST API design" primer for resource naming conventions.
- **Me** — the mental model walkthrough, and your questions as they come.

**Build (3h, throwaway, in Python):**
- A ~40-line Flask or FastAPI app. In-memory list, no database.
- `GET /items` (list), `POST /items` (create, returns 201), `GET /items/{id}` (returns 404 if missing).
- Hit every endpoint with curl. Watch the status codes. Send malformed JSON on purpose and see what happens.
- **Then delete it.** It exists to make the concepts concrete, nothing else. Timebox hard at 3 hours.

**Done when:** you can explain to me, without notes, what happens between typing a URL and seeing JSON — and why a 404 and a 500 mean different things about whose fault it is.

---

### Thu Jul 23 · F2 — Databases, ORMs, and Modern Java
**Goal:** The persistence layer stops being magic. Java gets current.

**Learn (5h):**
- *Relational modelling (2h)* — tables, rows, columns, primary keys, **foreign keys**, one-to-many vs many-to-one, normalisation at a basic level.
- *SQL (1.5h)* — `CREATE TABLE`, `INSERT`, `SELECT`, `WHERE`, `JOIN`, `ORDER BY`. Enough to read and write queries by hand, because you'll need to verify what Hibernate generates.
- *Transactions (30m)* — atomicity, commit, rollback, and why "all or nothing" matters.
- *What an ORM is and why it exists (1h)* — objects in your code ↔ rows in a table, the impedance mismatch, and the honest tradeoff: convenience for a layer of hidden behaviour you have to learn to see through.

**Learn (2h): Modern Java**
- `record` (you'll use these constantly for DTOs), streams and collectors, `Optional`, try-with-resources, `var`, enhanced switch, text blocks. Your AUB Java is probably Java 8-flavoured; this is Java 21.

**Resources:**
- **[core]** PostgreSQL Tutorial (`postgresqltutorial.com`) — Basics section only. Practical and fast.
- **[core]** Type every query by hand into `psql` against the Postgres container you already have running. Don't read SQL — write it.
- **[core]** Dev.java or Baeldung on Java records and streams. Short.
- **[ref]** PostgreSQL official docs for anything the tutorial glosses.
- **Me** — the ORM tradeoff explanation, and why it's the thing that will confuse you most in week 1.

**Build (2h):**
- In `psql`: create `users`, `jobs`, `applications` tables by hand with real foreign keys. Insert a few rows. Write a JOIN that returns every application with its job title and candidate email.
- Then drop them all — Spring will recreate them. You wrote them so you'll recognise what Hibernate generates.

**Done when:** you can write a two-table JOIN from memory, and explain what a foreign key constrains and why.

---

## WEEK 1 — Spring Backend (Jul 24–28)

### Fri Jul 24 · S1 — Spring Core + First Endpoint
**Goal:** HTTP → controller → service → repository → Postgres → JSON.

**Learn (3.5h):**
- *Spring Core DI (2h)* — the application context, bean lifecycle, constructor injection, `@Component`/`@Service`/`@Repository`/`@Configuration`, component scanning. **The one sentence that unlocks Spring:** *you never construct your own dependencies; the framework constructs them at startup and hands them to you.*
- *Spring MVC (1.5h)* — DispatcherServlet, handler mapping, `@RestController`, `@RequestBody`, `@PathVariable`, `HttpMessageConverter`, `ResponseEntity`. Map each piece onto what you built in Python on Jul 22.

**Resources:**
- **[core]** Spring Framework reference → *Core Technologies → The IoC Container*, §1.1–1.4 only: `docs.spring.io/spring-framework/reference/core/beans.html`
- **[core]** Dan Vega's Spring Boot crash course, first ~45 min (YouTube `@DanVega`) — he's the Spring Developer Advocate. It's Boot 3, but **Core concepts are identical in Boot 4**; watch for concepts, ignore version specifics.
- **[skim]** Baeldung, "Spring Boot 4 & Spring Framework 7 – What's New" (Mar 2026): `baeldung.com/spring-boot-4-spring-framework-7`
- **[ref]** Spring Boot reference: `docs.spring.io/spring-boot/reference/`

**Build:**
- Send Rawad questions 1–3 **before writing code.**
- `User` entity: `id, email (unique), passwordHash, role enum, createdAt`. Password null for now.
- `Job` entity: `id, title, description, requirements, seniority, createdBy → User, createdAt, active`.
- `JobRepository extends JpaRepository<Job, Long>`.
- `JobController`: `POST /api/jobs`, `GET /api/jobs`, `GET /api/jobs/{id}`.
- **Request/response DTOs as records.** Never put an entity on the wire — not once, not "temporarily."
- `@RestControllerAdvice` + one `ErrorResponse` record. Today, not in week 4.
- `CurrentUserProvider` interface + `HeaderCurrentUserProvider` (§3.4).

**Done when:** `curl -X POST /api/jobs` creates a row you can see in `psql`, `GET` returns it as JSON, and a nonexistent id returns your `ErrorResponse` with a 404 — no stack trace, no Spring default error page.

**End of day:** first Friday note to Rawad. Five lines. Send it even though the week was fundamentals — especially because it was.

---

### Sat Jul 25 · S2 — JPA Relationships
**Goal:** The hard concept day. Everything you learned about SQL on Thursday, now through Hibernate.

**Learn (5h — the biggest learning block in this plan):** `@Entity` lifecycle and the **persistence context** · managed vs. detached · `@ManyToOne` / `@OneToMany` · **fetch types, and why `@ManyToOne` defaults to EAGER while `@OneToMany` defaults to LAZY** · `LazyInitializationException` · `@Transactional` boundaries and proxy self-invocation · cascade types · the N+1 problem.

**Resources:**
- **[core]** Spring Data JPA reference, *Working with Spring Data Repositories* + *Defining Query Methods*: `docs.spring.io/spring-data/jpa/reference/`
- **[core]** Hibernate 7 User Guide — *Persistence Contexts* and *Associations* chapters only. Boot 4 ships **Hibernate ORM 7 / JPA 3.2**; do not read Hibernate 5 material, the API changed.
- **[core]** Turn on `spring.jpa.show-sql=true` and read the SQL Hibernate generates for every call. This is the highest-value habit you can build this week — it turns the ORM from magic into a thing you can see.
- **[ref]** Baeldung on `LazyInitializationException` (check publish dates).
- **Me** — the persistence-context model, and a post-mortem on your first `LazyInitializationException`. You will get one. That's the day working correctly, not failing.

**Build:**
- `Application` entity: `id, job → Job, candidate → User, originalFilename, contentType, sizeBytes, storagePath, extractedText, redactedText, extractionMethod, textLength, status enum, failureReason, submittedAt`.
- Bidirectional `Job` ↔ `Application` relationship, correct fetch types.
- Repository query: all applications for a job, ordered — and check the generated SQL for N+1.
- Bean Validation on DTOs (`@NotBlank`, `@Size`), wired into your error handler.

**Done when:** you can save a job with three applications, fetch them back, and **show me the SQL Hibernate generated** and explain why it's that many queries.

---

### Sun Jul 26 · S3 — File Upload
**Goal:** Files on disk, rows in the DB.

**Learn (1.5h):** multipart requests · `@RequestPart` · `MultipartFile` · size limits · why you never trust a client-supplied filename.

**Resources:**
- **[core]** Spring Boot reference → *Web → Servlet Web Applications → Multipart*.
- **[ref]** `MultipartFile` javadoc.

**Build:**
- `POST /api/applications` — multipart, file + `jobId`.
- Store as `storage/{uuid}.{ext}` — **never the user's filename.**
- Persist with `status = PENDING`, return `202` + id.
- `GET /api/applications/{id}` returning status + metadata.
- Size cap (10MB) in `application.yml` **and** checked in code.
- Filename sanitisation: strip separators, reject `../`, escape on output.

**Done when:** Postman upload → 202 with an id → file on disk under a UUID name → DB row linking to the right job and user · and a filename of `../../etc/passwd` doesn't escape your storage directory.

---

### Mon Jul 27 · S4 — Extraction, Redaction, Hostile Input
**Goal:** Text out of real PDFs. Garbage rejected cleanly.

**Learn (1h):** PDFBox `PDFTextStripper` · POI `XWPFDocument` · magic bytes.

**Resources:**
- **[core]** Apache PDFBox docs — `PDFTextStripper` page only: `pdfbox.apache.org`
- **[ref]** Apache POI `XWPFWordExtractor` javadoc: `poi.apache.org`
- **[skim]** A file-signature table for `%PDF-` and `PK\x03\x04`.

**Build:**
- `CvTextExtractor` — PDF via PDFBox, DOCX via POI, reject everything else.
- **Validate magic bytes, not extension.**
- **Zero-extracted-text → reject as scanned.** Best trap on the break-it list; handle it now, not in week 4.
- `PiiRedactor` v1: email regex, phone regex, name strategy (header-line heuristic + tokens matching the `User.email` local part). Runs before **both** the LLM call and embedding.
- Set `extractionMethod` and `textLength`.

**Done when:** real PDF → redacted text in DB · `.exe` renamed `.pdf` → 400 with a readable message · scanned PDF → 422 "no text layer" · 0-byte file → clean rejection.

---

### Tue Jul 28 · S5 — Fixtures + Gemini Wired
**Goal:** Your test corpus and your precedent corpus, which are the same thing.

**Resources:**
- **[core]** Spring AI Google GenAI Chat: `docs.spring.io/spring-ai/reference/api/chat/google-genai-chat.html`
- **[core]** Spring AI Google GenAI Embeddings: `docs.spring.io/spring-ai/reference/api/embeddings/google-genai-embeddings-text.html`
- **[core]** Ravi Kumar, "Spring AI + Gemini: Your First API Call in Java" (May 2026): `ravibuilds.dev/blog/spring-ai-gemini-first-api-call` — short, current, documents the exact auth-mode gotchas.
- Free API key, no card: `aistudio.google.com/apikey`

**Build:**
- **First hour:** both starters + the Spring AI BOM, set only `api-key`, make one chat call and one embedding call succeed. **Record the embedding dimension** — you need it on Aug 2.
- 3 job postings, genuinely different shapes: backend engineer, data/ML, non-technical (so NOT_A_FIT is unambiguous for some CVs).
- **20 synthetic CVs**, deliberate quality spread: ~5 strong, ~8 borderline, ~7 weak per job. Vary format and length — some with clean section headers, some without.
- **Adversarial set, separate:** scanned PDF (print-to-image one of the others), 40-page CV, Arabic/RTL CV, restaurant menu, 0-byte file, `.exe` renamed.
- Commit under `fixtures/`. Project asset, not scratch.

**Done when:** `fixtures/` holds 20 CVs, 3 job JSONs, 6 adversarial files, and a real embedding vector came back from Gemini with its dimension written down.

---

## WEEK 2 — Evaluation Pipeline & Gate (Jul 29 – Aug 1)

### Wed Jul 29 · S6 — Spring AI + First Real Evaluation
**Goal:** One CV in, one structured `EvaluationResult` out. Synchronous.

**Learn (3h):** `ChatClient` fluent API · Structured Output Converter · `.entity()` and `validateSchema()` · prompt templates and `.param()`.

**Resources:**
- **[core]** Spring AI Structured Output Converter: `docs.spring.io/spring-ai/reference/api/structured-output-converter.html`
- **[core]** Spring blog, "Self-Correcting Structured Output in Spring AI 2.0" (Jun 2026): `spring.io/blog/2026/06/23/spring-ai-self-correcting-structured-output/` — best explanation of `validateSchema()` anywhere, and 2.0-specific.
- ⚠️ **Ignore every Spring AI Udemy course** — all on 1.x, none have `validateSchema()`.
- ⚠️ Native provider structured output rejects a top-level JSON array schema. Your `EvaluationResult` is a record wrapping lists, so you're fine — but never `.entity()` a bare `List<T>`.

**Build:**
- `EvaluationResult` and `CriterionAssessment` records (roadmap §4.2).
- Rubric system prompt v1, **as a resource file with a `promptVersion` constant.** Bump on every edit, persist on every evaluation. You'll want this on Aug 5.
- Criteria: technical skill match · relevant experience depth · domain relevance · education fit · evidence of impact/ownership · communication quality.
- `LlmEvaluator.evaluate(Job, String cvText) → EvaluationResult`. Synchronous, no retry, no persistence yet.
- Log `tokensIn`, `tokensOut`, `latencyMs` from the first call.

**Done when:** a JUnit test takes fixture CV #1 + job #1 and returns a populated `EvaluationResult` with 6 criteria and verbatim evidence quotes.

---

### Thu Jul 30 · S7 — Async Orchestration
**Goal:** 202-and-poll working end to end.

**Learn (2h):** `@EnableAsync` · `TaskExecutor` sizing · **the self-invocation trap** (an `@Async` method called from inside the same bean doesn't go through the proxy and runs synchronously — this bites everyone once) · Spring Retry with exponential backoff.

**Resources:**
- **[core]** Spring Framework reference → *Integration → Task Execution and Scheduling*.
- **[ref]** Spring Boot 4.1 release notes on async context propagation for `@Async` — new in 4.1, affects how request-ID logging survives the async boundary.
- **[ref]** Spring Retry README.
- **Me** — thread pool sizing for an LLM-bound (not CPU-bound) workload; the intuition is different from what you'd guess.

**Build:**
- `Evaluation` and `CriterionScore` entities (roadmap §3).
- `EvaluationService` `@Async`: `PENDING → PROCESSING → COMPLETED | FAILED`.
- Explicit `TaskExecutor` bean — core 2, max 4, queue 20. Sized deliberately, not defaulted.
- Hard 30s timeout. Exponential backoff on 429/5xx, cap 3.
- Any failure → `FAILED` + human-readable `failureReason`. **Zero stack traces to the client.**
- `GET /api/applications/{id}` returns status and, when COMPLETED, the full evaluation.
- **Startup sweep:** `ApplicationRunner` failing rows stuck in `PROCESSING` from a previous run. Five lines. Almost nobody thinks of it, and it reads as maturity.

**Done when:** `POST` returns 202 in under 200ms while the evaluation is still running, and polling shows `PROCESSING` then `COMPLETED`.

---

### Fri Jul 31 · S8 — The Grounding Checker
**Goal:** Your first real differentiator. Numbers instead of vibes.

**Learn (30m):** string normalisation, Levenshtein ratio vs. token-set overlap.

**Resources:**
- **[ref]** Apache Commons Text `LevenshteinDistance` / `JaroWinklerSimilarity`. One dependency, no ML.
- **Me** — threshold selection. Too tight and you flag real quotes; too loose and you miss real hallucinations. There's a right answer and it's testable.

**Build:**
- `GroundingChecker` — normalise (lowercase, collapse whitespace, strip punctuation), substring-match each `evidenceQuote` against CV text, ~90% fuzzy tolerance.
- Two-way for now: `CV` (verified) vs `UNGROUNDED`. Third case arrives Aug 4.
- Persist `evidenceSource` and `evidenceVerified` per `CriterionScore`.
- Compute and persist a per-evaluation **ungrounded rate**.
- **Unit tests today.** Pure logic, no Spring context, highest test ROI in the codebase: exact match · whitespace variation · case variation · reformatted quote · fabricated quote · empty quote · quote longer than the CV.

**Done when:** fixture CV #3 shows per-claim `verified: true/false` and an ungrounded percentage, and the fabricated-quote test passes.

**End of day:** Friday note. `Decided:` = pgvector over a dedicated vector DB. Two sentences.

---

### Sat Aug 1 · 🚩 GATE — 60 Evaluations
**Goal:** A complete, demoable system with zero RAG. **This is the gate that matters.**

**Build:**
- A fixture runner (Spring Boot test or profile-gated `CommandLineRunner`): 20 CVs × 3 jobs, wait for terminal status, persist everything.
- Summary printout: verdict distribution · mean and p95 latency · mean ungrounded % · total tokens · **cost per CV in dollars**.
- Run the adversarial set through it. Fix what breaks.
- Write `results/baseline-no-rag.md`.

**🚩 Done when:** 60 `Evaluation` rows exist, the summary prints, and you can `curl` a CV → 202 → poll → full structured evaluation with verified evidence.

**If this doesn't happen today, tell Rawad Monday morning — not in week 4.** Everything from here layers onto a working system; nothing from here is load-bearing for a demo. That's the whole point of the gate.

**Also:** short note to Rawad. "End-to-end pipeline live, 60 fixture evaluations run, here are the numbers." That changes his read of you.

*Your precedent corpus is now seeded. Not a side effect — the design.*

---

## WEEK 3 — RAG (Aug 2–5)

### Sun Aug 2 · R1 — Chunking + Embeddings
**Goal:** Vectors in the ground.

**Learn (3h):** `VectorStore` / `PgVectorStore` · metadata filter expressions · HNSW vs IVFFlat · chunking strategy.

**Resources:**
- **[core]** Spring AI PGvector: `docs.spring.io/spring-ai/reference/api/vectordbs/pgvector.html` — note `initialize-schema=true` is **opt-in**; nothing works until you set it.
- **[core]** Spring AI Vector Databases (metadata filter API): `docs.spring.io/spring-ai/reference/api/vectordbs.html`
- **[core]** Ed Donner LLM Engineering **Week 5**, 1.75×, **concepts only.** Pull it forward out of order. His LangChain/Chroma code doesn't transfer to Java — take the chunking and retrieval-evaluation instincts, leave the scaffolding.
- **[ref]** pgvector README.
- ⚠️ IVFFlat caps at 2000 dimensions and fails at index creation above it. Use **HNSW**.

**Build:**
- `CvChunker` — **section-based, not fixed-window.** Header regex (`EXPERIENCE|EDUCATION|SKILLS|PROJECTS|CERTIFICATION`, case-insensitive, punctuation-tolerant). Paragraph fallback when no headers exist — some fixtures deliberately lack them. **Record `charStart`/`charEnd` against the *original* text**, because that's what the frontend highlights.
- `CvChunk` entity: `application, chunkIndex, sectionType, content, charStart, charEnd, vectorDocId`. **No embedding column** (§3.2).
- `EmbeddingService`: redact → embed → store with metadata `{type: CV_CHUNK, applicationId, jobId}`.
- Set `spring.ai.vectorstore.pgvector.dimensions` to the number from Jul 28.
- Backfill all 60 fixtures.

**Done when:** the vector table is populated, and a similarity query for "kubernetes deployment experience" from `psql` returns chunks that are actually about that.

---

### Mon Aug 3 · R2 — Precedent Retrieval
**Goal:** Retrieval that's measured, not assumed.

**Learn (1h):** recall@k, and why precision matters less here.

**Resources:**
- **[ref]** Spring AI `SearchRequest` javadoc — `topK`, `similarityThreshold`, `filterExpression`.
- **Me** — how to hand-label 10 pairs without fooling yourself, and what recall@3 number is actually acceptable.

**Build:**
- **What gets embedded for precedent:** the *(job requirements + CV summary)* pair from a completed evaluation — not the raw CV. The question is "have we seen a similar candidate for a similar role," not "which CVs share vocabulary." Metadata `{type: PRECEDENT, evaluationId, jobId}`.
- Backfill precedent embeddings for all 60.
- `PrecedentRetriever`: top-k = 3 · **similarity threshold — nothing clears it, return nothing and say so** · **exclude self by applicationId** (a CV retrieving itself as its own precedent bites everyone once) · empty-store path for the first-ever candidate.
- `RetrievedPrecedent` audit rows: which evaluations, what similarity, what rank.
- **Retrieval evaluation:** hand-label 10 query → relevant-precedent pairs. Measure recall@3. Write `results/retrieval-eval.md`.

**Done when:** recall@3 is a number you can say out loud, and an application against an empty vector store completes normally with zero precedents.

---

### Tue Aug 4 · R3 — Prompt Assembly + Three-Way Attribution
**Goal:** The thing that makes this an engineered system rather than a prompt.

**Build:**
- `PromptAssembler` with the source-separated template (roadmap §4.3) — precedents fenced as `CALIBRATION PRECEDENTS (reference only) — these are NOT this candidate`, CV in the user turn, explicit instruction that CV wins on conflict.
- **Feature flag `rag.enabled`**, read at request time so you can flip it without a rebuild.
- **Extend `GroundingChecker` to three-way:** matches CV → `CV`; else matches a retrieved precedent → `PRECEDENT` (leakage); else → `UNGROUNDED`.
- Persist all three. Compute a **leakage rate** alongside the ungrounded rate.
- Tests including a quote appearing in both CV and precedent (CV must win).

**Done when:** with `rag.enabled=true` you can point at a claim and say which bucket it fell in — and the leakage counter is not hardcoded to zero.

---

### Wed Aug 5 · R4 — 🎯 The Headline Experiment
**Goal:** The two minutes of the demo that make the difference.

**Build:**
- All 60 with `rag.enabled=false`, then all 60 with `true`. Same prompt version, model, fixtures.
- **Consistency:** 3 CVs × 3 runs under each setting. Verdict variance and score standard deviation. RAG is *supposed* to reduce this — that's its entire justification. Check whether it does.
- **Name perturbation (cheap once the harness exists):** one CV, four name variants (Lebanese male, Lebanese female, French male, French female), byte-identical otherwise, 5 runs each. Table of score deltas.
  - *Moves* → real bias found, and redaction is the fix you already shipped.
  - *Doesn't move* → you demonstrated redaction works.
  - **Both are wins.** Neither requires you to have guessed right.
- Write `results/rag-ab.md` and `results/bias.md`.

**Done when:** you can say this with your own numbers — *"RAG off: X% ungrounded. RAG on: Y% ungrounded, but Z% leaked from precedent. Net grounding quality went down, and here's the attribution guard that catches it."*

*If RAG is worse on every axis, that's a **result**, not a failure. Present it with numbers. Most people building these systems never measure it at all.*

---

## WEEK 4 — Auth & Frontend (Aug 6–11)

### Thu Aug 6 · A1 — Spring Security 7 + JWT
**Goal:** Real auth. Hard-timeboxed.

**Learn (4h):** filter chain · `SecurityFilterChain` bean config · `PasswordEncoder` · JWT issue and validate · method security.

**Resources:**
- **[core]** Spring Security reference, *Servlet Applications → Architecture* and *Authentication*: `docs.spring.io/spring-security/reference/` — **official docs, primary source.** Security 7 changed defaults from 6 and most JWT tutorials won't compile.
- **[core]** `github.com/MossaabFrifita/spring-boot-4-security-7-jwt` — working Boot 4 + Security 7 + JWT + PostgreSQL reference. **Read it to see the shape, then close it and write your own.** Check it's still maintained.
- **[ref]** JJWT (`io.jsonwebtoken:jjwt`) README.
- ⚠️ `antMatchers` is gone — it's `requestMatchers`, lambda DSL only. If a tutorial shows `.and()` chaining or `WebSecurityConfigurerAdapter`, it's years stale. Close it.

**Build:**
- `POST /api/auth/register`, `POST /api/auth/login` → JWT. BCrypt hashing.
- Filter chain: `/api/auth/**` open, everything else authenticated.
- Role rules: job writes = RECRUITER only, applications = CANDIDATE only.
- `JwtCurrentUserProvider` — swap the bean, touch nothing else.

**⏱ Hard stop: midday Aug 7.** If auth isn't working, revert to the header stub, list it under known limitations, move on. Auth killing this project is the classic way it fails, and you'd be trading the demo centrepiece for a login form.

---

### Fri Aug 7 · A2 — Auth Matrix + Recruiter Endpoints
**Goal:** Close the IDOR hole. Ship the recruiter backend.

**Resources:**
- **[ref]** Spring Security reference → *Method Security* (`@PreAuthorize`).
- **[skim]** OWASP on Broken Object Level Authorization — 10 minutes, so you can name the vulnerability class in the demo.

**Build:**
- **The IDOR fix:** candidate A fetching candidate B's evaluation returns **403, not 200.** Ownership check in the service layer, not the controller. This is the #1 real vulnerability in apps like this and the single thing Rawad is most likely to actually try.
- Full auth matrix by hand: no token → 401 · expired → 401 · tampered signature → 401 · wrong role → 403 · cross-user resource → 403.
- `GET /api/jobs/{id}/applications` — ranked by `overallScore`, recruiter-only, paginated.
- `GET /api/search?q=...` — recruiter-only semantic search over `CV_CHUNK` vectors, metadata-filtered, returning application + matching chunk + score.

**Done when:** every row of the auth matrix behaves correctly, and search returns sensible results for "machine learning research experience."

**End of day:** Friday note. `Decided:` = three-way attribution.

---

### Sat Aug 8 · A3 — Frontend Foundations + Auth Screens
**Goal:** React talking to the real API. **Your second from-zero day — budget for it.**

**Learn (3h):** Vite project structure · components and props · `useState` / `useEffect` · fetch and async in React · React Router · TanStack Query · CORS.

**Resources:**
- **[core]** `react.dev` → *Quick Start* and *Describing the UI*. You've seen React in the RedReady repo but haven't written it — do the Quick Start properly, it's short.
- **[core]** TanStack Query docs → *Queries* and *Important Defaults*: `tanstack.com/query/latest`
- **[ref]** React Router docs — route config + protected routes.
- **[ref]** Spring Framework reference → *Web → CORS*. Configure once in a `WebMvcConfigurer`, not with scattered `@CrossOrigin`.
- **Standard for all frontend work: plain and working beats pretty and broken.** No design system, no component library. Rawad is evaluating engineering.

**Build:** Vite + React + Router + TanStack Query · auth context, token attached via a fetch interceptor · login and register pages · protected route wrapper · job list + job detail from the real backend.

**Done when:** you register a new candidate in the browser, log in, and see the three real jobs from Postgres.

---

### Sun Aug 9 · A4 — Upload + Status
**Goal:** The full candidate loop in the browser.

**Build:**
- Apply page: file picker, client-side validation (type, size), upload progress.
- Status page: TanStack Query `refetchInterval: 2000`, **stopping at terminal status** — not polling forever.
- Give up after ~2 minutes with a readable timeout state.
- `FAILED` shows the `failureReason`, not a generic error.
- Backend down → readable error, not a white screen.
- Refresh mid-evaluation → state recovers from the server.

**Done when:** upload a CV in the browser, watch PENDING → PROCESSING → COMPLETED, hard-refresh halfway, and the page picks up correctly.

---

### Mon Aug 10 · A5 — ⭐ The Evaluation Screen
**Goal:** The most demoable thing you will build. It gets the whole day.

**Build:**
- Verdict badge + overall score.
- Per-criterion breakdown: name, score /5, reasoning, evidence quote.
- **Click a claim → the exact CV span highlights**, driven by `charStart`/`charEnd`. CV text in a scrollable panel; scroll-to and highlight on click. *Conceptually this is string slicing plus a `<mark>` tag — it is not hard React. Don't let it intimidate you.*
- **Attribution badges, colour-coded:** ✅ CV (verified) · ⚠️ PRECEDENT (leaked from context) · ❌ UNGROUNDED. Visible without hovering.
- Gaps and unsupported-claims lists.

*When Rawad clicks a claim and the exact CV line lights up, the difference between you and a ChatGPT wrapper becomes visible in one second, with no explaining. This screen is worth more demo time than every other screen combined.*

**Done when:** clicking any criterion highlights the correct span, and a fabricated claim is visibly flagged red.

---

### Tue Aug 11 · A6 — Recruiter Dashboard + Precedent Panel
**Goal:** The recruiter side of the product, and making RAG visible.

**Build:**
- **Recruiter dashboard:** post a job · applications ranked by score with verdict badges · click through to the evaluation screen · semantic search box wired to `/api/search`.
- **Precedent panel** on the evaluation screen: *"3 similar candidates informed this evaluation"* — similarity score, job title, verdict, expandable reasoning. Framed as reference, never ground truth, **in the UI text itself.**
- Empty state: *"No sufficiently similar precedents found — evaluated without calibration."* This state matters as much as the populated one.
- Visible `rag.enabled` indicator, so the A/B is demonstrable live.

**Done when:** you log in as a recruiter, post a job, see ranked candidates, click one, and toggle RAG off and resubmit to show two evaluations side by side.

---

## WEEK 5 — Hardening & Demo (Aug 12–15)

### Wed Aug 12 · H1 — Break It + Tests
**Goal:** Find your own bugs before he does. *He told you he'd break it — that's the published rubric.*

**Resources:**
- **[core]** Spring Boot reference → *Testing* — `@WebMvcTest`, `@DataJpaTest`, MockMvc.
- ⚠️ **JUnit 4 is removed in Spring Boot 4.** If you see `@RunWith`, close the tab.

For each item: run it, record actual behaviour, fix, re-run.

**Files:** 0-byte · 100MB · `.exe` renamed `.pdf` · password-protected PDF · **scanned PDF, no text layer** · Arabic/RTL CV · 40-page CV · restaurant menu · filename `../../etc/passwd` · filename `<script>alert(1)</script>`

**LLM:** invalid key → clean failure · 429 → backoff fires · timeout → cap holds · prose instead of JSON → `validateSchema()` retries then fails cleanly · fabricated quote → grounding catches it · same CV twice → same verdict?

**RAG:** empty vector store · nothing above threshold → retrieves nothing, doesn't force k=3 · precedent longer than context · **embedding API down → upload still works, RAG degrades off** · self-retrieval excluded · dimension mismatch.

**Concurrency:** 10 simultaneous uploads · duplicate application · job deleted mid-evaluation · **kill the app mid-evaluation, restart → orphan sweep fails the stuck row.**

**Tests (mock the LLM — never call a paid API from a test suite):** `GroundingChecker` three-way · `CvChunker` (headers present/absent/malformed) · `CvTextExtractor` (every adversarial fixture) · one `@WebMvcTest` upload · one `@DataJpaTest` for the ownership query.

---

### Thu Aug 13 · H2 — 🔒 Freeze + Rehearsal 1
**Goal:** Clean clone → running system. **No new features after today.**

**Build:**
- `docker compose up` from a fresh `git clone` brings up Postgres+pgvector and the app.
- Seed script: users, jobs, backfilled precedent corpus, so the demo has history.
- Frontend build served or documented in two commands.
- **README:** architecture diagram · the four defended decisions · RAG A/B numbers · bias findings · setup · **Known Limitations** (no OCR, synthetic precedent corpus, no multilingual rubric, not calibrated against human reviewers).
- Structured logging with request IDs.

**Then:** full demo run-through, out loud, timed. Expect it to run long — cut, don't rush.

**Done when:** you clone into a brand-new directory on a different machine, run the documented commands, and evaluate a CV end to end.

---

### Fri Aug 14 · H3 — Rehearsals 2 & 3 + Numbers Card
- Rehearse twice more. **Once on a machine that isn't yours**, from a clean clone. This is where you find the hardcoded absolute path.
- **The numbers card**, one page: cost per CV · p50/p95 latency · recall@3 · ungrounded % RAG off vs on · leakage % · verdict variance · name-perturbation max delta · corpus size.
- Prepare answers to: *Why pgvector? Why not an agent? Why not fine-tune? What's your chunking strategy and why? What breaks at 10,000 CVs? What would you do differently? Would you deploy this to make real hiring decisions?*
- Backup: screen recording of the happy path, in case something dies live.
- Final Friday note.
- **Stop by evening.** Don't code the night before. Sleep is worth more than any remaining bug.

*That last question deserves a real answer, and the right one is no — with reasons. Say so.*

---

### Sat Aug 15 · Demo Day

Ten minutes, rehearsed three times, not improvised:

1. **Frame (30s)** — "A CV evaluator where every claim is grounded in a source, and past evaluations calibrate new ones for consistency."
2. **Happy path (90s)** — post job as recruiter, upload CV as candidate, poll, verdict.
3. **Evidence screen (2min)** — click a claim, watch the CV line highlight. **Slow down here.**
4. **RAG, honestly (2min)** — retrieved precedents with similarity scores, then the A/B numbers including what RAG *broke* and the guard that catches it.
5. **Break it yourself (2min)** — scanned PDF, magic-byte rejection, cross-candidate 403. **Beat him to it.**
6. **Bias findings (90s)** — name-swap deltas and what they mean for redaction.
7. **Close on limits (30s)** — known gaps, and what you'd do first in Phase 2.

**Never say "it works."** Say: *here's what it does, here's where it fails, here's what I'd fix first.* Interns who volunteer their system's weaknesses get trusted with real systems.

---

## 7. The Cut Ladder — Date-Triggered, Not Pre-Emptive

Nothing is cut now. But scope is only real if you know what goes when you're behind. **Check yourself against these three dates.**

**If Aug 1 passes without the gate (60 evaluations, no RAG):**
Cut in this order until you're back on track — (1) recruiter semantic search UI, keep the endpoint · (2) register flow, seed users directly · (3) precedent panel expandable detail, show scores only.
Tell Rawad Monday Aug 3.

**If Aug 5 passes without the RAG A/B numbers:**
Ship `rag.enabled=false` by default and **present the negative result with numbers.** That's a legitimate, honest outcome and it demos better than pretending. Don't paper over bad retrieval with prompt tweaks.

**If Aug 10 passes without the evaluation screen working:**
Cut the recruiter dashboard to a plain unstyled list, and cut CSS everywhere to the minimum. The evidence-highlight screen takes priority over everything else in the frontend.

**Never cut, at any point:** the evidence-highlight screen · three-way attribution · the break-it checklist · demo rehearsal. These four are the deliverable.

**Stretch goal, only if genuinely ahead on Aug 11:** bias propagation experiment — seed a deliberately skewed precedent corpus, submit a fresh identical candidate, measure whether the verdict shifts purely because of retrieval content. Costs a day. It's a controlled experiment on the Amazon resume-tool failure mode inside your own system, and nobody else will run it.

---

## 8. Master Resource List

**This is the complete list. Don't add to it** — more courses is the failure mode, not the fix.

### Fundamentals (Jul 22–23)
| Topic | Where |
|---|---|
| HTTP, REST, client/server | MDN Web Docs → `developer.mozilla.org/en-US/docs/Web/HTTP` |
| SQL + relational basics | `postgresqltutorial.com` (Basics only) + hands-on `psql` |
| Modern Java (records, streams, Optional) | `dev.java` or Baeldung |

### Always-current primary sources
| Topic | Where |
|---|---|
| Spring Boot 4 reference | `docs.spring.io/spring-boot/reference/` |
| Spring Framework 7 (Core, Web, Task Execution) | `docs.spring.io/spring-framework/reference/` |
| Spring Data JPA | `docs.spring.io/spring-data/jpa/reference/` |
| Hibernate 7 User Guide | `hibernate.org/orm/documentation/` |
| Spring Security 7 | `docs.spring.io/spring-security/reference/` |
| Spring AI 2.0 | `docs.spring.io/spring-ai/reference/` |
| → Structured Output Converter | `.../api/structured-output-converter.html` |
| → PGvector | `.../api/vectordbs/pgvector.html` |
| → Google GenAI chat / embeddings | `.../api/chat/google-genai-chat.html` · `.../api/embeddings/google-genai-embeddings-text.html` |
| pgvector | `github.com/pgvector/pgvector` |
| PDFBox · POI | `pdfbox.apache.org` · `poi.apache.org` |
| React · TanStack Query · React Router | `react.dev` · `tanstack.com/query/latest` · `reactrouter.com` |

### Dated secondary sources (current as of Jul 2026)
| What | Where | For |
|---|---|---|
| "Self-Correcting Structured Output in Spring AI 2.0" (Jun 2026) | `spring.io/blog/2026/06/23/spring-ai-self-correcting-structured-output/` | Jul 29 |
| Dan Vega — Spring Core videos | YouTube `@DanVega` · `danvega.dev` | Jul 24 |
| "Spring Boot 4 & Framework 7 – What's New" (Mar 2026) | `baeldung.com/spring-boot-4-spring-framework-7` | Jul 24, 10 min |
| "Spring AI + Gemini: First API Call" (May 2026) | `ravibuilds.dev/blog/spring-ai-gemini-first-api-call` | Jul 28 |
| Boot 4 + Security 7 + JWT reference repo | `github.com/MossaabFrifita/spring-boot-4-security-7-jwt` | Aug 6 |
| Ed Donner LLM Engineering, **Week 5 only** | Udemy Business | Aug 2 |

### Explicitly excluded
❌ Spring Academy REST API course · ❌ Chad Darby's Udemy course (good, but 40 hours you don't have) · ❌ **every Spring AI Udemy course** (all 1.x, no `validateSchema()`) · ❌ any JWT tutorial not dated after Feb 2026 · ❌ any JUnit 4 material · ❌ Ed Donner weeks 1–4 and 6–8, for now.

### Version gotchas — memorise these
- Spring Boot **4.1** is the target (4.0 Nov 2025, 4.1 Jun 2026).
- Hibernate **7** / JPA **3.2**. Hibernate 5 material will mislead you.
- **JUnit 4 removed.** `@RunWith` = stale tutorial.
- **Jackson 3** replaced Jackson 2.
- Security: `requestMatchers`, not `antMatchers`. No `WebSecurityConfigurerAdapter`.
- Spring AI: `initialize-schema=true` is opt-in for PgVectorStore.
- Gemini: set **only** `api-key`. Any `project-id`/`location` flips to Vertex AI mode.
- pgvector: **HNSW**, not IVFFlat (2000-dim cap).

---

## 9. Rules for the Next 24 Days

1. **Every day ends with the "Done when" test executed.** Not "mostly working." Run it. If it fails, the day rolls over and you consult §7 — deliberately, not by accident.
2. **20-minute explain-back daily.** No notes, no looking at the code. This is the course replacement, and it's also your early warning that you're going too fast.
3. **Week 1: show me your work every ~90 minutes**, not at end of day.
4. **You write every line.** I explain, review, debug. If I ever hand you a block to paste, ask me to explain it instead.
5. **`show-sql=true` stays on all of week 1.** Read the SQL Hibernate generates. It's the difference between using an ORM and understanding one.
6. **Timeboxes are real.** If a block runs 50% over, stop and message me. Twenty minutes of unsticking beats four hours of grinding.
7. **Nothing enters scope that isn't in this file.** No agents, no reranker, no fine-tuning, no Kafka, no Redis, no OCR.
8. **Friday note to Rawad every Friday**, five lines: Jul 24, Jul 31, Aug 7, Aug 14.

---

*The AI is the product. Spring is the delivery mechanism. RAG is the brief. Be the only person in the room who measured what it broke.*
