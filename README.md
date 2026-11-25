# AI Java Reviewer

[![Java 17](https://img.shields.io/badge/Java-17-orange?logo=openjdk)](https://openjdk.org/)
[![langchain4j](https://img.shields.io/badge/langchain4j-1.13.1-blue)](https://docs.langchain4j.dev/)
[![Claude](https://img.shields.io/badge/Claude-Anthropic-black)](https://www.anthropic.com/)
[![License](https://img.shields.io/badge/license-see%20repo-lightgrey)](#)

AI-powered Java code review for pull requests. Built on **[langchain4j](https://docs.langchain4j.dev/)** and the **Anthropic Claude** API, packaged as a fat JAR that your GitHub Action builds and runs on every PR that touches `*.java`.

It finds security issues (with CWE tags where applicable), anti-patterns, complexity hotspots, likely bugs, and occasional style notes — then posts a sticky summary comment, inline review comments on changed lines, and a SARIF upload for the Code scanning tab.

> AI review complements human review and deterministic tools (SpotBugs, PMD, Semgrep, CodeQL). It can miss issues and raise false positives. Treat findings as suggestions.

## Why this design

| Trait | What it means in practice |
|---|---|
| **Diff-aware** | Full file for context; only findings on (or within ±2 lines of) PR-changed lines survive. |
| **Confidence-scored** | Every finding is HIGH / MEDIUM / LOW; `REVIEWER_MIN_CONFIDENCE` drops the rest. |
| **Prompt-cached** | The review rubric (`system-prompt.txt`) is cached via Anthropic `cacheSystemMessages`. |
| **Parallel** | Files reviewed concurrently (`REVIEWER_CONCURRENCY`, default 4). |
| **Fail-the-check** | Exit code 1 when surviving findings meet `REVIEWER_FAIL_ON` (default `HIGH`). |

## Quick start

1. Copy this project into your repo (or publish the JAR and call it from your own workflow).
2. Add repository secret **`ANTHROPIC_API_KEY`**.
3. Keep [`.github/workflows/code-review.yml`](.github/workflows/code-review.yml) — it triggers on PRs that change `*.java`.

SARIF upload needs code scanning (public repos, or private with GitHub Advanced Security). That step uses `continue-on-error`, so sticky + inline comments still work without it.

**Full setup, configuration, local CLI, and web UI:** [docs/usage.md](docs/usage.md)  
**Pipeline, components, and data flow:** [docs/architecture.md](docs/architecture.md)

## Outputs

On each qualifying PR the Action produces:

1. **Sticky summary** — `review-report.md` via `marocchino/sticky-pull-request-comment` (header `ai-java-reviewer`).
2. **Inline comments** — `review-comments.json` posted through `actions/github-script`, deduped by fingerprint markers (`<!-- ai-reviewer:fp=… -->`).
3. **SARIF** — `review.sarif` uploaded with category `ai-java-reviewer` (fingerprints, CWE help URIs, `security-severity`).

## Configuration (summary)

All knobs are environment variables (set under the workflow `env:` block):

| Variable | Default | Role |
|---|---|---|
| `ANTHROPIC_API_KEY` | *(required)* | Anthropic API key |
| `REVIEWER_MODEL` | `claude-sonnet-4-6` | Claude model id |
| `REVIEWER_FAIL_ON` | `HIGH` | Min severity that fails the job |
| `REVIEWER_MIN_CONFIDENCE` | `LOW` | Drop findings below this confidence |
| `REVIEWER_CONCURRENCY` | `4` | Parallel file reviews |
| `REVIEWER_MAX_TOKENS` | `8000` | Max output tokens per file |
| `REVIEWER_TIMEOUT_SECONDS` | `120` | Per-request timeout |
| `REVIEWER_DIFF_FROM` | *(unset)* | Unified diff path → diff-aware mode |
| `REVIEWER_FILES_FROM` | *(unset)* | Newline-separated file list |
| `REVIEWER_OUTPUT_MD` / `_SARIF` / `_COMMENTS` | `review-report.md` / `review.sarif` / `review-comments.json` | Report paths |
| `REVIEWER_VERBOSE` | `false` | Log raw model I/O |
| `REVIEWER_SERVE` / `REVIEWER_PORT` | `false` / `8080` | Web UI mode |

See [docs/usage.md](docs/usage.md) for CLI args, exit codes, and local recipes.

## Run locally (CLI)

```bash
mvn -DskipTests package
export ANTHROPIC_API_KEY=sk-ant-...
java -jar target/ai-java-reviewer.jar path/to/File.java

# Diff-aware against main
git diff --diff-filter=ACMR main...HEAD -- '*.java' > diff.patch
REVIEWER_DIFF_FROM=diff.patch java -jar target/ai-java-reviewer.jar

mvn test   # no API key required
```

## Web UI

```bash
java -jar target/ai-java-reviewer.jar --serve          # http://localhost:8080
java -jar target/ai-java-reviewer.jar --serve --port 9000
# or: ./run-reviewer.sh   # loads key from libsecret keyring
```

Paste-and-review is **whole-file** (diff filter only applies in CI). Endpoints: `GET /`, `POST /api/review`, `GET /api/health`.

## Project layout

```
src/main/java/com/example/reviewer/
  Reviewer.java                 # CLI / --serve entry; loads config + diff; writes reports
  ai/CodeReviewAssistant.java   # langchain4j AiService (user message template)
  ai/ReviewerFactory.java       # Claude model + prompt caching + Config.fromEnv()
  diff/DiffParser.java          # unified diff → changed lines (new-file coords)
  diff/FileDiff.java            # ranges + slack matching
  review/ReviewEngine.java      # parallel per-file review loop
  review/FindingFilter.java     # confidence + diff-scope gates (pure, unit-tested)
  model/                        # Finding, ReviewResult, Severity, Confidence, Category
  report/                       # ResponseParser, Markdown / SARIF / ReviewComments reporters
  web/WebServer.java            # JDK HttpServer UI
src/main/resources/system-prompt.txt   # review rubric + JSON contract
.github/workflows/code-review.yml      # GitHub Action
docs/                           # architecture + usage guides
```

## Cost & limits

- One API call per changed file; concurrency bounded by `REVIEWER_CONCURRENCY`.
- Files over **120 KB** are skipped (`MAX_FILE_BYTES` in `ReviewEngine`).
- Temperature is fixed at **0.0** for stable reviews.
- Prefer Haiku or tighter `paths:` filters for large PRs.

## Extending

- Tune the rubric in `system-prompt.txt`.
- Add a `Category` enum value and mention it in the prompt.
- Adjust scope in `FindingFilter` (slack, unlocalized HIGH+ keep rule).
- Swap sticky/inline steps for Slack or other sinks.

## Documentation

| Doc | Contents |
|---|---|
| [docs/usage.md](docs/usage.md) | Action setup, env vars, CLI, web UI, troubleshooting |
| [docs/architecture.md](docs/architecture.md) | End-to-end pipeline, modules, filtering, Action steps |

## License

See the repository for license terms.