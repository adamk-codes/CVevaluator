# CV Evaluation Platform — Full Execution Roadmap (v2)
### Apliman Internship, Phase 1 (Month 1)
*Rebuilt around RAG as a core requirement, not an add-on.*

---

## 0. Read This First

**What this actually is.** Not a web app with AI bolted on. An **AI system with a web application wrapped around it**. Rawad asked for RAG, which means retrieval, grounding, and evaluation quality are the deliverable. Spring Boot and React are the delivery mechanism.

**Why he asked for RAG.** Almost certainly not because CV evaluation needs it. aïda is a conversational AI platform — retrieval is core to Apliman's real stack. Phase 1 is a proxy. He's testing whether you can build the primitives the actual product runs on, in a throwaway domain. **This tells you where to invest: the parts that transfer to aïda.** Chunking, embedding, retrieval, grounding, citation, structured output, async orchestration, cost control. Those are the same primitives either way.

**Three principles that govern everything below:**

1. **Vertical slice before depth.** Upload a PDF → hardcoded verdict → visible in a browser, by **Day 7**. Then thicken. An intern with an ugly working system on Day 7 beats one with beautiful auth and no product on Day 25.

2. **RAG is additive, never blocking.** Ship the evaluation pipeline *without* retrieval first (Day 13 gate). Then layer RAG on behind a feature flag. If retrieval turns out badly, you still have a working system to demo. If you build them entangled and retrieval breaks, you have nothing.

3. **"He will break your code" is the published rubric.** He's telling you the evaluation is on robustness, not feature count. Section 9 isn't polish — it's the deliverable.

**Ask about spec. Decide about design.** "What does good feedback look like?" is spec — ambiguity costs you a month, so ask. "What should I chunk on?" is design latitude — decide it, then state the decision in your Friday note. Asking permission for design decisions hands back the exact thing he's evaluating.

---

## 1. Skill Gap

| Area | Your level | Time to close |
|---|---|---|
| Java language | Coursework (AUB) — needs modern-Java refresher | 1 day |
| Maven | Zero — it's `requirements.txt` in XML | 2 hrs |
| **Spring Core (DI/IoC/beans)** | Zero — **the concept that unlocks everything** | 1–2 days |
| Spring MVC / REST | Strong conceptually (FastAPI) — syntax mapping only | 1 day |
| **Spring Data JPA / Hibernate** | **Zero. Your biggest gap.** | 3 days |
| Spring Security 7 + JWT | Zero. Notoriously unfriendly | 2 days |
| PostgreSQL | Some coursework | 0.5 day |
| React | Real (Next.js/RedReady) — just Vite + Router | 1 day |
| PDF/DOCX extraction (Java) | Zero, but PDFBox is simple | 0.5 day |
| **LLM integration** | **Strong** — learn Spring AI idioms, not concepts | 1 day |
| **RAG / embeddings / chunking** | **Strong conceptually** (MineGuard, Ed Donner Wk5) | 1 day (Java idioms) |
| pgvector | Likely zero — but it's just SQL + an index | 0.5 day |
| Async orchestration | SSE from MineGuard | 0.5 day |
| Docker | **Strong** | — |
| JUnit/Mockito | Unknown | 1 day |

**The hardest thing here is JPA/Hibernate — not Spring, not Security, not RAG.** Coming from Python you'll expect a Django-style ORM and get a persistence-context model with managed vs. detached entities and `LazyInitializationException`. Expect two confusing days. That confusion is normal and it passes.

**The RAG is the easy part for you.** That's the whole asymmetry to exploit: the other intern will struggle with retrieval and coast on CRUD. Invert it.

**Skip entirely:** Thymeleaf, server-side MVC, Spring Cloud, microservices, Kafka, WebFlux/reactive, AWS deploy, GraalVM. Every course teaches these. Every one is a trap at 4 weeks.

---

## 2. Architecture

