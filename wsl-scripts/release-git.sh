#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=common.sh
source "$SCRIPT_DIR/common.sh"

VERSION_NAME=""
VERSION_CODE=""
APP_NAME="Anisubroid"
BUILD_TASK="assembleDebug"
APK_PATH=""
BRANCH="main"
SKIP_BUILD=false
RETRY_BUILD=false
SKIP_PUSH=false
CREATE_GITHUB_RELEASE=false
REPO="Mayeggx/Anisubroid"
RELEASE_ASSET_PATH=""
RELEASE_TITLE=""
RELEASE_NOTES=""
DRY_RUN=false
AUTO_STASH_NAME=""
DID_AUTO_STASH=false

usage() {
    cat <<'EOF'
Usage:
  ./wsl-scripts/release-git.sh --version-name <version> [options]

Required:
  --version-name <version>       Android versionName and Git tag version, for example 1.2.0.

Options:
  --version-code <positive-int>  Android versionCode. Default: current value + 1.
  --app-name <name>              Release APK filename prefix. Default: Anisubroid.
  --build-task <gradle-task>     Gradle task. Default: assembleDebug.
  --apk-path <path>              APK to archive/upload. Default: app/build/outputs/apk/debug/app-debug.apk.
  --branch <branch>              Branch to commit and push. Default: main.
  --skip-build                   Reuse an existing APK instead of invoking Gradle.
  --retry-build                  For the default Debug task, retry build failures until success.
  --skip-push                    Commit and create the tag locally without pushing.
  --create-github-release        Create/update GitHub Release and upload the APK; requires GitHub CLI authentication.
  --repo <owner/repo>            GitHub repository. Default: Mayeggx/Anisubroid.
  --release-asset-path <path>    Asset uploaded to GitHub Release. Default: generated release APK.
  --release-title <title>        GitHub Release title. Default: v<version>.
  --release-notes <text>         GitHub Release notes. Default: short asset summary.
  --dry-run                      Print planned mutations without changing files, Git state, or remote state.
  -h, --help                     Show this help.

Behavior:
  1. Stashes tracked local changes temporarily, updates app/build.gradle.kts, builds and copies the APK to release/.
  2. Commits "Release v<version>", creates annotated tag v<version>, then pushes branch and tag.
  3. Restores the temporary stash after completion. Untracked files are never stashed.
  4. An existing tag enters republish mode: metadata, commit and tag creation are skipped; the requested version must already match build.gradle.kts.

Note: Git credentials inside WSL are separate from Windows. See wsl-scripts/README.md
for configuring user.name/user.email and the Windows credential manager.
EOF
}

fail() {
    printf '%s\n' "$*" >&2
    exit 1
}

print_command() {
    printf '+'
    printf ' %q' "$@"
    printf '\n'
}

run_mutation() {
    if [[ "$DRY_RUN" == true ]]; then
        print_command "$@"
        return 0
    fi
    "$@"
}

