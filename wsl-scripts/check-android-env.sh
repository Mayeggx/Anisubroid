#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=common.sh
source "$SCRIPT_DIR/common.sh"

strict=false
if [[ "${1:-}" == "--strict" ]]; then
    strict=true
elif [[ $# -gt 0 ]]; then
    printf 'Usage: ./wsl-scripts/check-android-env.sh [--strict]\n' >&2
    exit 2
fi

configure_local_proxy
missing=0

printf 'PROJECT_ROOT=%s\n' "$PROJECT_ROOT"
printf 'ANDROID_SDK_ROOT=%s\n' "$ANDROID_SDK_ROOT"
printf 'GRADLE_USER_HOME=%s\n' "$GRADLE_USER_HOME"
printf 'WSL_HOST_IP=%s\n' "$(wsl_host_ip)"
printf 'REQUIRED_COMPILE_SDK=%s\n' "$REQUIRED_COMPILE_SDK"
printf 'REQUIRED_BUILD_TOOLS=%s\n' "$REQUIRED_BUILD_TOOLS_VERSION"

if require_java17; then
    :
else
    missing=1
fi

if sdkmanager="$(find_sdkmanager)"; then
    printf 'SDK_MANAGER=%s\n' "$sdkmanager"
elif is_shared_sdk_root; then
    printf '%s\n' "note: no Linux sdkmanager in shared SDK mode (not needed; components are merged directly)."
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

gradle_properties_proxy
if [[ -n "$PROJECT_PROXY_HOST" ]]; then
    if tcp_open "$PROJECT_PROXY_HOST" "${PROJECT_PROXY_PORT:-7897}"; then
        printf 'GRADLE_PROXY=%s:%s reachable\n' "$PROJECT_PROXY_HOST" "$PROJECT_PROXY_PORT"
    else
        printf 'GRADLE_PROXY=%s:%s unreachable from WSL; init.d override handles it.\n' "$PROJECT_PROXY_HOST" "$PROJECT_PROXY_PORT"
    fi
fi

# gh is only needed for release-git.sh --create-github-release, so it is
# reported but never counted as a missing requirement.
if command -v gh >/dev/null 2>&1; then
    printf 'GH=%s\n' "$(gh --version | head -n 1)"
    if gh auth status --hostname github.com >/dev/null 2>&1; then
        printf 'GH_AUTH=authenticated\n'
    else
        printf '%s\n' "note: gh is installed but not authenticated (needed only for --create-github-release). Run: gh auth login" >&2
    fi
else
    printf '%s\n' "note: GitHub CLI (gh) not installed (needed only for --create-github-release). Install: ./wsl-scripts/init-android-env.sh --install-gh" >&2
fi

if [[ "$missing" -eq 0 ]]; then
    printf '%s\n' "Environment check passed."
elif [[ "$strict" == true ]]; then
    exit 1
else
    printf '%s\n' "Environment check found missing requirements. Run: ./wsl-scripts/init-android-env.sh --accept-licenses" >&2
fi