```
┌──────────────────────────────────────────────────┐
│  React SPA (Vite)                                │
│   Candidate: browse jobs, upload CV, poll status │
│   Recruiter: post jobs, ranked apps, semantic    │
│              search, evidence highlighting       │
└────────────────┬─────────────────────────────────┘
                 │ REST/JSON + JWT
┌────────────────▼─────────────────────────────────┐
│  Spring Boot 4.1                                 │
│  ┌────────────────────────────────────────────┐  │
│  │ Controllers                                │  │
│  │  /api/auth  /api/jobs                      │  │
│  │  /api/applications  /api/evaluations       │  │
│  │  /api/search  (recruiter semantic search)  │  │
│  └───────────────┬────────────────────────────┘  │
│  ┌───────────────▼────────────────────────────┐  │
│  │ Ingestion         │ Evaluation             │  │
│  │  CvTextExtractor  │  PrecedentRetriever ◄──┼──┼─ RAG
│  │  PiiRedactor      │  PromptAssembler       │  │
│  │  CvChunker        │  LlmEvaluator          │  │
│  │  EmbeddingService │  GroundingChecker      │  │
│  │                   │  EvaluationService     │  │
│  └───────────────┬────────────────────────────┘  │
│  ┌───────────────▼────────────────────────────┐  │
│  │ Spring Data JPA  +  Spring AI VectorStore  │  │
│  └───────────────┬────────────────────────────┘  │
└──────────────────┼───────────────────────────────┘
                   │
    ┌──────────────▼──────────────┐   ┌─────────────┐
    │  PostgreSQL + pgvector      │   │ Gemini      │
    │  (relational + embeddings,  │   │ (chat +     │
    │   one database)             │   │  embeddings)│
    └─────────────────────────────┘   └─────────────┘
```

**Two decisions worth defending in the demo:**

**pgvector, not a separate vector DB.** Your embeddings live in the Postgres you already run. Swap the Compose image to `pgvector/pgvector:pg17`, add the extension, done. Spring AI ships `PgVectorStore` out of the box. Pinecone or Qdrant would be a second service, a second failure mode, and a second thing to explain — for a corpus of a few hundred chunks. *"It didn't earn its complexity at this scale"* is the right answer and it's a senior one.

**Evaluation is asynchronous.** LLM + retrieval is 5–25s. Block the request thread and two concurrent uploads kill you — Rawad will find that in ten seconds.
- `POST /api/applications` → store, extract, persist `status=PENDING`, return **202 + id** immediately
- `@Async` worker → `PROCESSING` → retrieve → evaluate → write `Evaluation` → `COMPLETED`/`FAILED`
- `GET /api/applications/{id}` → frontend polls every 2s to terminal state

No Kafka, no RabbitMQ. `@Async` + a status column is correct here.

---

## 3. Data Model

```
User          id, email(unique), passwordHash, role[CANDIDATE|RECRUITER], createdAt

Job           id, title, description, requirements, seniority,
              createdBy→User, createdAt, active

Application   id, job→Job, candidate→User,
              originalFilename, contentType, sizeBytes, storagePath,
              extractedText, redactedText, extractionMethod, textLength,
              status[PENDING|PROCESSING|COMPLETED|FAILED],
              failureReason, submittedAt

CvChunk       id, application→Application,
              chunkIndex, sectionType[EXPERIENCE|EDUCATION|SKILLS|OTHER],
              content, embedding VECTOR(768), charStart, charEnd
              ← charStart/charEnd let you highlight the exact source span in the UI

Evaluation    id, application→Application (1:1),
              verdict[STRONG_FIT|POSSIBLE_FIT|NOT_A_FIT],
              overallScore(0-100), summary,
              modelName, promptVersion, embeddingModel,
              ragEnabled(bool), latencyMs, tokensIn, tokensOut,
              rawResponse, createdAt

CriterionScore id, evaluation→Evaluation,
              criterionName, score(0-5), weight, reasoning,
              evidenceQuote,           ← verbatim span
              evidenceSource[CV|PRECEDENT|UNGROUNDED],  ← WHICH document
              evidenceVerified(bool),
              sourceChunk→CvChunk (nullable)

RetrievedPrecedent  id, evaluation→Evaluation,
              precedentEvaluation→Evaluation, similarityScore, rank
              ← full audit trail: which past evaluations influenced this one
```

