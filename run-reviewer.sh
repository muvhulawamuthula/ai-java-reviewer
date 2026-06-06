#!/usr/bin/env bash
#
# Launches the AI Java Reviewer web UI, pulling ANTHROPIC_API_KEY from the system
# keyring (libsecret) so the key is never stored in plaintext or typed each run.
#
# One-time setup:
#   sudo apt install -y libsecret-tools
#   secret-tool store --label='Anthropic API key' service ai-java-reviewer key api
#     (paste the key at the prompt)
#
# Usage:
#   ./run-reviewer.sh                 # serve on http://localhost:8080
#   ./run-reviewer.sh --port 9000     # any extra args pass through to the jar
#
set -euo pipefail

cd "$(dirname "$0")"

JAR="target/ai-java-reviewer.jar"

if ! command -v secret-tool >/dev/null 2>&1; then
  echo "secret-tool not found. Install it with: sudo apt install -y libsecret-tools" >&2
  exit 1
fi

# Build the jar on first run (or if it's missing).
if [ ! -f "$JAR" ]; then
  echo "Building $JAR ..." >&2
  mvn -q -DskipTests package
fi

KEY="$(secret-tool lookup service ai-java-reviewer key api || true)"
if [ -z "$KEY" ]; then
  echo "No key found in the keyring. Store it once with:" >&2
  echo "  secret-tool store --label='Anthropic API key' service ai-java-reviewer key api" >&2
  exit 1
fi

# Default to --serve if the caller passed no arguments.
if [ "$#" -eq 0 ]; then
  set -- --serve
fi

# Export only for the child process; never printed or written to disk.
ANTHROPIC_API_KEY="$KEY" exec java -jar "$JAR" "$@"
