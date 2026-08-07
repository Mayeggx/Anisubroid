#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=common.sh
source "$SCRIPT_DIR/common.sh"

strict=false
if [[ "${1:-}" == "--strict" ]]; then
    strict=true
elif [[ $# -gt 0 ]]; then
    printf 'Usage: ./mac-scripts/check-android-env.sh [--strict]\n' >&2
    exit 2
fi

configure_local_proxy
missing=0

printf 'PROJECT_ROOT=%s\n' "$PROJECT_ROOT"
printf 'ANDROID_SDK_ROOT=%s\n' "$ANDROID_SDK_ROOT"
printf 'REQUIRED_COMPILE_SDK=%s\n' "$REQUIRED_COMPILE_SDK"
printf 'REQUIRED_BUILD_TOOLS=%s\n' "$REQUIRED_BUILD_TOOLS_VERSION"

if require_java17; then
    :
else
    missing=1
fi

if sdkmanager="$(find_sdkmanager)"; then
    printf 'SDK_MANAGER=%s\n' "$sdkmanager"
else
    printf '%s\n' "Android command-line tools were not found." >&2
    missing=1
fi

if require_android_sdk; then
    :
else
    missing=1
fi

if [[ -f "$PROJECT_ROOT/local.properties" ]]; then
    printf 'LOCAL_PROPERTIES=%s\n' "$PROJECT_ROOT/local.properties"
else
    printf '%s\n' "local.properties is absent; the build script will create it." >&2
fi

if [[ -f "$PROJECT_ROOT/gradle.properties" ]] && grep -q '^systemProp\.https\.proxyHost=127\.0\.0\.1$' "$PROJECT_ROOT/gradle.properties"; then
    if nc -z 127.0.0.1 7897 >/dev/null 2>&1; then
        printf 'GRADLE_PROXY=127.0.0.1:7897 reachable\n'
    else
        printf '%s\n' "GRADLE_PROXY=127.0.0.1:7897 configured but unreachable." >&2
        missing=1
    fi
fi

if [[ "$missing" -eq 0 ]]; then
    printf '%s\n' "Environment check passed."
elif [[ "$strict" == true ]]; then
    exit 1
else
    printf '%s\n' "Environment check found missing requirements. Run: ./mac-scripts/init-android-env.sh --accept-licenses" >&2
fi