**`evidenceSource` is the most important column in this schema**, and it exists *because* of RAG. Without retrieval, a claim is either in the CV or hallucinated. With retrieval, there's a third case: the claim is true — of *a different candidate's* CV that got pulled into context. That's the failure mode RAG introduces, and tracking it per-claim is what separates an engineered system from a prompt.

`RetrievedPrecedent` gives you a full audit trail. When Rawad asks "why did it say that?", you can show which past evaluations were in context and how similar they were. Interns who can't reproduce their own outputs look like they got lucky.

---

## 4. The AI Layer

### 4.1 Use Spring AI. Do not hand-roll HTTP.

You wrote raw `urllib.request` against Gemini in MineGuard. **Don't do the Java equivalent.**

```java
EvaluationResult result = chatClient.prompt()
    .system(rubricSystemPrompt)
    .user(u -> u.text(evaluationTemplate)
                .param("jobDescription", job.getDescription())
                .param("cvText", application.getRedactedText())
                .param("precedents", formattedPrecedents))
    .call()
    .entity(EvaluationResult.class, spec -> spec.validateSchema());
```

`.entity()` generates a JSON schema from your record, injects it, deserializes the reply. `validateSchema()` (Spring AI 2.0) detects malformed output and **retries automatically** — the self-correction loop you'd otherwise hand-write.

**Gemini connection:** `spring-ai-starter-model-openai` with `spring.ai.openai.base-url` pointed at Gemini's OpenAI-compatible endpoint — the same trick you're already running in the Media Bias Comparator. Zero new concepts.

> ⚠️ **Assumption to verify on Day 8, not Day 20:** that the Gemini OpenAI-compat endpoint serves *embeddings*, not just chat. If it doesn't, fall back to Spring AI's `TransformersEmbeddingModel` (`all-MiniLM-L6-v2`, runs locally, no API, no cost, 384-dim). Test this in the first hour of the AI phase — it's a 30-minute check that could otherwise cost you three days.

### 4.2 Rubric-first, never vibes

```java
public record CriterionAssessment(
    String criterionName,
    int score,                 // 0-5
    String reasoning,
    String evidenceQuote,      // MUST be verbatim
    String evidenceSource      // "CV" | "PRECEDENT" | "NONE"
) {}

public record EvaluationResult(
    String verdict,
    int overallScore,
    String summary,
    List<CriterionAssessment> criteria,
    List<String> gaps,
    List<String> unsupportedClaims
) {}
```

Criteria derived from the job posting: technical skill match, relevant experience depth, domain relevance, education fit, evidence of impact/ownership, communication quality.

### 4.3 The RAG layer — precedent retrieval

**The brief:** retrieve past evaluations as calibration anchors, so evaluations are *consistent* across candidates rather than each one being a fresh coin-flip.

**Cold start.** You have no history. Solve it with your fixture set: 20 CVs × 3 jobs = 60 evaluations, generated in the non-RAG phase (Days 8–13). **That's not a workaround — that's the design.** Seeding a precedent corpus is a real production problem and you're solving it the way production does. Say that in the demo.

**Chunking.** CVs are semi-structured. Chunk by **section** (Experience / Education / Skills / Projects), not by fixed token windows. A 512-token window slicing through the middle of a job entry destroys the thing you're trying to match. Store `charStart`/`charEnd` per chunk so the frontend can highlight the exact source span. Fixed-size chunking here is the single most common way people make retrieval quietly bad.

**What gets embedded.** For precedent retrieval, embed the *(job requirements + CV summary)* pair, not the raw CV. You're asking "have we seen a similar candidate for a similar role?" — not "which CVs share vocabulary?"

