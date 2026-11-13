#!/usr/bin/env bash
# Vendor the MailVerdict server's published API contract snapshot at a given commit.
#
# The only writer of contract/src/test/resources/api-contract/{openapi.json,sse-events.json,
# SYNCED_AT}; never edit those by hand. SYNCED_AT records the sha the snapshot was taken from.
#
# Usage: tooling/sync-contract.sh <mail-verdict-sha>

set -euo pipefail

if [ $# -ne 1 ]; then
    echo "usage: $0 <mail-verdict-sha>" >&2
    exit 1
fi

sha="$1"
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
dest="$root/contract/src/test/resources/api-contract"
base_url="https://raw.githubusercontent.com/frederikberg/mail-verdict/$sha/docs/api-contract"

mkdir -p "$dest"

for name in openapi.json sse-events.json; do
    echo "fetching $name @ $sha"
    curl -fsSL "$base_url/$name" -o "$dest/$name"
done

echo "$sha" > "$dest/SYNCED_AT"
echo "vendored $dest against $sha"
