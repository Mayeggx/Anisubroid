#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
RETRY_DELAY_SECONDS="${RETRY_DELAY_SECONDS:-15}"
BUILD_RETRY_LOG_DIR="${BUILD_RETRY_LOG_DIR:-$PROJECT_ROOT/build/reports/macos-build-retries}"
attempt=1

if ! [[ "$RETRY_DELAY_SECONDS" =~ ^[0-9]+$ ]]; then
    printf 'RETRY_DELAY_SECONDS must be a non-negative integer, got: %s\n' "$RETRY_DELAY_SECONDS" >&2
    exit 2
fi

mkdir -p "$BUILD_RETRY_LOG_DIR"

while true; do
    attempt_log="$BUILD_RETRY_LOG_DIR/debug-build-attempt-${attempt}-$(date +%Y%m%d-%H%M%S).log"
    printf '\n=== Debug build attempt %d ===\n' "$attempt"
    printf 'Log=%s\n' "$attempt_log"

    set +e
    "$SCRIPT_DIR/build-debug.sh" 2>&1 | tee "$attempt_log"
    build_status="${PIPESTATUS[0]}"
    set -e

    if [[ "$build_status" -eq 0 ]]; then
        printf 'Debug APK build succeeded on attempt %d.\n' "$attempt"
        exit 0
    fi

    printf 'Debug APK build attempt %d failed (exit code %d). Retrying in %s seconds; press Ctrl-C to stop.\n' \
        "$attempt" "$build_status" "$RETRY_DELAY_SECONDS" >&2
    sleep "$RETRY_DELAY_SECONDS"
    ((attempt += 1))
done
