#!/usr/bin/env bash
# Runs the release scripts against good and deliberately broken repositories and fails if any of
# them answers wrongly. A guard whose success is silence is only trusted once it has been seen to
# fail.

set -uo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
tmp="$(mktemp -d)"
trap 'find "$tmp" -delete' EXIT
failures=0

# fixture <name> <appVersionName line> <changelog body>: writes a repository under $tmp/<name>.
fixture() {
    mkdir -p "$tmp/$1"
    printf '%s\n' "$2" > "$tmp/$1/gradle.properties"
    printf '%s\n' "$3" > "$tmp/$1/CHANGELOG.md"
}

# expect <pass|fail> <description> <command...>
expect() {
    local want="$1" description="$2"
    shift 2
    if "$@" > /dev/null 2>&1; then got=pass; else got=fail; fi
    if [ "$got" = "$want" ]; then
        echo "ok   $description"
    else
        echo "FAIL $description (expected $want, got $got)"
        failures=$((failures + 1))
    fi
}

changelog=$'# Changelog\n\n## [Unreleased]\n\n## [1.2.3] - 2026-01-01\n\n### Added\n\n- Something.\n\n## [1.2.2] - 2025-12-01\n\n- Older.\n\n## [1.2.0] - 2025-11-01\n'
fixture good "appVersionName=1.2.3" "$changelog"
fixture bad-semver "appVersionName=1.2" "$changelog"
fixture no-section "appVersionName=1.2.4" "$changelog"
fixture empty-section "appVersionName=1.2.0" "$changelog"
fixture no-version "somethingElse=1" "$changelog"

check() { REPO_ROOT="$tmp/$1" "$here/check_release_versions.sh" "${@:2}"; }
extract() { REPO_ROOT="$tmp/$1" "$here/extract_changelog.sh" "${@:2}"; }

expect pass "version check, repository only" check good
expect pass "version check, matching tag" check good v1.2.3
expect fail "version check, tag differs from appVersionName" check good v1.2.4
expect fail "version check, appVersionName is not semver" check bad-semver
expect fail "version check, no changelog section for the tag" check no-section v1.2.4
expect fail "version check, appVersionName missing" check no-version

expect pass "changelog extract, existing section" extract good 1.2.3
expect pass "changelog extract, leading v" extract good v1.2.3
expect fail "changelog extract, unknown version" extract good 9.9.9
expect fail "changelog extract, empty section" extract empty-section 1.2.0

if [ "$(extract good 1.2.3)" != $'### Added\n\n- Something.' ]; then
    echo "FAIL changelog extract returned the wrong body"
    failures=$((failures + 1))
else
    echo "ok   changelog extract returns exactly the section body"
fi

if [ "$failures" -gt 0 ]; then
    echo "$failures check(s) failed" >&2
    exit 1
fi
