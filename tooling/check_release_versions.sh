#!/usr/bin/env bash
# Verifies that every place a release version is written agrees.
#
# The version lives in gradle.properties (appVersionName); the app's versionCode is derived from it
# at build time. With a tag argument the script also checks the tag against it and that CHANGELOG.md
# has a section for that version.
#
# Usage: tooling/check_release_versions.sh [vX.Y.Z]
# REPO_ROOT overrides the repository the checks read (the self-test points it at fixtures).

set -euo pipefail

root="${REPO_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
tag="${1:-}"
problems=()

version="$(sed -n 's/^appVersionName=//p' "$root/gradle.properties")"
if [ -z "$version" ]; then
    echo "gradle.properties has no appVersionName" >&2
    exit 1
fi
echo "gradle.properties  $version"

if ! [[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.-]+)?$ ]]; then
    problems+=("appVersionName '$version' is not a semantic version")
fi

if [ -n "$tag" ]; then
    echo "git tag            $tag"
    if [ "${tag#v}" != "$version" ]; then
        problems+=("tag '$tag' does not match appVersionName '$version'")
    fi
    if ! grep -Eq "^## \[${tag#v}\]" "$root/CHANGELOG.md"; then
        problems+=("CHANGELOG.md has no section for ${tag#v}")
    fi
fi

if [ ${#problems[@]} -gt 0 ]; then
    echo >&2
    echo "Release version check FAILED:" >&2
    printf '  - %s\n' "${problems[@]}" >&2
    exit 1
fi
echo
echo "Release version check passed."