**Retrieval.** For a new (CV, Job): retrieve top-k (start k=3) most similar past evaluations. Filter by similarity threshold — **if nothing clears the bar, retrieve nothing and say so.** Injecting irrelevant precedent is worse than injecting none, and confidently retrieving garbage is what makes RAG systems fail invisibly.

**Prompt assembly — this is where it gets dangerous:**

```
[SYSTEM]  Rubric + scoring guide.
          Evidence for every claim must be a verbatim quote from the
          CANDIDATE CV section. Never cite precedent text as evidence
          about this candidate. If precedent and CV conflict, the CV wins.

[CONTEXT] === CALIBRATION PRECEDENTS (reference only) ===
          Similar candidates previously evaluated for similar roles.
          Use for consistency of standards. These are NOT this candidate.
          NOT ground truth — they may contain errors.
          <precedent 1: job, cv summary, verdict, reasoning, similarity>
          ...

[USER]    === JOB ===            <requirements>
          === CANDIDATE CV ===   <redacted CV text>
```

**The engineering problem RAG creates.** Once precedent CVs are in context, the model *will* conflate them. It will write "strong Kubernetes experience" about a candidate whose CV never mentions Kubernetes — because a retrieved precedent did. This is invisible in a happy-path demo and catastrophic in a hiring tool.

**The fix is mechanical, and it's your headline result:**

```java
String norm = normalize(cvText);
if (norm.contains(normalize(quote)))          → CV          (verified)
else if (precedentTexts.stream()
           .anyMatch(p -> normalize(p).contains(normalize(quote))))
                                              → PRECEDENT   (LEAKAGE — flag it)
else                                          → UNGROUNDED  (hallucination)
```

(Normalize case + whitespace; allow ~90% fuzzy match to tolerate reformatting.)

Now you have **two numbers, not vibes**: a hallucination rate and a **context-leakage rate**. Then run the experiment that makes the point: *"RAG off: 12% ungrounded. RAG on: 8% ungrounded, but 6% leaked from precedent. Net grounding quality went **down**, and here's the attribution guard that fixes it."* That is an engineering result. It's also a real finding about RAG that most people building these systems never measure.

**Retrieval evaluation.** Build 10 hand-labeled query→relevant-precedent pairs. Measure recall@3. If retrieval is bad, everything downstream is bad and no prompt fixes it. Most people never check this. Check it.

### 4.4 Bias — build it AND instrument it

Automated CV screening is *the* canonical algorithmic-bias case, and **precedent-RAG is literally the Amazon resume-tool failure mode**: past decisions become training signal, bias compounds. You're building it deliberately, which means you get to measure it.

**Ship as features:**
1. **`PiiRedactor` — pre-LLM and pre-embedding.** Strip name, email, phone, address, nationality, DOB, gender markers. The model scores skills, not identity. **Redact before embedding too** — otherwise names leak into the vector space and your retrieval itself becomes biased. That subtlety is worth stating out loud in the demo; almost nobody catches it.
2. **Precedent framed as reference, never ground truth.** In the prompt and in the UI.

**Present as findings:**
3. **Consistency.** Same CV, same job, 3 runs. Same verdict? Report variance. RAG should *reduce* it — that's the entire justification for the feature. Measure whether it actually does.
4. **Name perturbation.** One CV, name swapped across 4 variants (Lebanese/French, male/female), byte-identical otherwise, 5 runs each. Does the score move? Moves → you found real bias and redaction is the fix. Doesn't move → you *demonstrated* redaction works. **Both outcomes are wins. Neither requires you to be right.**
5. **Bias propagation (the one nobody else will run).** Seed the precedent corpus with a deliberately skewed set — e.g. all NOT_A_FIT verdicts for one profile. Does a fresh, identical candidate now get scored lower purely because of what's in retrieval? **That's a controlled experiment on the Amazon failure mode, in your own system.** If it propagates: that's your argument for precedent-quality gating. If it doesn't: that's your argument that the attribution guard holds.

That last one is your research lane meeting the internship brief exactly. It costs about a day. Do it.

