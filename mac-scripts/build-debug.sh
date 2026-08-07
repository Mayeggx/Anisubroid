#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=common.sh
source "$SCRIPT_DIR/common.sh"

configure_local_proxy
require_java17
require_android_sdk
ensure_local_properties

cd "$PROJECT_ROOT"
printf '%s\n' "Starting Debug APK build..."
sh ./gradlew clean assembleDebug --console=plain --stacktrace --no-daemon

apk_path="$PROJECT_ROOT/app/build/outputs/apk/debug/app-debug.apk"
if [[ ! -f "$apk_path" ]]; then
    printf 'Build completed but APK was not found: %s\n' "$apk_path" >&2
    exit 1
fi

printf 'Build succeeded.\nAPK=%s\n' "$apk_path"
