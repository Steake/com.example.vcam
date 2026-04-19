#!/usr/bin/env bash
#
# bumpversion.sh — bump the Android `versionName` (and auto-increment
# `versionCode`) in app/build.gradle, then create a matching annotated
# git tag that kicks off the tag-triggered release workflow.
#
# Usage:
#   ./bumpversion.sh <new-version-name>
#   ./bumpversion.sh --dry-run <new-version-name>
#   ./bumpversion.sh --no-tag  <new-version-name>    # bump only, don't tag
#
# Examples:
#   ./bumpversion.sh 4.9
#   ./bumpversion.sh 5.0
#
# Behaviour:
#   - Reads the current versionName / versionCode from app/build.gradle
#   - Refuses to go backwards (versionName must compare greater than current)
#   - Increments versionCode by 1
#   - Rewrites app/build.gradle in place
#   - Stages + commits the change ("chore: bump version to vX.Y")
#   - Creates an annotated tag "vX.Y" pointing at that commit
#
# Pushing the tag (`git push origin vX.Y`) is left to the caller.

set -euo pipefail

DRY_RUN=0
CREATE_TAG=1

while [[ $# -gt 0 ]]; do
    case "$1" in
        --dry-run)
            DRY_RUN=1
            shift
            ;;
        --no-tag)
            CREATE_TAG=0
            shift
            ;;
        -h|--help)
            sed -n '2,/^$/p' "$0" | sed 's/^# \{0,1\}//'
            exit 0
            ;;
        -*)
            echo "unknown flag: $1" >&2
            exit 2
            ;;
        *)
            NEW_VERSION="$1"
            shift
            ;;
    esac
done

if [[ -z "${NEW_VERSION:-}" ]]; then
    echo "usage: $0 [--dry-run] [--no-tag] <new-version-name>" >&2
    exit 2
fi

# Semver-ish: digits and dots only (e.g. 4.9, 5.0, 10.12.3).
if ! [[ "$NEW_VERSION" =~ ^[0-9]+(\.[0-9]+)+$ ]]; then
    echo "error: version must be dotted numeric (e.g. 4.9, 5.0.1) — got '$NEW_VERSION'" >&2
    exit 1
fi

repo_root="$(cd "$(dirname "$0")" && pwd)"
gradle_file="$repo_root/app/build.gradle"

if [[ ! -f "$gradle_file" ]]; then
    echo "error: $gradle_file not found" >&2
    exit 1
fi

current_name="$(grep -E '^\s*versionName\s+' "$gradle_file" \
    | head -n1 \
    | sed -E 's/.*versionName[[:space:]]+"([^"]+)".*/\1/')"
current_code="$(grep -E '^\s*versionCode\s+' "$gradle_file" \
    | head -n1 \
    | sed -E 's/.*versionCode[[:space:]]+([0-9]+).*/\1/')"

if [[ -z "$current_name" || -z "$current_code" ]]; then
    echo "error: could not parse versionName / versionCode from $gradle_file" >&2
    exit 1
fi

# Refuse to go backwards. `sort -V` gives natural version ordering.
if [[ "$NEW_VERSION" == "$current_name" ]]; then
    echo "error: versionName is already $current_name — nothing to do" >&2
    exit 1
fi
lowest="$(printf '%s\n%s\n' "$current_name" "$NEW_VERSION" | sort -V | head -n1)"
if [[ "$lowest" == "$NEW_VERSION" ]]; then
    echo "error: new version $NEW_VERSION is not greater than current $current_name" >&2
    exit 1
fi

new_code=$((current_code + 1))

echo "Current: versionName=$current_name versionCode=$current_code"
echo "New:     versionName=$NEW_VERSION versionCode=$new_code"

if [[ $DRY_RUN -eq 1 ]]; then
    echo "(dry-run) not writing changes"
    exit 0
fi

# Rewrite in place. Keep the surrounding whitespace / indentation.
tmpfile="$(mktemp)"
awk -v new_name="$NEW_VERSION" -v new_code="$new_code" '
    /^[[:space:]]*versionName[[:space:]]+"/ {
        sub(/"[^"]+"/, "\"" new_name "\"")
    }
    /^[[:space:]]*versionCode[[:space:]]+[0-9]+/ {
        sub(/versionCode[[:space:]]+[0-9]+/, "versionCode " new_code)
    }
    { print }
' "$gradle_file" > "$tmpfile"
mv "$tmpfile" "$gradle_file"

# Sanity-check the rewrite actually took effect.
verify_name="$(grep -E '^\s*versionName\s+' "$gradle_file" | head -n1 | sed -E 's/.*"([^"]+)".*/\1/')"
verify_code="$(grep -E '^\s*versionCode\s+' "$gradle_file" | head -n1 | sed -E 's/.*versionCode[[:space:]]+([0-9]+).*/\1/')"
if [[ "$verify_name" != "$NEW_VERSION" || "$verify_code" != "$new_code" ]]; then
    echo "error: rewrite failed (got name=$verify_name code=$verify_code)" >&2
    exit 1
fi

# Refuse to run if the user has unrelated changes already staged, so the
# version-bump commit stays pure.
if ! git -C "$repo_root" diff --cached --quiet; then
    echo "error: you have staged changes; commit or reset them before running bumpversion.sh" >&2
    exit 1
fi

git -C "$repo_root" add -- app/build.gradle
# Pathspec-limited commit: only include app/build.gradle even if something
# else sneaks into the index between the check above and now.
git -C "$repo_root" commit -m "chore: bump version to v$NEW_VERSION" -- app/build.gradle

if [[ $CREATE_TAG -eq 1 ]]; then
    tag="v$NEW_VERSION"
    if git -C "$repo_root" rev-parse "$tag" >/dev/null 2>&1; then
        echo "error: tag $tag already exists" >&2
        exit 1
    fi
    git -C "$repo_root" tag -a "$tag" -m "Release $tag"
    echo "Tagged $tag. Push with: git push origin $tag"
else
    echo "Committed bump. Tag not created (--no-tag)."
fi