### 4.5 Failure handling — non-negotiable

- Hard timeout (~30s). Never let a hung call hold a thread.
- Exponential backoff on 429/5xx, cap 3.
- Malformed output → `validateSchema()` first, then catch → `status=FAILED` + readable reason. **No stack trace ever reaches the frontend.**
- Token limits: a 40-page CV blows context. Truncate with an explicit strategy and *tell the user you truncated*.
- Embedding failure → application still saves, RAG degrades off. **Retrieval down must not mean upload down.**
- Log tokens + cost per evaluation. When he asks "what does this cost per CV?", have the number.

---

## 5. Execution Plan — 4 Weeks

Each block pairs **build** with **learn exactly what you need, right before you need it**. Don't read ahead.

### Days 1–3 — Walking Skeleton
**Learn (~6 hrs, then stop):** Modern Java (records, streams, `Optional`, try-with-resources) 2h · Maven 1h · **Spring Core: DI, IoC, `@Component`/`@Service`/`@Repository`, constructor injection** 3h. *Don't skim Spring Core — it's the concept that makes everything else legible. Mental model: FastAPI's `Depends()` applied to the whole app.*

**Build:** IntelliJ Community + JDK 21 · `start.spring.io` → Spring Boot **4.1.x**, Java 21, Maven; deps: Web, Data JPA, PostgreSQL, Validation, Lombok, Security, Actuator · `docker-compose.yml` with **`pgvector/pgvector:pg17`** (use it from day one — no migration later) · `GET /api/health` · one `Job` entity + repository, save and fetch a row.

> **Day 3 gate:** HTTP → controller → service → repository → Postgres → JSON. If that round-trips, you understand Spring. If not, stop. Nothing later works without it.

### Days 4–7 — Backend Core
**Learn:** Spring MVC (`@RestController`, `@RequestBody`, `ResponseEntity`) 1h · **JPA/Hibernate (`@Entity`, `@ManyToOne`, fetch types, cascade, `@Transactional`) 4–6h — the hard block** · Bean Validation 30m · `@RestControllerAdvice` 1h · **Spring Security 7 + JWT 4–6h** · Multipart upload 30m · PDFBox + POI 1h.

> ⚠️ **Security 7 changed defaults from Security 6. Most JWT tutorials online are Security 6 and won't compile.** Use official docs when a tutorial fights you.

**Build:** Register/login → JWT, roles CANDIDATE/RECRUITER · Job CRUD (recruiter-only writes) · `POST /api/applications` multipart · `CvTextExtractor` (PDFBox / POI, reject everything else) · `PiiRedactor` · global exception handler → consistent error DTO, **zero stack traces to client from day one**.

> **Day 7 gate:** upload a real PDF via Postman, see redacted text in the DB.

### Days 8–13 — Evaluation Pipeline (no RAG yet)
**Learn:** Spring AI 2.0 — `ChatClient`, `.entity()`, `validateSchema()`, prompt templates 3h · Spring `@Async` (`@EnableAsync`, `TaskExecutor` sizing) 1.5h · Spring Retry 1h.

**Build:** Wire Spring AI → Gemini · **Hour one: verify the embeddings endpoint works** (§4.1 warning) · `EvaluationResult`/`CriterionAssessment` records · versioned rubric prompt (bump `promptVersion` on every change) · `LlmEvaluator` with retry/timeout/token logging · `GroundingChecker` (CV-only for now) · `EvaluationService` `@Async` orchestration · `GET /api/applications/{id}` · **build the 20-CV × 3-job fixture set and run it** → this both tests you and *seeds your precedent corpus*.

> **Day 13 gate — the one that matters.** `curl` a CV → 202 → poll → full structured evaluation with verified evidence. **A complete, demoable system with zero RAG.** If you're behind, tell Rawad *now*, on Day 12 — not Day 25.

