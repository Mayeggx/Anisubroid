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
printf '%s\n' "Starting Debug unit tests..."
sh ./gradlew testDebugUnitTest --console=plain --stacktrace --no-daemon
printf '%s\n' "Debug unit tests succeeded."
