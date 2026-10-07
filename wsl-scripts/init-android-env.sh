#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=common.sh
source "$SCRIPT_DIR/common.sh"

COMMAND_LINE_TOOLS_URL="${ANDROID_CMDLINE_TOOLS_URL:-https://dl.google.com/android/repository/commandlinetools-linux-14742923_latest.zip}"
INSTALL_JDK=false
ACCEPT_LICENSES=false
INSTALL_GH=false

usage() {
    cat <<'EOF'
Usage: ./wsl-scripts/init-android-env.sh [options]

Options:
  --install-jdk       Install OpenJDK 17 (plus curl/unzip) through apt when absent.
  --accept-licenses   Accept Android SDK licenses non-interactively (standalone SDK only).
  --install-gh        Install GitHub CLI (gh) from the official apt repository when absent.
                      Only needed for release-git.sh --create-github-release.
  -h, --help          Show this help message.

Behavior:
  When ANDROID_SDK_ROOT resolves to the host Windows SDK (/mnt/e/Android/Sdk by
  default), platforms/ and licenses/ are reused directly and nothing SDK-related
  is downloaded. Set ANDROID_SDK_ROOT to a WSL path (e.g. $HOME/Android/Sdk) to
  provision a standalone Linux SDK with cmdline-tools instead.

Environment variables:
  ANDROID_SDK_ROOT              Android SDK directory. Default: reuse /mnt/e/Android/Sdk.
  WINDOWS_SDK_ROOT              Windows SDK mount point. Default: /mnt/e/Android/Sdk.
  ANDROID_CMDLINE_TOOLS_URL     Linux command-line tools ZIP URL (standalone mode).
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
        --install-gh)
            INSTALL_GH=true
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

sudo_cmd=()
if [[ "$(id -u)" -ne 0 ]]; then
    if command -v sudo >/dev/null 2>&1; then
        sudo_cmd=(sudo)
    else
        printf '%s\n' "Root privileges are required to install packages and sudo is unavailable." >&2
        exit 1
    fi
fi

install_apt_packages() {
    "${sudo_cmd[@]}" apt-get update
    DEBIAN_FRONTEND=noninteractive "${sudo_cmd[@]}" apt-get install -y "$@"
}

# Installs GitHub CLI via the official apt repository so `apt upgrade` keeps it
# current. Only required for release-git.sh --create-github-release.
install_gh_cli() {
    if command -v gh >/dev/null 2>&1; then
        printf 'GitHub CLI already installed: %s\n' "$(gh --version | head -n 1)"
        return 0
    fi

    printf '%s\n' "Installing GitHub CLI (gh) from the official apt repository..."
    "${sudo_cmd[@]}" install -m 0755 -d /etc/apt/keyrings
    curl -fsSL "https://cli.github.com/packages/githubcli-archive-keyring.gpg" |
        "${sudo_cmd[@]}" tee /etc/apt/keyrings/githubcli-archive-keyring.gpg >/dev/null
    "${sudo_cmd[@]}" chmod go+r /etc/apt/keyrings/githubcli-archive-keyring.gpg
    printf 'deb [arch=%s signed-by=/etc/apt/keyrings/githubcli-archive-keyring.gpg] https://cli.github.com/packages stable main\n' \
        "$(dpkg --print-architecture)" |
        "${sudo_cmd[@]}" tee /etc/apt/sources.list.d/github-cli.list >/dev/null
    "${sudo_cmd[@]}" apt-get update
    install_apt_packages gh

    command -v gh >/dev/null 2>&1 || {
        printf '%s\n' "GitHub CLI installation finished but gh was not found on PATH." >&2
        return 1
    }
    printf 'GH=%s\n' "$(gh --version | head -n 1)"
}

if ! resolve_java_home; then
    if [[ "$INSTALL_JDK" != true ]]; then
        printf '%s\n' "JDK 17 is required but not installed." >&2
        printf '%s\n' "Re-run with --install-jdk, or install a JDK 17 and set JAVA_HOME." >&2
        exit 1
    fi

    install_apt_packages openjdk-17-jdk-headless
    if ! resolve_java_home; then
        printf '%s\n' "JDK installation finished but JDK 17 could not be discovered. Set JAVA_HOME manually." >&2
        exit 1
    fi
fi

missing_tools=()
command -v curl >/dev/null 2>&1 || missing_tools+=(curl)
command -v unzip >/dev/null 2>&1 || missing_tools+=(unzip)
command -v nc >/dev/null 2>&1 || missing_tools+=(netcat-openbsd)
if [[ ${#missing_tools[@]} -gt 0 ]]; then
    install_apt_packages "${missing_tools[@]}"
fi

if [[ "$INSTALL_GH" == true ]]; then
    install_gh_cli
fi

require_java17
seed_gradle_home

if is_shared_sdk_root; then
    printf 'Reusing Windows SDK at %s\n' "$ANDROID_SDK_ROOT"

    # The shared SDK already has the platform data and accepted licenses.
    # What it lacks are Linux binaries; merge them next to the Windows ones.
    build_tools_dir="$ANDROID_SDK_ROOT/build-tools/$REQUIRED_BUILD_TOOLS_VERSION"
    if [[ ! -x "$build_tools_dir/aapt" && ! -x "$build_tools_dir/aapt2" ]]; then
        merge_linux_sdk_package "build-tools;$REQUIRED_BUILD_TOOLS_VERSION" "$build_tools_dir"
    fi

    platform_tools_dir="$ANDROID_SDK_ROOT/platform-tools"
    if [[ ! -x "$platform_tools_dir/adb" && -d "$platform_tools_dir" ]]; then
        merge_linux_sdk_package "platform-tools" "$platform_tools_dir"
    fi

    if [[ ! -d "$ANDROID_SDK_ROOT/platforms/android-$REQUIRED_COMPILE_SDK" ]]; then
        merge_linux_sdk_package "platforms;android-$REQUIRED_COMPILE_SDK" "$ANDROID_SDK_ROOT/platforms/android-$REQUIRED_COMPILE_SDK"
    fi

    if [[ ! -d "$ANDROID_SDK_ROOT/licenses" ]]; then
        printf '%s\n' "No accepted SDK licenses found in the shared SDK. On Windows run: sdkmanager --licenses" >&2
        exit 1
    fi

    if require_android_sdk; then
        ensure_local_properties
        printf '%s\n' "Android build environment is ready (shared SDK mode). Run: ./wsl-scripts/build-debug.sh"
        exit 0
    fi
    exit 1
fi

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

    cp -a "$download_dir/unpacked/cmdline-tools" "$ANDROID_SDK_ROOT/cmdline-tools/latest"
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
printf '%s\n' "Android build environment is ready. Run: ./wsl-scripts/build-debug.sh"