### Days 14–18 — RAG Layer
**Learn:** Ed Donner LLM Engineering **Week 5 (RAG)** — pull it forward, out of order, ~4h at 1.75× (concepts only; his LangChain code doesn't transfer to Java) · Spring AI `VectorStore` + `PgVectorStore` 2h · pgvector indexing (HNSW vs IVFFlat) 1h.

**Build:** `CvChunker` (section-based, with `charStart`/`charEnd`) · `EmbeddingService` (redact → embed → store) · backfill embeddings for the 60 fixtures · `PrecedentRetriever` (top-k + similarity threshold + empty-result path) · `PromptAssembler` with the source-separated template · **extend `GroundingChecker` to three-way attribution: CV / PRECEDENT / UNGROUNDED** · `RetrievedPrecedent` audit rows · `/api/search` recruiter semantic search · **feature-flag RAG on/off** (`rag.enabled`) — this is what makes §4.3's A/B measurable, and what saves you if retrieval goes sideways · measure recall@3 on 10 labeled pairs.

> **Day 18 gate:** RAG on vs. off, both work, and you have the numbers.

### Days 19–24 — Frontend
**Learn:** Vite + React 1h · React Router 1h · TanStack Query for polling (`refetchInterval` to terminal status) 2h · CORS in Spring 30m.

**Build:** Login/register · job list + detail · apply with client-side validation + upload progress · status page (PENDING→PROCESSING→COMPLETED, real spinner, **timeout state**) · **the evaluation screen** — verdict badge, score, per-criterion breakdown, each claim's evidence rendered **with the CV span highlighted via `charStart`/`charEnd`**, and unverified/leaked claims visibly flagged · precedent panel: "3 similar candidates informed this evaluation" with similarity scores · recruiter dashboard: ranked applications + semantic search.

*The evidence-highlight screen is the most demoable thing you will build. When he clicks a claim and the exact CV line lights up, the difference between you and a ChatGPT wrapper becomes visible in one second, with no explaining.*

### Days 25–28 — Hardening & Demo
**Learn:** JUnit 5 + Mockito 2h · `@WebMvcTest`, `@DataJpaTest`, MockMvc 2h. (**JUnit 4 is removed in Spring Boot 4** — ignore any JUnit 4 tutorial.)

**Build:** the entire §9 checklist · tests: `GroundingChecker` (pure logic, high value), `CvChunker`, `CvTextExtractor` (feed it garbage), one MockMvc upload test — **mock the LLM; never call a paid API from a test suite** · structured logging + request IDs · `docker-compose up` from a clean clone · README with architecture, decisions, **known limitations** · rehearse the demo 3× out loud on a machine that isn't yours.

**Days 27–28 are slack. You will need them.**

---

## 6. Learning Resources

> ⚠️ **Spring Boot 4 / Spring 7 / Security 7 shipped Nov 2025. Anything older than ~Feb 2026 teaches Boot 3 and will break on you** — Security defaults changed, Jackson 3 replaced Jackson 2, JUnit 4 removed, Undertow gone. Check "last updated" on everything.

**Spring — pick one:**
- **Chad Darby, "Spring Boot 4, Spring 7 & Hibernate for Beginners" (Udemy).** Updated 5/2026 for Boot 4. Watch at 1.75×, **skip Thymeleaf, MVC forms, and AOP entirely.** You need ~the first 40%.
- **Spring Academy (spring.io) — free, official, always current.** Faster, less hand-holding. Given your level this may suit you better than Darby, who assumes less than you know.

**RAG:** **Ed Donner LLM Engineering, Week 5** — pull it forward now. Concepts transfer completely; the LangChain/Chroma code doesn't (you're in Java). Take the chunking and retrieval-evaluation instincts, ignore the scaffolding.

**Spring AI:** **the reference docs, not a course.** `docs.spring.io/spring-ai/reference` — read Structured Output Converter and VectorStore properly. Short, current, and the only thing on Spring AI 2.0. Every Spring AI Udemy course is on 1.x and won't have `validateSchema()`.

**Reference:** Baeldung (check dates) · official Spring docs — non-negotiable for Security 7 and Spring AI 2.0 · pgvector README · react.dev (Vite quickstart only).

