#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=common.sh
source "$SCRIPT_DIR/common.sh"

COMMAND_LINE_TOOLS_URL="${ANDROID_CMDLINE_TOOLS_URL:-https://dl.google.com/android/repository/commandlinetools-mac-14742923_latest.zip}"
INSTALL_JDK=false
ACCEPT_LICENSES=false

usage() {
    cat <<'EOF'
Usage: ./mac-scripts/init-android-env.sh [options]

Options:
  --install-jdk       Install Eclipse Temurin JDK 17 through Homebrew when absent.
  --accept-licenses   Accept Android SDK licenses non-interactively.
  -h, --help          Show this help message.

Environment variables:
  ANDROID_SDK_ROOT              Android SDK directory. Default: $HOME/Library/Android/sdk
  ANDROID_CMDLINE_TOOLS_URL     macOS command-line tools ZIP URL.
  USE_LOCAL_PROXY               auto (default), 1, or 0. The project currently expects port 7897.
EOF
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --install-jdk)
            INSTALL_JDK=true
            ;;
        --accept-licenses)
            ACCEPT_LICENSES=true
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            printf 'Unknown option: %s\n' "$1" >&2
            usage >&2
            exit 2
            ;;
    esac
    shift
done

configure_local_proxy

if ! resolve_java_home; then
    if [[ "$INSTALL_JDK" != true ]]; then
        printf '%s\n' "JDK 17 is required but not installed." >&2
        printf '%s\n' "Re-run with --install-jdk, or install a JDK 17 and set JAVA_HOME." >&2
        exit 1
    fi

    if ! command -v brew >/dev/null 2>&1; then
        printf '%s\n' "Homebrew is required for --install-jdk. Install JDK 17 manually, then re-run this script." >&2
        exit 1
    fi

    brew install --cask temurin@17
    if ! resolve_java_home; then
        printf '%s\n' "JDK installation finished but JDK 17 could not be discovered. Open a new terminal or set JAVA_HOME manually." >&2
        exit 1
    fi
fi

require_java17
mkdir -p "$ANDROID_SDK_ROOT/cmdline-tools"

sdkmanager="$(find_sdkmanager || true)"
if [[ -z "$sdkmanager" ]]; then
    download_dir="$(mktemp -d "${TMPDIR:-/tmp}/anisubroid-cmdline-tools.XXXXXX")"
    trap 'rm -rf "$download_dir"' EXIT

    printf 'Downloading Android command-line tools from %s\n' "$COMMAND_LINE_TOOLS_URL"
    curl --fail --location --retry 3 --output "$download_dir/commandlinetools.zip" "$COMMAND_LINE_TOOLS_URL"
    unzip -q "$download_dir/commandlinetools.zip" -d "$download_dir/unpacked"

    if [[ ! -d "$download_dir/unpacked/cmdline-tools" ]]; then
        printf '%s\n' "Downloaded archive does not contain cmdline-tools/. Set ANDROID_CMDLINE_TOOLS_URL to a valid ZIP." >&2
        exit 1
    fi

    if [[ -e "$ANDROID_SDK_ROOT/cmdline-tools/latest" ]]; then
        printf '%s\n' "Unexpected cmdline-tools/latest state. Install Android command-line tools manually or remove the incomplete directory." >&2
        exit 1
    fi

    ditto "$download_dir/unpacked/cmdline-tools" "$ANDROID_SDK_ROOT/cmdline-tools/latest"
    sdkmanager="$ANDROID_SDK_ROOT/cmdline-tools/latest/bin/sdkmanager"
fi

printf 'SDK_MANAGER=%s\n' "$sdkmanager"
if [[ "$ACCEPT_LICENSES" == true ]]; then
    set +o pipefail
    yes | "$sdkmanager" --sdk_root="$ANDROID_SDK_ROOT" --licenses >/dev/null
    sdkmanager_status="${PIPESTATUS[1]}"
    set -o pipefail

    if [[ "$sdkmanager_status" -ne 0 ]]; then
        printf 'Android SDK license acceptance failed with exit code: %s\n' "$sdkmanager_status" >&2
        exit "$sdkmanager_status"
    fi
fi

"$sdkmanager" --sdk_root="$ANDROID_SDK_ROOT" \
    "platform-tools" \
    "platforms;android-$REQUIRED_COMPILE_SDK" \
    "build-tools;$REQUIRED_BUILD_TOOLS_VERSION"

ensure_local_properties
printf '%s\n' "Android build environment is ready. Run: ./mac-scripts/build-debug.sh"
