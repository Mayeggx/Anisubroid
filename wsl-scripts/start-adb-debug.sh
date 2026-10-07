#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=common.sh
source "$SCRIPT_DIR/common.sh"

INSTALL_APK=true
LAUNCH_APP=true
CAPTURE_LOG=true

usage() {
    cat <<'EOF'
Usage: ./wsl-scripts/start-adb-debug.sh [options]

Options:
  --skip-install   Do not install the debug APK.
  --skip-launch    Do not launch the app.
  --skip-log       Do not capture a logcat snapshot.
  -h, --help       Show this help.

Environment variables:
  ADB_BIN               Explicit adb binary to use (Linux adb or Windows adb.exe).
  WINDOWS_PLATFORM_TOOLS  Windows platform-tools dir mounted in WSL.
                        Default: /mnt/e/Android/Sdk/platform-tools

USB debugging note: WSL2 cannot see USB devices unless they are attached with
usbipd. If the Linux adb finds no device, the script falls back to the Windows
adb.exe through WSL interop, which uses the host's adb server and devices.
EOF
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --skip-install) INSTALL_APK=false ;;
        --skip-launch) LAUNCH_APP=false ;;
        --skip-log) CAPTURE_LOG=false ;;
        -h|--help) usage; exit 0 ;;
        *)
            printf 'Unknown option: %s\n' "$1" >&2
            usage >&2
            exit 2
            ;;
    esac
    shift
done

APK_PATH="$PROJECT_ROOT/app/build/outputs/apk/debug/app-debug.apk"
LOGS_DIR="$PROJECT_ROOT/logs"
PACKAGE_NAME="com.mayegg.anisub"
MAIN_ACTIVITY="$PACKAGE_NAME/.MainActivity"
WINDOWS_PLATFORM_TOOLS="${WINDOWS_PLATFORM_TOOLS:-/mnt/e/Android/Sdk/platform-tools}"
WIN_ADB="$WINDOWS_PLATFORM_TOOLS/adb.exe"
LINUX_ADB="$ANDROID_SDK_ROOT/platform-tools/adb"

mkdir -p "$LOGS_DIR"

adb_lists_devices() {
    local adb_bin="$1"
    "$adb_bin" devices 2>/dev/null | awk 'NR > 1 && /[[:space:]]device[[:space:]]*$/ { found = 1 } END { exit(found ? 0 : 1) }'
}

pick_adb() {
    local candidates=()
    if [[ -n "${ADB_BIN:-}" ]]; then
        candidates+=("$ADB_BIN")
    else
        candidates+=("$LINUX_ADB" "$WIN_ADB")
    fi

    local existing=()
    local c
    for c in "${candidates[@]}"; do
        [[ -x "$c" || -f "$c" ]] && existing+=("$c")
    done

    if [[ ${#existing[@]} -eq 0 ]]; then
        printf '%s\n' "No adb binary found. Install platform-tools or set ADB_BIN." >&2
        return 1
    fi

    for c in "${existing[@]}"; do
        "$c" start-server >/dev/null 2>&1 || true
        if adb_lists_devices "$c"; then
            printf '%s\n' "$c"
            return 0
        fi
    done

    printf '%s\n' "${existing[0]}"
}

adb_cmd() {
    "$ADB" "$@"
}

adb_path_arg() {
    if [[ "$ADB" == *.exe ]]; then
        wslpath -w "$1"
    else
        printf '%s\n' "$1"
    fi
}

ADB="$(pick_adb)"
printf 'ADB=%s\n' "$ADB"
printf 'ADB_VERSION='
"$ADB" version 2>/dev/null | head -n 1 || true

printf '%s\n' "Connected devices:"
"$ADB" devices -l || true

if [[ ! -f "$APK_PATH" ]]; then
    printf 'APK not found: %s\n' "$APK_PATH"
    printf '%s\n' "Run ./wsl-scripts/build-debug.sh first."
    exit 0
fi

printf 'APK=%s\n' "$APK_PATH"

if ! adb_lists_devices "$ADB"; then
    printf '%s\n' "No online Android device detected."
    if [[ "$ADB" != *.exe && -f "$WIN_ADB" ]]; then
        printf '%s\n' "Hint: USB devices attached to Windows need usbipd for the Linux adb. Trying the Windows adb.exe may help."
    fi
    printf '%s\n' "Connect a phone and allow USB debugging, then rerun this script."
    exit 0
fi

if [[ "$INSTALL_APK" == true ]]; then
    printf '%s\n' "Installing debug APK..."
    adb_cmd install -r "$(adb_path_arg "$APK_PATH")"
else
    printf '%s\n' "Skip installing APK (--skip-install)."
fi

if [[ "$LAUNCH_APP" == true ]]; then
    printf '%s\n' "Launching app..."
    adb_cmd shell am start -n "$MAIN_ACTIVITY"
else
    printf '%s\n' "Skip launching app (--skip-launch)."
fi

if [[ "$CAPTURE_LOG" == true ]]; then
    timestamp="$(date +%Y%m%d-%H%M%S)"
    log_path="$LOGS_DIR/adb-logcat-$timestamp.txt"
    latest_path="$LOGS_DIR/adb-logcat-latest.txt"
    printf '%s\n' "Exporting current logcat snapshot..."
    adb_cmd logcat -d -v time > "$log_path" || true
    cp -f "$log_path" "$latest_path"
    printf 'LOG_FILE=%s\n' "$log_path"
    printf 'LATEST_LOG_FILE=%s\n' "$latest_path"
fi

printf '%s\n' "Ready for adb debugging."
printf '  %s logcat\n' "$ADB"
printf '  %s shell am start -n %s\n' "$ADB" "$MAIN_ACTIVITY"
printf '  %s install -r <apk>\n' "$ADB"