**That's the entire reading list. Don't add to it.** You have four weeks, an unfamiliar stack, and Ed Donner's course is 8 weeks on its own. More courses is the failure mode, not the fix — the calendar fills with watching instead of building.

---

## 7. On Python vs. Spring AI

Python stays primary and it isn't close. Research, training, fine-tuning, anything with a paper attached — Python, permanently.

But it isn't either/or. Spring AI exists because enterprises have ten million lines of Java and want LLM features without a rewrite. Not a fad — same reason .NET has Semantic Kernel. Banks, telcos, insurers. In Lebanon and the Gulf that's a large share of the real market, and **Apliman is that market.**

Python is your core competency. Spring AI is market access. "I can call an LLM API in Python" is now commodity. "ML depth *and* I can ship LLM features into enterprise Java" is rare, well-paid, and uncrowded.

**What actually compounds is neither library.** It's the systems discipline: structured output, grounding, attribution, retrieval evaluation, async orchestration, cost control. Framework-agnostic, and still valuable in ten years. Spring AI is syntax you'll forget. Learn it instrumentally; don't get precious about it.

---

## 8. Scope Discipline — What NOT to Build

- ❌ Microservices — it's one app
- ❌ Kafka/RabbitMQ — `@Async` is correct at this scale
- ❌ A dedicated vector DB (Pinecone/Qdrant/Weaviate) — **pgvector, defend it**
- ❌ Agent frameworks / multi-agent / LangGraph-style orchestration — **the most tempting trap now that RAG is in scope.** Retrieve → evaluate is one deterministic pipeline. It does not need an agent.
- ❌ Fine-tuning / a trained classifier — **you have no labeled hiring outcomes.** Reaching for supervised ML here is resume-driven development. It looks impressive only to people who aren't senior.
- ❌ A reranker model — measure recall@3 first. Add one only if the number says so.
- ❌ Redis, Kubernetes, OAuth, email, CI/CD, a design system
- ❌ OCR for scanned PDFs — **detect and reject; name it as a known limitation.** Tesseract is a week you don't have.

Each is a plausible-sounding way to fail Phase 1. Scope discipline is itself evaluated. *"It didn't earn its complexity at this scale"* beats having built it.

---

## 9. The Break-It Checklist

He told you he'd break it. Break it first.

**Files:** 0-byte · 100MB · `.exe` renamed `.pdf` (**validate magic bytes, not extension**) · password-protected PDF · **scanned PDF, no text layer** (*extracts zero text — do you reject it, or send an empty prompt and get a confident hallucinated evaluation of nothing? Best trap on this list*) · Arabic/RTL CV · 40-page CV · a restaurant menu instead of a CV · filename `../../etc/passwd` · filename `<script>`

**LLM:** invalid key → clean failure · 429 → backoff · timeout → hard cap · prose instead of JSON · missing required field · fabricated `evidenceQuote` → **grounding catches it** · same CV twice → same verdict?

**RAG:** empty vector store (**first-ever candidate — does it crash or degrade?**) · nothing above threshold → retrieve nothing, don't force k=3 · precedent longer than context · embedding API down → **upload must still work** · precedent text quoted as candidate evidence → **leakage detector catches it** · self-retrieval (does a CV retrieve *itself* as its own precedent? **exclude by application id** — this one bites everyone once) · corrupt/dimension-mismatched embedding

**Auth:** Candidate A fetches Candidate B's evaluation → **403, not 200** (IDOR — the #1 real vulnerability in apps like this, and the thing a supervisor is most likely to actually try) · candidate hits recruiter endpoints → 403 · expired JWT → 401 · tampered signature → 401 · no token → 401

