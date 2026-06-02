# AI Java Reviewer

An AI-powered Java code reviewer built on **[langchain4j](https://docs.langchain4j.dev/)**
and the **Anthropic (Claude) API**. It analyzes changed Java files for:

- **Security vulnerabilities** (injection, insecure deserialization, weak crypto, hardcoded secrets, XXE, …) — tagged with CWE ids where applicable
- **Anti-patterns** (swallowed exceptions, God classes, resource leaks, mutable static state, …)
- **Complexity** (high cyclomatic/cognitive complexity, long methods, deep nesting)
- plus likely **bugs** and minor **style** notes

It runs as a **GitHub Action** on pull requests and produces three things: a single,
self-updating **summary comment**, **inline review comments** anchored to the changed
lines, and a **SARIF** file so findings also show up in the repository's *Code scanning* tab.

What makes it low-noise and cheap to run:

- **Diff-aware** — it sends the whole file for context but only flags issues on the lines
  the PR actually changed, so it doesn't nag about pre-existing code.
- **Confidence-scored** — every finding carries a HIGH/MEDIUM/LOW confidence, and you can
  suppress anything below a threshold (`REVIEWER_MIN_CONFIDENCE`).
- **Prompt-cached** — the review rubric is cached on Anthropic's side, so reviewing many
  files in one PR doesn't re-send the instructions each time.
- **Parallel** — files are reviewed concurrently (`REVIEWER_CONCURRENCY`).

> AI review is a complement to — not a replacement for — human review and
> deterministic tools (SpotBugs, PMD, Semgrep, CodeQL). It can miss issues and
> raise false positives. Treat findings as suggestions.

---

## How it works

```
PR opened/updated
   │
   ├─ git diff base..head → changed .java files + unified diff (diff.patch)
   ├─ parse diff → changed line ranges per file (new-file coordinates)
   ├─ for each file (in parallel) → Claude (langchain4j AiService, cached rubric) → JSON findings
   ├─ filter → keep only findings on changed lines and at/above the confidence threshold
   ├─ aggregate → review-report.md  +  review.sarif  +  review-comments.json
   ├─ post/update sticky summary comment (review-report.md)
   ├─ post inline review comments on the changed lines (review-comments.json)
   └─ upload SARIF → Code scanning tab
```

The model is asked to return strict JSON; the app parses it into typed `Finding` objects,
applies the diff-scope + confidence filter, renders the three reports, and exits non-zero
when any surviving finding meets the configured severity threshold (so the check can block
merges). If no diff is supplied it falls back to reviewing whole files.

## Setup

1. Copy this project into your repo (or publish the jar and call it from your own workflow).
2. Add your Anthropic key as a repository secret named **`ANTHROPIC_API_KEY`**
   (Settings → Secrets and variables → Actions → New repository secret).
3. Commit `.github/workflows/code-review.yml`. Done — it triggers on PRs that touch `*.java`.

That's it. Inline SARIF annotations require code scanning to be available
(public repos, or private repos with GitHub Advanced Security); the upload step
is marked `continue-on-error`, so the comment still works without it.

## Configuration

All configuration is via environment variables (set them in the workflow's `env:`):

| Variable | Default | Description |
|---|---|---|
| `ANTHROPIC_API_KEY` | *(required)* | Your Anthropic API key. |
| `REVIEWER_MODEL` | `claude-sonnet-4-6` | Claude model id. Use a Haiku model for cheaper/faster reviews, or `claude-opus-4-8` for the most thorough. |
| `REVIEWER_FAIL_ON` | `HIGH` | Minimum severity that fails the check: `CRITICAL`, `HIGH`, `MEDIUM`, `LOW`, `INFO`. |
| `REVIEWER_MIN_CONFIDENCE` | `LOW` | Drop findings below this confidence: `HIGH`, `MEDIUM`, `LOW`. Set `MEDIUM` to cut false positives. |
| `REVIEWER_CONCURRENCY` | `4` | How many files to review in parallel. |
| `REVIEWER_MAX_TOKENS` | `8000` | Max output tokens per file. |
| `REVIEWER_TIMEOUT_SECONDS` | `120` | Per-request timeout. |
| `REVIEWER_DIFF_FROM` | *(unset)* | Path to a unified diff (e.g. `git diff`). Enables diff-aware mode; files to review are derived from it. |
| `REVIEWER_FILES_FROM` | *(unset)* | Path to a newline-separated list of files to review (whole-file mode). |
| `REVIEWER_OUTPUT_MD` | `review-report.md` | Markdown summary report path. |
| `REVIEWER_OUTPUT_SARIF` | `review.sarif` | SARIF output path. |
| `REVIEWER_OUTPUT_COMMENTS` | `review-comments.json` | Inline-review-comments payload path. |
| `REVIEWER_VERBOSE` | `false` | Log raw requests/responses (debugging). |

You can also pass file paths directly as CLI arguments. Inputs combine: CLI args,
`REVIEWER_FILES_FROM`, and the files named in `REVIEWER_DIFF_FROM` are all reviewed.
When a diff is supplied the review is diff-aware; otherwise files are reviewed whole.

## Run locally

```bash
mvn -DskipTests package

export ANTHROPIC_API_KEY=sk-ant-...
java -jar target/ai-java-reviewer.jar src/main/java/com/acme/PaymentService.java

# review-report.md and review.sarif are written to the working directory
cat review-report.md
```

To run a diff-aware review of everything changed against `main` (only changed lines flagged):

```bash
git diff --diff-filter=ACMR main...HEAD -- '*.java' > diff.patch
REVIEWER_DIFF_FROM=diff.patch java -jar target/ai-java-reviewer.jar
# writes review-report.md, review.sarif, review-comments.json
```

Run the test suite (no API key needed — the model isn't called):

```bash
mvn test
```

## Cost & performance notes

- One API call per changed file, run `REVIEWER_CONCURRENCY` at a time. Cost scales with file
  size and the number of changed files.
- The system prompt (review rubric) is cached on Anthropic's side (`cacheSystemMessages`), so
  the per-file input cost after the first file is just the file itself.
- Files over ~120 KB are skipped (raise `MAX_FILE_BYTES` in `ReviewEngine.java` if needed).
- For large PRs, consider Haiku, or only reviewing files under certain paths
  (tighten the `paths:` filter in the workflow).
- `temperature` is fixed at `0.0` for stable, repeatable reviews.

## Project layout

```
src/main/java/com/example/reviewer/
  Reviewer.java               # thin CLI entry point: loads config + diff, writes reports
  ai/CodeReviewAssistant.java # langchain4j AiService interface (user message)
  ai/ReviewerFactory.java     # builds the Claude model (+ prompt caching) + AiService + config
  diff/DiffParser.java        # unified diff → changed line ranges per file
  diff/FileDiff.java          # changed lines for one file (ranges, slack matching)
  review/ReviewEngine.java    # parallel per-file review loop
  review/FindingFilter.java   # pure diff-scope + confidence filtering (heavily unit-tested)
  model/                      # Finding, ReviewResult, Severity, Confidence, Category
  report/ResponseParser.java  # parses the model's JSON (fence-tolerant)
  report/MarkdownReporter.java
  report/SarifReporter.java          # enriched: fingerprints, CWE helpUri, security-severity
  report/ReviewCommentsReporter.java # inline PR review-comment payload
src/main/resources/system-prompt.txt # the review rubric + JSON contract
src/test/java/...                     # JUnit 5 suite
.github/workflows/code-review.yml     # the GitHub Action
```

## Extending

- **Tune the rubric**: edit `system-prompt.txt` (categories, severity/confidence guidance, JSON shape).
- **Add a category**: add to the `Category` enum and mention it in the prompt.
- **Tune the scope filter**: `review/FindingFilter.java` decides what survives (changed-line
  slack, how unlocalized findings are handled, confidence threshold) — it's a pure function
  with full unit coverage, so it's safe to adjust.
- **Send only the diff hunks** (cheaper, less context): pass the patch instead of full file
  contents in `CodeReviewAssistant#review`.
- **Different sink**: swap the sticky-comment / inline-comment steps for `gh pr comment`, Slack, etc.
