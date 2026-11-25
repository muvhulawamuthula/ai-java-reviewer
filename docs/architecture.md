# Architecture

This document describes how **ai-java-reviewer** turns a pull-request diff into sticky comments, inline review comments, and SARIF — and how the Java modules fit together.

## High-level pipeline

```
PR opened / synchronize / reopened (paths: **/*.java)
        │
        ▼
┌───────────────────────────────────────────────────────────┐
│  GitHub Action (.github/workflows/code-review.yml)        │
│  checkout (fetch-depth: 0) → JDK 17 → mvn package         │
│  git diff BASE..HEAD → changed.txt + diff.patch           │
└────────────────────────────┬──────────────────────────────┘
                             │ REVIEWER_DIFF_FROM=diff.patch
                             ▼
┌───────────────────────────────────────────────────────────┐
│  Reviewer (CLI)                                           │
│  loadDiff → DiffParser → Map<path, FileDiff>              │
│  collect .java paths (CLI ∪ FILES_FROM ∪ diff keys)       │
│  ReviewerFactory → CodeReviewAssistant (Claude)           │
│  ReviewEngine (parallel) → ResponseParser → FindingFilter │
│  write Markdown + SARIF + review-comments.json            │
│  exit 1 if findings ≥ REVIEWER_FAIL_ON                    │
└────────────────────────────┬──────────────────────────────┘
                             │
         ┌───────────────────┼───────────────────┐
         ▼                   ▼                   ▼
  sticky PR comment    inline review API    upload-sarif
  (summary markdown)   (fingerprinted)      (code scanning)
```

When `REVIEWER_DIFF_FROM` is unset (or the file is missing), the same JAR reviews whole files — used by the local CLI and the web UI.

## GitHub Action contract

Workflow: [`.github/workflows/code-review.yml`](../.github/workflows/code-review.yml)

| Concern | Implementation |
|---|---|
| Trigger | `pull_request` types `opened`, `synchronize`, `reopened`; path filter `**/*.java` |
| Permissions | `contents: read`, `pull-requests: write`, `security-events: write` |
| Concurrency | `ai-java-review-${{ pr.number }}` with `cancel-in-progress: true` |
| Diff | `git diff --diff-filter=ACMR` between PR base and head SHAs |
| Skip empty | Review / comment / SARIF steps gated on `steps.changed.outputs.count != '0'` |
| Sticky comment | `marocchino/sticky-pull-request-comment@v3`, header `ai-java-reviewer` |
| Inline comments | `actions/github-script@v8`; batch `createReview`, fallback per-comment; skip known fingerprints |
| SARIF | `github/codeql-action/upload-sarif@v4`, category `ai-java-reviewer`, `continue-on-error: true` |

The Action builds the reviewer from source (`mvn -B -q -DskipTests package`) so the workflow always runs the version checked into the repo.

## Java module map

| Package / type | Responsibility |
|---|---|
| `Reviewer` | Entry point: `--serve` vs CLI; env wiring; report paths; GITHUB_STEP_SUMMARY + Action outputs; exit codes |
| `ai.ReviewerFactory` | `AnthropicChatModel` (temp `0.0`, retries, `cacheSystemMessages`) + `AiServices` + `Config.fromEnv()` |
| `ai.CodeReviewAssistant` | AiService interface; user template with `filename`, `code`, `changedLines` |
| `diff.DiffParser` | Unified diff → new-file line sets; skips pure deletions |
| `diff.FileDiff` | `touches(line, slack)`, `describeRanges()` for the model prompt |
| `review.ReviewEngine` | Bounded thread pool; skip >120 KB / empty / no changed lines; one model call per file |
| `review.FindingFilter` | Pure gates: confidence threshold; diff slack (±2 from `Reviewer.LINE_SLACK`); unlocalized findings kept only if severity ≥ HIGH |
| `report.ResponseParser` | Fence-tolerant JSON → `ReviewResult` / `Finding` |
| `report.MarkdownReporter` | Sticky / job-summary markdown |
| `report.SarifReporter` | SARIF with fingerprints, CWE URIs, `security-severity` |
| `report.ReviewCommentsReporter` | `{path, line, side, body, fingerprint}` JSON for the workflow script |
| `web.WebServer` | JDK `HttpServer`: `/`, `/api/health`, `/api/review` (whole-file) |
| `model.*` | `Finding`, `Severity`, `Confidence`, `Category`, `ReviewResult` |
| `resources/system-prompt.txt` | Rubric + strict JSON schema the model must emit |

