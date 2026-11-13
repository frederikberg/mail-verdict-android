#!/usr/bin/env bash
# Re-records the Roborazzi screenshots and copies them to docs/images/.
#
# Usage: tooling/screenshots.sh [name ...]
#   With names, only app/build/outputs/roborazzi/<name>.png is copied; without, every PNG there.
# GRADLE_PREFIX may hold a command wrapped around ./gradlew (a CPU limiter, for instance).

set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
src="$root/app/build/outputs/roborazzi"
dest="$root/docs/images"

# shellcheck disable=SC2086
(cd "$root" && ${GRADLE_PREFIX:-} ./gradlew --console=plain :app:recordRoborazziDebug)

mkdir -p "$dest"
if [ $# -eq 0 ]; then
    for file in "$src"/*.png; do
        cp "$file" "$dest/"
        echo "copied $(basename "$file")"
    done
else
    for name in "$@"; do
        cp "$src/$name.png" "$dest/$name.png"
        echo "copied $name.png"
    done
fi