read_version_info() {
    local line
    CURRENT_VERSION_NAME=""
    CURRENT_VERSION_CODE=""

    while IFS= read -r line || [[ -n "$line" ]]; do
        if [[ -z "$CURRENT_VERSION_NAME" && "$line" =~ ^[[:space:]]*versionName[[:space:]]*=[[:space:]]*\"([^\"]+)\" ]]; then
            CURRENT_VERSION_NAME="${BASH_REMATCH[1]}"
        fi
        if [[ -z "$CURRENT_VERSION_CODE" && "$line" =~ ^[[:space:]]*versionCode[[:space:]]*=[[:space:]]*([0-9]+) ]]; then
            CURRENT_VERSION_CODE="${BASH_REMATCH[1]}"
        fi
    done < "$GRADLE_FILE"

    [[ -n "$CURRENT_VERSION_NAME" && -n "$CURRENT_VERSION_CODE" ]] || fail "Failed to read versionName/versionCode from $GRADLE_FILE"
}

update_version_info() {
    local temp_file line found_name=false found_code=false
    temp_file="$(mktemp "${TMPDIR:-/tmp}/anisubroid-release-gradle.XXXXXX")"

    while IFS= read -r line || [[ -n "$line" ]]; do
        if [[ "$found_name" == false && "$line" =~ ^([[:space:]]*versionName[[:space:]]*=[[:space:]]*)\"[^\"]+\"(.*)$ ]]; then
            printf '%s"%s"%s\n' "${BASH_REMATCH[1]}" "$VERSION_NAME" "${BASH_REMATCH[2]}" >> "$temp_file"
            found_name=true
        elif [[ "$found_code" == false && "$line" =~ ^([[:space:]]*versionCode[[:space:]]*=[[:space:]]*)[0-9]+(.*)$ ]]; then
            printf '%s%s%s\n' "${BASH_REMATCH[1]}" "$VERSION_CODE" "${BASH_REMATCH[2]}" >> "$temp_file"
            found_code=true
        else
            printf '%s\n' "$line" >> "$temp_file"
        fi
    done < "$GRADLE_FILE"

    [[ "$found_name" == true && "$found_code" == true ]] || fail "Failed to update versionName/versionCode in $GRADLE_FILE"
    mv "$temp_file" "$GRADLE_FILE"
}

restore_auto_stash() {
    local exit_code=$?
    trap - EXIT

    if [[ "$DID_AUTO_STASH" == true ]]; then
        printf '%s\n' "Restoring auto stashed tracked changes..."
        local stash_line stash_ref
        stash_line="$(git stash list --format='%gd%x09%s' | grep -F "$AUTO_STASH_NAME" | head -n 1 || true)"
        if [[ -n "$stash_line" ]]; then
            stash_ref="${stash_line%%$'\t'*}"
            if ! git stash pop "$stash_ref"; then
                printf '%s\n' "WARNING: git stash pop failed. Resolve manually with: git stash list" >&2
            else
                printf '%s\n' "Auto stash restored."
            fi
        else
            printf '%s\n' "WARNING: auto stash entry was not found. Check: git stash list" >&2
        fi
    fi

    exit "$exit_code"
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --version-name)
            VERSION_NAME="${2:-}"
            shift 2
            ;;
        --version-code)
            VERSION_CODE="${2:-}"
            shift 2
            ;;
        --app-name)
            APP_NAME="${2:-}"
            shift 2
            ;;
        --build-task)
            BUILD_TASK="${2:-}"
            shift 2
            ;;
        --apk-path)
            APK_PATH="${2:-}"
            shift 2
            ;;
        --branch)
            BRANCH="${2:-}"
            shift 2
            ;;
        --skip-build)
            SKIP_BUILD=true
            shift
            ;;
        --retry-build)
            RETRY_BUILD=true
            shift
            ;;
        --skip-push)
            SKIP_PUSH=true
            shift
            ;;
        --create-github-release)
            CREATE_GITHUB_RELEASE=true
            shift
            ;;
        --repo)
            REPO="${2:-}"
            shift 2
            ;;
        --release-asset-path)
            RELEASE_ASSET_PATH="${2:-}"
            shift 2
            ;;
        --release-title)
            RELEASE_TITLE="${2:-}"
            shift 2
            ;;
        --release-notes)
            RELEASE_NOTES="${2:-}"
            shift 2
            ;;
        --dry-run)
            DRY_RUN=true
            shift
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            printf 'Unknown or incomplete option: %s\n' "$1" >&2
            usage >&2
            exit 2
            ;;
    esac
done

[[ -n "$VERSION_NAME" ]] || fail "--version-name is required."
[[ "$VERSION_NAME" != *$'\n'* ]] || fail "--version-name must not contain a newline."
[[ "$APP_NAME" != *'/'* && "$APP_NAME" != *$'\n'* ]] || fail "--app-name must not contain / or a newline."
[[ "$BRANCH" != *' '* && "$BRANCH" != *$'\n'* ]] || fail "--branch must not contain spaces or a newline."
[[ "$VERSION_CODE" == "" || "$VERSION_CODE" =~ ^[1-9][0-9]*$ ]] || fail "--version-code must be a positive integer."
[[ "$RETRY_BUILD" == false || "$BUILD_TASK" == "assembleDebug" ]] || fail "--retry-build only supports the default --build-task assembleDebug."

