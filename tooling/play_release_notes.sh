#!/usr/bin/env bash
# Prints the CHANGELOG.md section of one version as the plain text Google Play shows as "what's new":
# one bullet per entry, headings dropped, and cut after the last entry that fits the 500 characters
# Play allows (a first entry that alone is longer is cut itself). Fails like extract_changelog.sh when
# the version has no section.
#
# Usage: tooling/play_release_notes.sh X.Y.Z   (a leading v is accepted)
# REPO_ROOT overrides the repository the changelog is read from.

set -euo pipefail
export LC_ALL=C.UTF-8

if [ $# -ne 1 ]; then
    echo "usage: $0 X.Y.Z" >&2
    exit 1
fi

limit=500
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
notes="$("$here/extract_changelog.sh" "$1")"

entries=()
current=""
flush() {
    if [ -n "$current" ]; then entries+=("$current"); fi
    current=""
}
while IFS= read -r line; do
    case "$line" in
        "- "*) flush; current="${line#- }" ;;
        "  "*) current+=" ${line#"${line%%[![:space:]]*}"}" ;;
        *) flush ;;
    esac
done <<< "$notes"
flush

out=""
for entry in "${entries[@]}"; do
    line="• $entry"
    candidate="${out:+$out$'\n'}$line"
    if [ "${#candidate}" -le "$limit" ]; then
        out="$candidate"
    elif [ -z "$out" ]; then
        out="${line:0:$((limit - 1))}…"
        break
    else
        break
    fi
done
printf '%s\n' "$out"
