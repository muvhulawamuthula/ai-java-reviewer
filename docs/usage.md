# Usage guide

How to install, configure, and run **ai-java-reviewer** as a GitHub Action, CLI, or local web UI.

## Prerequisites

- JDK **17+** and Maven (Action installs Temurin 17 for you)
- An **Anthropic API key** with access to the chosen Claude model
- For SARIF / Code scanning annotations: a public repo, or private repo with GitHub Advanced Security

## GitHub Action setup

1. Ensure [`.github/workflows/code-review.yml`](../.github/workflows/code-review.yml) is present (this repo’s workflow is ready to use as-is).
2. Repository **Settings → Secrets and variables → Actions → New repository secret**:
   - Name: `ANTHROPIC_API_KEY`
   - Value: your Anthropic key
3. Open a PR that changes a `*.java` file. The workflow:
   - Checks out with `fetch-depth: 0`
   - Builds `target/ai-java-reviewer.jar`
   - Diffs PR base…head (`--diff-filter=ACMR` on `*.java`)
   - Runs the JAR with `REVIEWER_DIFF_FROM=diff.patch`
   - Posts sticky summary + inline comments; uploads SARIF

### Permissions the workflow needs

```yaml
permissions:
  contents: read          # checkout
  pull-requests: write    # sticky + inline comments
  security-events: write  # SARIF upload
```

### What you see on the PR

1. **Sticky comment** — self-updating markdown summary (`header: ai-java-reviewer`).
2. **Inline review comments** — anchored to the RIGHT (new) side of the diff; re-runs skip fingerprints already present.
3. **Code scanning** — SARIF category `ai-java-reviewer` when upload succeeds.
4. **Check failure** — job fails if the JAR exits `1` (findings ≥ `REVIEWER_FAIL_ON`).

Inline posting and SARIF upload use `continue-on-error: true` so commenting/upload issues do not mask the review result from the JAR itself. The sticky comment step runs under `if: always()` when files changed, so a failing review still updates the summary when the report file exists.

## Environment variables

Set these in the workflow `env:` (or your shell for local runs).

| Variable | Default | Description |
|---|---|---|
| `ANTHROPIC_API_KEY` | *(required)* | Anthropic API key |
| `REVIEWER_MODEL` | `claude-sonnet-4-6` | Model id (e.g. Haiku for cost, `claude-opus-4-8` for depth) |
| `REVIEWER_FAIL_ON` | `HIGH` | Fail check on findings at/above: `CRITICAL`, `HIGH`, `MEDIUM`, `LOW`, `INFO` |
| `REVIEWER_MIN_CONFIDENCE` | `LOW` | Suppress below: `HIGH`, `MEDIUM`, `LOW` (try `MEDIUM` to cut noise) |
| `REVIEWER_CONCURRENCY` | `4` | Parallel file reviews |
| `REVIEWER_MAX_TOKENS` | `8000` | Max output tokens per file |
| `REVIEWER_TIMEOUT_SECONDS` | `120` | Per-request timeout (seconds) |
| `REVIEWER_DIFF_FROM` | *(unset)* | Path to unified diff → **diff-aware** mode; files derived from the diff |
| `REVIEWER_FILES_FROM` | *(unset)* | Newline-separated list of paths (avoids CLI length limits) |
| `REVIEWER_OUTPUT_MD` | `review-report.md` | Markdown summary path |
| `REVIEWER_OUTPUT_SARIF` | `review.sarif` | SARIF path |
| `REVIEWER_OUTPUT_COMMENTS` | `review-comments.json` | Inline-comments JSON path |
| `REVIEWER_VERBOSE` | `false` | Log raw request/response bodies |
| `REVIEWER_SERVE` | `false` | Start web UI instead of CLI review |
| `REVIEWER_PORT` | `8080` | Web UI port (overridden by `--port`) |

### Input combination rules

- Candidates = CLI args ∪ `REVIEWER_FILES_FROM` ∪ paths named in `REVIEWER_DIFF_FROM`.
- Only existing files ending in `.java` are reviewed.
- With a valid diff → diff-aware filtering; without → whole-file review.

