#!/usr/bin/env bash
# Prints the CHANGELOG.md section of one version, without its heading, for use as a release body.
# Fails when the version has no section or the section is empty, so a release cannot publish blank.
#
# Usage: tooling/extract_changelog.sh X.Y.Z   (a leading v is accepted)
# REPO_ROOT overrides the repository the changelog is read from.

set -euo pipefail

if [ $# -ne 1 ]; then
    echo "usage: $0 X.Y.Z" >&2
    exit 1
fi

root="${REPO_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
version="${1#v}"

body="$(awk -v v="$version" '
    /^## / {
        if (found) exit
        if (index($0, "## [" v "]") == 1) { found = 1; next }
    }
    found { print }
' "$root/CHANGELOG.md")"

if ! grep -Eq "^## \[$version\]" "$root/CHANGELOG.md"; then
    echo "CHANGELOG.md has no section for $version" >&2
    exit 1
fi
if [ -z "$(printf '%s' "$body" | tr -d '[:space:]')" ]; then
    echo "CHANGELOG.md section for $version is empty" >&2
    exit 1
fi

# The command substitution dropped trailing blank lines; this drops the leading ones.
printf '%s\n' "$body" | sed '/./,$!d'