**Data/concurrency:** SQL injection in job description (JPA parameterizes — verify, don't assume) · 10 simultaneous uploads (thread pool exhaustion) · duplicate application · job deleted mid-evaluation · **app restarts mid-evaluation → does the row sit in `PROCESSING` forever?** Add a startup sweep that fails orphaned rows. Five lines, almost nobody thinks of it, reads as maturity.

**Frontend:** backend down → readable error, not a white screen · `FAILED` → UI says why · polls forever → gives up after ~2min · refresh mid-evaluation → state recovers from server

---

## 10. Demo Script

Ten minutes, rehearsed three times, not improvised.

1. **Frame (30s).** "A CV evaluator where every claim is grounded in a source, and past evaluations calibrate new ones for consistency."
2. **Happy path (90s).** Post job, upload CV, poll, verdict.
3. **Evidence screen (2min).** Click a claim → exact CV line highlights. *Slow down. This is the moment.*
4. **RAG, shown honestly (2min).** Show the 3 retrieved precedents and their similarity scores. Then the A/B: *"RAG off: verdict variance X. RAG on: variance Y — consistency improved. But ungrounded claims went from 12% to 8% while 6% leaked from precedent context. Here's the attribution guard."* **Showing that RAG introduced a problem you then measured and fixed is worth more than showing RAG working.**
5. **Break it yourself (2min).** Scanned PDF → clean rejection. `.exe` renamed → magic-byte rejection. Log in as another candidate, fetch this evaluation → 403. **Beat him to it. Interns who pre-empt the attack look senior.**
6. **Bias finding (90s).** Name-swap deltas. Then the propagation experiment: *"I seeded precedent with a skewed set — here's whether bias transferred, and what that means for gating precedent quality."*
7. **Close on limits (30s).** "Known gaps: no OCR, no multilingual rubric, not calibrated against human reviewers, precedent corpus is synthetic. Given phase 2, I'd start with X."

**Never say "it works."** Say "here's what it does, here's where it fails, here's what I'd fix first." Interns who volunteer their system's weaknesses get trusted with real systems.

---

## 11. Weekly Discipline

**Every Friday, five lines to Rawad, asked for or not:**

```
Week N
Shipped:  <what works now>
Blocked:  <what's stuck / what I need>
Next:     <top 3>
Decided:  <a design call you made and why>   ← RAG scope, chunking, pgvector
Question: <one specific thing you want his input on>
```

Ten minutes a week. It's the difference between "competent" and "quiet" when he decides Phase 2 — and it manufactures the sprint structure nobody gave you without asking him to run his team differently. The `Decided:` line is how you exercise design latitude without silently building the wrong thing for three weeks.

**Ask in week one, in one conversation:**
1. What does Phase 2 depend on — and can both interns advance, or is it one slot?
2. **Show me a "detailed feedback" output you'd call good, and one you'd call bad.** *(Highest-risk ambiguity in the project — you can build the wrong thing competently for a month.)*
3. Which LLM, whose API key, and does CV data have to stay internal? *(Data-residency rules change your architecture on Day 8, not Day 20.)*
4. Existing Apliman repo or Spring conventions to follow?
5. What's the team building on aïda right now — and is it retrieval-based? *(Tells you which parts of this transfer.)*

---

## 12. Red Flags — Escalate, Don't Absorb

- **Day 13 gate missed (evaluation not end-to-end).** Say so Day 12. **Cut RAG before you cut the working pipeline** — Rawad would rather see a solid system minus a feature than a broken system with everything half-wired.
- **Security eats >2 days.** Hardcode auth temporarily, move on, return Day 25. Don't let auth kill the project — that's the classic way this fails.
- **JPA blocks you 2+ days.** Drop to `JdbcTemplate` for the stuck piece, come back. Shipping beats elegance.
- **Retrieval quality is bad (recall@3 poor).** Don't paper over it with prompt tweaks. Fix chunking first. If it's still bad by Day 18, ship RAG off by default and **present the negative result with numbers** — that's a legitimate, honest outcome and it demos better than pretending.
- **Building things not on this list.** Stop. Reread §8.

---

*The AI is the product. Spring is the delivery mechanism. RAG is the brief — build it, then be the only person in the room who measured what it broke.*