### Exit codes

| Code | When |
|---|---|
| `0` | Success; no findings at/above `REVIEWER_FAIL_ON` |
| `1` | Blocking findings present |
| `2` | Config / unexpected error |

## Local CLI

```bash
mvn -DskipTests package
export ANTHROPIC_API_KEY=sk-ant-...

# Whole-file review of explicit paths
java -jar target/ai-java-reviewer.jar src/main/java/com/acme/PaymentService.java

# Diff-aware review of changes vs main
git diff --diff-filter=ACMR main...HEAD -- '*.java' > diff.patch
REVIEWER_DIFF_FROM=diff.patch java -jar target/ai-java-reviewer.jar

# Or pass a file list
printf '%s\n' src/main/java/com/acme/*.java > files.txt
REVIEWER_FILES_FROM=files.txt java -jar target/ai-java-reviewer.jar

# Artifacts
cat review-report.md
# also: review.sarif, review-comments.json
```

Unit tests (no API key):

```bash
mvn test
```

## Web UI

Useful for ad-hoc reviews outside a PR. Always **whole-file** (no diff scope filter).

```bash
mvn -DskipTests package
export ANTHROPIC_API_KEY=sk-ant-...
java -jar target/ai-java-reviewer.jar --serve
# http://localhost:8080

java -jar target/ai-java-reviewer.jar --serve --port 9000
# equivalent: REVIEWER_SERVE=true REVIEWER_PORT=9000 java -jar target/ai-java-reviewer.jar
```

### Keyring helper (`run-reviewer.sh`)

On Linux with libsecret:

```bash
sudo apt install -y libsecret-tools
secret-tool store --label='Anthropic API key' service ai-java-reviewer key api
./run-reviewer.sh                 # defaults to --serve
./run-reviewer.sh --port 9000     # extra args passed through
```

### HTTP API

| Method | Path | Behavior |
|---|---|---|
| `GET` | `/` | Static UI (`web/index.html`) |
| `GET` | `/api/health` | `{"ready": true\|false}` — false if no API key |
| `POST` | `/api/review` | Body `{"filename","code"}` → `{"summary","findings"}` |

The page loads without a key; reviews fail until `ANTHROPIC_API_KEY` is set.

## Tuning for noise and cost

| Goal | Knob |
|---|---|
| Fewer false positives | `REVIEWER_MIN_CONFIDENCE=MEDIUM` (or `HIGH`) |
| Stricter merge gate | `REVIEWER_FAIL_ON=MEDIUM` (or keep `HIGH` / `CRITICAL`) |
| Cheaper / faster | Smaller model (Haiku); lower `REVIEWER_CONCURRENCY`; tighten workflow `paths:` |
| Large PRs | Haiku + path filters; files >120 KB are skipped automatically |
| Debug model I/O | `REVIEWER_VERBOSE=true` |

Temperature is fixed at `0.0` in `ReviewerFactory` (not configurable) for repeatable reviews. The system prompt is prompt-cached across files in a run.

## Troubleshooting

| Symptom | Likely cause / fix |
|---|---|
| Workflow never runs | PR did not change `*.java`, or path filter excludes your files |
| `Configuration error: ANTHROPIC_API_KEY…` | Secret missing / not available to Actions |
| Sticky comment ok, no Code scanning | Advanced Security / code scanning not enabled; SARIF step is `continue-on-error` |
| Inline comments missing some lines | GitHub rejects lines outside the PR diff; script falls back and skips failures |
| Duplicate-looking comments after force-push | Fingerprints are content-based; new lines/titles produce new fingerprints |
| File skipped in logs | Empty file, >120 KB, or (diff mode) no added/modified lines |
| Job green despite findings | Findings below `REVIEWER_FAIL_ON` (e.g. only MEDIUM when fail-on is HIGH) |

## Related docs

- [Architecture](architecture.md) — modules, filtering, Action data flow
- [README](../README.md) — project overview