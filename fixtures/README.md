# Fixtures

Test corpus for CVEvaluator: 3 jobs, 20 synthetic CVs, 6 adversarial files.

Everything here is synthetic. No real candidate data, no scraped CVs. The
names are invented, the addresses are `@example.com`, and the phone numbers
are Lebanese-shaped so the redactor has something real to bite on.

```
fixtures/
  jobs/          3 job postings, shaped as CreateJobRequest
  cvs/           20 CVs: 9 pdf, 6 docx, 5 txt
  adversarial/   6 files that are meant to go wrong
  content/       plain-text sources every binary is built from
  tools/         the generator, the verifier, and generate.cmd
  manifest.json  expected outcome for all 60 CV/job pairs
```

## What the corpus is for

Two different things, and they fail in different places:

- **`cvs/`** exercises extraction, redaction, and the rubric. These all
  extract cleanly. Anything that goes wrong with them is a grading problem.
- **`adversarial/`** exercises the boundaries. Two are rejected at upload, one
  fails extraction, and three extract fine and are somebody else's problem.

## Jobs

| id | title | shape |
|----|-------|-------|
| `job-01-backend-engineer` | Senior Backend Engineer (Java / Spring Boot) | deep single-stack technical |
| `job-02-ml-engineer` | Machine Learning Engineer (NLP / Retrieval) | Python and modelling |
| `job-03-restaurant-manager` | Restaurant General Manager | non-technical |

The three are far enough apart that `NOT_A_FIT` is unarguable for most
cross-pairs — a backend CV against the restaurant job shares no vocabulary at
all. Jobs 1 and 2 are the interesting pair: both say "engineer", both want
PostgreSQL and Kubernetes, and neither transfers.

`POST /api/jobs` takes these files as-is:

```bash
curl -X POST http://localhost:8080/api/jobs -H 'Content-Type: application/json' -H 'X-User-Id: 1' -d @fixtures/jobs/job-01-backend-engineer.json
```

## CVs

Twenty CVs, split 5 strong / 8 borderline / 7 weak against each one's primary
job. Formats and layouts are mixed deliberately: 9 PDF across three visual
themes, 6 DOCX (one with skills in a real Word table, so POI's table path gets
exercised and not just its paragraph path), 5 TXT. Seven have no section
headers at all and read as continuous prose.

Full expected outcomes, including every CV against every job, are in
[`manifest.json`](manifest.json). The ones worth knowing about:

- **cv-04 vs cv-03** — the same calibre of ML candidate, one in clean
  `EXPERIENCE`/`EDUCATION` sections, one in unbroken prose with no headings.
  If they score differently, the grader is reading formatting.
- **cv-18** — a genuinely excellent backend engineer applied to the restaurant
  job. `STRONG` against job-01, `NOT_A_FIT` against job-03. Any grader scoring
  the CV instead of the pair gets exactly one of those wrong.
- **cv-07** — eight years of the right engineering on .NET instead of Java.
  Also the fixture that proves `PiiRedactor` leaves `.NET Core` and `ASP.NET`
  intact rather than eating them as bare domains.
- **cv-13, cv-08, cv-06** — each names its own gap in plain language
  ("I have never designed a training run"). If the assessment claims a strength
  the CV explicitly disclaims, that is a grounding failure with a quotable
  counter-example sitting right there.
- **cv-17** — a structural engineer. Says "engineer" throughout and mentions
  Python once.

Every CV carries an email and a Lebanese phone number in one of the formats
`PiiRedactor` handles (`03 447 812`, `71 209 334`, `+961 3 992 447`), and
several carry `01/2019 - 06/2023` date ranges, which is the case the phone
pattern deliberately does not match.

## Adversarial files

| file | fails where | expected |
|------|-------------|----------|
| `adv-01-scanned-no-text-layer.pdf` | extraction | `FAILED` — 0 visible chars, below `minimum-text-length`. No OCR is a decision, not a gap. |
| `adv-02-forty-page-cv.pdf` | nothing yet | `COMPLETED` at ~130k chars. Breaks the prompt budget, not the extractor. |
| `adv-03-arabic-rtl-cv.pdf` | nothing | `COMPLETED`. Extracts to the exact source Arabic. |
| `adv-04-restaurant-menu.pdf` | the prompt | `COMPLETED`. A real PDF that is not a CV. |
| `adv-05-zero-bytes.pdf` | upload | `REJECTED` — empty header. |
| `adv-06-executable-renamed.pdf` | upload | `REJECTED` — starts `4D 5A`, not `%PDF-`. |

Three of these deserve a second look:

**`adv-02`, the 40-page CV, is the one that will bite.** It extracts perfectly
— that is the problem. 130,000 characters is far past any sensible context
slice, so it forces the chunking and token-limit decision to be made on
purpose rather than discovered live at the demo.

**`adv-04`, the menu, has no technical defence.** It is a valid PDF with valid
text and nothing in extraction or validation can reject it. It is also
deliberately adjacent to the restaurant job, so a naive keyword match will
find "menu", "covers", "kitchen" and be pleased with itself. The only thing
that catches it is the prompt noticing there is no candidate, no employment
history and no dates.

**`adv-06` is inert on purpose.** Well-formed DOS and PE headers, zero
sections, no entry point — unmistakably a PE file to a magic-byte check, and
incapable of running. Committing a real executable to test a rejection path
would be a poor trade.

## Regenerating

```bash
fixtures\tools\generate.cmd
```

Rebuilds every binary from `content/`, then reads each one back with the same
PDFBox and POI versions the application uses and checks it behaves as
documented. Output ends with `All fixtures behaved as documented.` or a
non-zero exit.

Edit the text in `content/`, not the binaries. The markup is five characters
wide:

```
# name          the candidate's name, set large
@ contact       a contact or strapline row
## SECTION      a section heading
> role          an employer / role / date line, set bold
- bullet        a bullet
(anything else) a body paragraph; a blank line is vertical space
```

Whether a CV *has* `##` headings is a property of its source file — that is how
the sectioned and prose layouts differ.

## Two things that are not obvious

**The Arabic PDF stores its glyphs in visual order, and has to.**
`PDPageContentStream.showText` paints characters left to right in the order
given, so writing Arabic in logical order puts the first letter on the left,
which is backwards. Worse, it survives a careless check: `PDFTextStripper`
runs the Unicode bidi algorithm over what it finds, assumes glyph order is
visual order, and reverses every RTL run to recover logical order. Feed it
logical order and it hands back the string *reversed* — real Arabic
characters, correct count, wrong order, and nothing about the file looks
broken until somebody reads it. The generator therefore reorders each line
logical-to-visual with `java.text.Bidi` before drawing, reversing RTL runs by
grapheme cluster so the five Arabic diacritics stay attached to their base
letters. This is also what Word does, so the fixture matches the files it
stands in for. `VerifyFixtures` asserts the round-trip by substring match, not
by character count — a count check would have passed on the reversed version.

PDFBox does no Arabic contextual shaping, so on screen the letters appear in
isolated form. The extracted characters are correct, which is what the
pipeline consumes.

**Nothing here tests the 10 MB upload limit.** The whole corpus is 517 KB. The
three largest files are `adv-01` at 135 KB (it is a JPEG, hence the jump),
`adv-02` at 55 KB and `adv-03` at 24 KB, because it embeds a font subset; every
CV is under 4 KB. If the multipart size limit needs a fixture, it needs a new
one.
