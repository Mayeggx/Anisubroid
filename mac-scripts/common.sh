#!/usr/bin/env bash

set -euo pipefail

MAC_SCRIPTS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$MAC_SCRIPTS_DIR/.." && pwd)"
ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}"
ANDROID_HOME="$ANDROID_SDK_ROOT"
REQUIRED_COMPILE_SDK="35"
REQUIRED_BUILD_TOOLS_VERSION="36.0.0"

export ANDROID_SDK_ROOT
export ANDROID_HOME

configure_local_proxy() {
    case "${USE_LOCAL_PROXY:-auto}" in
        0|false|FALSE|no|NO)
            ;;
        1|true|TRUE|yes|YES)
            export HTTP_PROXY="${HTTP_PROXY:-http://127.0.0.1:7897}"
            export HTTPS_PROXY="${HTTPS_PROXY:-http://127.0.0.1:7897}"
            ;;
        auto)
            if nc -z 127.0.0.1 7897 >/dev/null 2>&1; then
                export HTTP_PROXY="${HTTP_PROXY:-http://127.0.0.1:7897}"
                export HTTPS_PROXY="${HTTPS_PROXY:-http://127.0.0.1:7897}"
            fi
            ;;
        *)
            printf 'Invalid USE_LOCAL_PROXY value: %s\n' "$USE_LOCAL_PROXY" >&2
            return 1
            ;;
    esac
}

is_java17_home() {
    local candidate="$1"
    local version_line

    [[ -x "$candidate/bin/java" ]] || return 1
    version_line="$("$candidate/bin/java" -version 2>&1 | head -n 1)"
    [[ "$version_line" =~ \"17\. ]]
}

resolve_java_home() {
    if [[ -n "${JAVA_HOME:-}" ]] && is_java17_home "$JAVA_HOME"; then
        return 0
    fi

    local detected_java_home=""
    if [[ -x "/usr/libexec/java_home" ]]; then
        detected_java_home="$(/usr/libexec/java_home -v 17 2>/dev/null || true)"
    fi

    if [[ -n "$detected_java_home" ]] && is_java17_home "$detected_java_home"; then
        JAVA_HOME="$detected_java_home"
        export JAVA_HOME
        return 0
    fi

    return 1
}

require_java17() {
    if ! resolve_java_home; then
        printf '%s\n' "JDK 17 was not found. Run: ./mac-scripts/init-android-env.sh --install-jdk" >&2
        return 1
    fi

    local java_version
    java_version="$("$JAVA_HOME/bin/java" -version 2>&1 | head -n 1)"
    printf 'JAVA_HOME=%s\n' "$JAVA_HOME"
    printf 'JAVA_VERSION=%s\n' "$java_version"
}

find_sdkmanager() {
    local candidate
    for candidate in \
        "$ANDROID_SDK_ROOT/cmdline-tools/latest/bin/sdkmanager" \
        "$ANDROID_SDK_ROOT/cmdline-tools/bin/sdkmanager"; do
        if [[ -x "$candidate" ]]; then
            printf '%s\n' "$candidate"
            return 0
        fi
    done

    return 1
}

ensure_local_properties() {
    local local_properties="$PROJECT_ROOT/local.properties"
    local temp_file
    temp_file="$(mktemp "${TMPDIR:-/tmp}/anisubroid-local-properties.XXXXXX")"

    if [[ -f "$local_properties" ]]; then
        awk -v sdk_dir="$ANDROID_SDK_ROOT" '
            BEGIN { found = 0 }
            /^sdk\.dir=/ { print "sdk.dir=" sdk_dir; found = 1; next }
            { print }
            END { if (!found) print "sdk.dir=" sdk_dir }
        ' "$local_properties" > "$temp_file"
    else
        printf 'sdk.dir=%s\n' "$ANDROID_SDK_ROOT" > "$temp_file"
    fi

    mv "$temp_file" "$local_properties"
    printf 'ANDROID_SDK_ROOT=%s\n' "$ANDROID_SDK_ROOT"
    printf 'local.properties=%s\n' "$local_properties"
}

require_android_sdk() {
    local missing=false

    if [[ ! -d "$ANDROID_SDK_ROOT/platforms/android-$REQUIRED_COMPILE_SDK" ]]; then
        printf '%s\n' "Android SDK Platform $REQUIRED_COMPILE_SDK was not found at $ANDROID_SDK_ROOT." >&2
        missing=true
    fi

    if [[ ! -x "$ANDROID_SDK_ROOT/platform-tools/adb" ]]; then
        printf '%s\n' "Android platform-tools was not found at $ANDROID_SDK_ROOT." >&2
        missing=true
    fi

    if [[ ! -x "$ANDROID_SDK_ROOT/build-tools/$REQUIRED_BUILD_TOOLS_VERSION/aapt2" ]]; then
        printf '%s\n' "Android SDK Build-Tools $REQUIRED_BUILD_TOOLS_VERSION was not found at $ANDROID_SDK_ROOT." >&2
        missing=true
    fi

    if [[ "$missing" == true ]]; then
        printf '%s\n' "Run: ./mac-scripts/init-android-env.sh --accept-licenses" >&2
        return 1
    fi
}