## Diff-aware review (two layers)

Noise control is intentional and layered:

1. **Prompt layer** — `CodeReviewAssistant` tells the model the changed line ranges (or `(whole file)`). The system prompt instructs it not to report pre-existing issues outside that scope.
2. **Filter layer** — `FindingFilter` drops anything that fails confidence or does not `FileDiff.touches(line, slack)` (default slack **2**). Unlocalized findings (null/0 line) in diff mode are kept only for **HIGH** or **CRITICAL**.

The engine still sends the **entire file** as context so the model can reason about callers and surrounding code.

## Finding model

Each finding (see `Finding` record and `system-prompt.txt`) carries:

- `category`: `SECURITY` | `ANTI_PATTERN` | `COMPLEXITY` | `BUG` | `STYLE`
- `severity`: `CRITICAL` → `INFO` (ordinal: lower = more serious)
- `confidence`: `HIGH` → `LOW`
- `title`, `line`, `description`, `suggestion`, optional `cwe`
- Runtime-added `file` path
- Derived `ruleId()`, `cweUrl()`, `fingerprint()` (SHA-256 of file|rule|line|title, 16 hex chars)

Fingerprints drive both SARIF identity and inline-comment idempotency (`<!-- ai-reviewer:fp=… -->`).

## Exit codes and CI gating

| Code | Meaning |
|---|---|
| `0` | No findings at/above `REVIEWER_FAIL_ON` (default `HIGH`) |
| `1` | One or more blocking findings |
| `2` | Configuration / unexpected error (e.g. missing `ANTHROPIC_API_KEY`) |

`Reviewer` also writes GitHub Actions outputs when `GITHUB_OUTPUT` is present (finding counts / blocking counts) and appends the markdown report to `GITHUB_STEP_SUMMARY`.

## Parallelism and failure isolation

`ReviewEngine` uses a fixed pool of size `min(concurrency, fileCount)`. Futures are collected in submission order so logs and aggregated findings stay deterministic. A single file failure becomes a skipped outcome; it does not abort the run.

## Web vs CI modes

| Mode | Diff | Filter | Entry |
|---|---|---|---|
| GitHub Action / CLI with `REVIEWER_DIFF_FROM` | Yes | Confidence + diff scope | `java -jar …` |
| CLI with file args only | No | Confidence only | `java -jar … File.java` |
| `--serve` / `REVIEWER_SERVE=true` | No | Confidence only | `WebServer` on port 8080 (or `--port` / `REVIEWER_PORT`) |

## Extension points

- **Rubric** — edit `system-prompt.txt` (categories, severity/confidence guidance, JSON shape).
- **New category** — extend `Category` and the prompt.
- **Scope policy** — change slack or unlocalized rules in `FindingFilter` (fully unit-tested).
- **Cheaper context** — pass hunks instead of full file contents in `CodeReviewAssistant#review`.
- **Alternate sinks** — replace sticky/inline workflow steps; reporters already emit portable artifacts.

## Tech stack

- Java **17**, Maven shade → `target/ai-java-reviewer.jar`
- **langchain4j** / **langchain4j-anthropic** `1.13.1`
- Jackson `2.18.2` for model JSON and SARIF / comments serialization
- JUnit 5 for parsers, filter, reporters (no live API calls in tests)