GRADLE_FILE="$PROJECT_ROOT/app/build.gradle.kts"
[[ -f "$GRADLE_FILE" ]] || fail "Gradle file was not found: $GRADLE_FILE"
TAG_NAME="v$VERSION_NAME"
DEFAULT_APK_PATH="$PROJECT_ROOT/app/build/outputs/apk/debug/app-debug.apk"

if [[ -z "$APK_PATH" ]]; then
    APK_PATH="$DEFAULT_APK_PATH"
elif [[ "$APK_PATH" != /* ]]; then
    APK_PATH="$PROJECT_ROOT/$APK_PATH"
fi

configure_local_proxy
if [[ "$SKIP_BUILD" == false ]]; then
    require_java17
    require_android_sdk
fi

cd "$PROJECT_ROOT"
git rev-parse --is-inside-work-tree >/dev/null 2>&1 || fail "Project root is not a Git worktree."
[[ "$(git branch --show-current)" == "$BRANCH" ]] || fail "Current branch is $(git branch --show-current); expected --branch $BRANCH."
[[ -n "$(git config user.name)" && -n "$(git config user.email)" ]] || fail "Git user.name/user.email is not configured for this repository."

git remote get-url origin >/dev/null 2>&1 || fail "Git remote origin is not configured."
read_version_info

TAG_ALREADY_EXISTS=false
if git rev-parse --verify --quiet "refs/tags/$TAG_NAME" >/dev/null; then
    TAG_ALREADY_EXISTS=true
    if [[ "$CURRENT_VERSION_NAME" != "$VERSION_NAME" ]]; then
        fail "Tag $TAG_NAME already exists, but build.gradle.kts declares versionName $CURRENT_VERSION_NAME. Refusing to change release metadata in republish mode."
    fi
fi

if [[ -z "$VERSION_CODE" ]]; then
    if [[ "$TAG_ALREADY_EXISTS" == true ]]; then
        VERSION_CODE="$CURRENT_VERSION_CODE"
    else
        VERSION_CODE=$((CURRENT_VERSION_CODE + 1))
    fi
fi

if [[ "$TAG_ALREADY_EXISTS" == true && "$CURRENT_VERSION_CODE" != "$VERSION_CODE" ]]; then
    fail "Tag $TAG_NAME already exists, but build.gradle.kts declares versionCode $CURRENT_VERSION_CODE rather than requested $VERSION_CODE."
fi

if [[ "$CREATE_GITHUB_RELEASE" == true && "$SKIP_PUSH" == true && "$TAG_ALREADY_EXISTS" == false ]]; then
    fail "--create-github-release cannot be used with --skip-push for a new local tag."
fi

printf 'VersionName=%s\nVersionCode=%s\nTag=%s\nAPK=%s\n' "$VERSION_NAME" "$VERSION_CODE" "$TAG_NAME" "$APK_PATH"

if [[ "$DRY_RUN" == true ]]; then
    printf '%s\n' "Dry-run: no build, version update, archive, commit, tag, push, or GitHub Release action will be performed."
    if [[ "$TAG_ALREADY_EXISTS" == false ]]; then
        printf 'Would update: %s\n' "$GRADLE_FILE"
        printf 'Would create: commit "Release %s" and annotated tag %s\n' "$TAG_NAME" "$TAG_NAME"
    else
        printf 'Republish mode: %s already exists; commit and tag creation would be skipped.\n' "$TAG_NAME"
    fi
    printf 'Would archive APK to: %s/release/%s-v%s.apk\n' "$PROJECT_ROOT" "$APP_NAME" "$VERSION_NAME"
    [[ "$SKIP_PUSH" == true ]] || printf 'Would push: origin %s and %s\n' "$BRANCH" "$TAG_NAME"
    [[ "$CREATE_GITHUB_RELEASE" == true ]] && printf 'Would create/update GitHub Release in %s.\n' "$REPO"
    exit 0
fi

AUTO_STASH_NAME="release-git.sh-auto-stash-$(date +%s)"
tracked_status="$(git status --porcelain | awk '$0 !~ /^\?\?/ {print}')"
if [[ -n "$tracked_status" ]]; then
    printf '%s\n' "Detected tracked local changes. Auto stashing before release..."
    git stash push -m "$AUTO_STASH_NAME" >/dev/null
    DID_AUTO_STASH=true
    printf 'AutoStash=%s\n' "$AUTO_STASH_NAME"
fi
trap restore_auto_stash EXIT

if [[ -n "$(git status --porcelain | awk '$0 !~ /^\?\?/ {print}')" ]]; then
    fail "Git worktree is not clean after auto stash. Resolve manually and rerun."
fi

if [[ "$TAG_ALREADY_EXISTS" == false ]]; then
    update_version_info
    read_version_info
    [[ "$CURRENT_VERSION_NAME" == "$VERSION_NAME" && "$CURRENT_VERSION_CODE" == "$VERSION_CODE" ]] || fail "Version update verification failed."
fi

if [[ "$SKIP_BUILD" == false ]]; then
    ensure_local_properties
    if [[ "$RETRY_BUILD" == true ]]; then
        "$SCRIPT_DIR/build-debug-until-success.sh"
    else
        sh "$PROJECT_ROOT/gradlew" clean "$BUILD_TASK" --console=plain --stacktrace --no-daemon
    fi
fi

[[ -f "$APK_PATH" ]] || fail "APK was not found: $APK_PATH"
RELEASE_DIR="$PROJECT_ROOT/release"
ARTIFACT_NAME="$APP_NAME-v$VERSION_NAME.apk"
RELEASE_ASSET="$RELEASE_DIR/$ARTIFACT_NAME"
mkdir -p "$RELEASE_DIR"
cp -f "$APK_PATH" "$RELEASE_ASSET"
printf 'ReleaseAsset=%s\n' "$RELEASE_ASSET"

if [[ "$TAG_ALREADY_EXISTS" == false ]]; then
    git add "$GRADLE_FILE"
    git commit -m "Release $TAG_NAME"
    git tag -a "$TAG_NAME" -m "Release $TAG_NAME"

    if [[ "$SKIP_PUSH" == false ]]; then
        git push origin "$BRANCH"
        git push origin "$TAG_NAME"
    fi
else
    printf 'Skip commit/tag because %s already exists.\n' "$TAG_NAME"
fi

if [[ "$CREATE_GITHUB_RELEASE" == true ]]; then
    command -v gh >/dev/null 2>&1 || fail "GitHub CLI (gh) is required for --create-github-release. Run: ./wsl-scripts/init-android-env.sh --install-gh"
    gh auth status --hostname github.com >/dev/null

    UPLOAD_ASSET="${RELEASE_ASSET_PATH:-$RELEASE_ASSET}"
    [[ "$UPLOAD_ASSET" = /* ]] || UPLOAD_ASSET="$PROJECT_ROOT/$UPLOAD_ASSET"
    [[ -f "$UPLOAD_ASSET" ]] || fail "Release asset was not found: $UPLOAD_ASSET"
    FINAL_RELEASE_TITLE="${RELEASE_TITLE:-v$VERSION_NAME}"
    FINAL_RELEASE_NOTES="${RELEASE_NOTES:-$APP_NAME $VERSION_NAME

Assets:
- $(basename "$UPLOAD_ASSET")}"

    if gh release view "$TAG_NAME" --repo "$REPO" >/dev/null 2>&1; then
        gh release upload "$TAG_NAME" "$UPLOAD_ASSET" --clobber --repo "$REPO"
        printf 'Updated existing GitHub Release asset for %s.\n' "$TAG_NAME"
    else
        gh release create "$TAG_NAME" "$UPLOAD_ASSET" --repo "$REPO" --title "$FINAL_RELEASE_TITLE" --notes "$FINAL_RELEASE_NOTES" --verify-tag
        printf 'Created GitHub Release for %s.\n' "$TAG_NAME"
    fi
fi

printf 'Release preparation complete.\nTag=%s\n' "$TAG_NAME"
