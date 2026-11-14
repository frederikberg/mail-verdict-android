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

# The Firebase configuration: a file naming another app, broken JSON and an empty input are refused.
mkdir -p "$tmp/gs/app"
printf 'android {\n    defaultConfig {\n        applicationId = "com.example.app"\n    }\n}\n' > "$tmp/gs/app/build.gradle.kts"
good_json='{"client":[{"client_info":{"mobilesdk_app_id":"1:1:android:other","android_client_info":{"package_name":"com.example.other"}}},{"client_info":{"mobilesdk_app_id":"1:1:android:abc123","android_client_info":{"package_name":"com.example.app"}}}]}'
gs() { REPO_ROOT="$tmp/gs" "$here/google_services.sh" "$@"; }
install_gs() { printf '%s' "$1" | gs install "$tmp/gs/out.json"; }

expect pass "google services, a file naming this app installs" install_gs "$good_json"
printf '%s' "$good_json" > "$tmp/gs/good.json"
if [ "$(gs app-id "$tmp/gs/good.json")" != "1:1:android:abc123" ]; then
    echo "FAIL google services returned the wrong app id (it picked another app's client)"
    failures=$((failures + 1))
else
    echo "ok   google services returns the app id of this application's client"
fi
if [ "$(stat -c %a "$tmp/gs/out.json")" != "600" ]; then
    echo "FAIL google services wrote the file readable by others"
    failures=$((failures + 1))
else
    echo "ok   google services writes the file for its owner only"
fi
expect fail "google services, a file naming only another app" install_gs '{"client":[{"client_info":{"mobilesdk_app_id":"1:1:android:x","android_client_info":{"package_name":"com.example.other"}}}]}'
expect fail "google services, broken JSON" install_gs '{"client":'
expect fail "google services, empty input" install_gs ''
expect fail "google services, app id of a file naming another app" gs app-id "$tmp/gs/missing.json"
if [ "$(gs application-id)" != "com.example.app" ]; then
    echo "FAIL google services did not print the application id"
    failures=$((failures + 1))
else
    echo "ok   google services prints the application id"
fi

# The Play release notes: bullets, the 500-character limit, a cut that never splits an entry.
long_entry="$(printf 'word %.0s' $(seq 1 130))"
play_changelog=$'# Changelog\n\n## [Unreleased]\n\n## [2.0.0] - 2026-01-01\n\n### Added\n\n- First change that is\n  wrapped over two lines.\n- Second change.\n\n### Fixed\n\n- Third change.\n\n## [1.0.0] - 2025-01-01\n\n- '"$long_entry"$'\n- Never reached.\n'
fixture play "appVersionName=2.0.0" "$play_changelog"
play() { REPO_ROOT="$tmp/$1" "$here/play_release_notes.sh" "${@:2}"; }
if [ "$(play play 2.0.0)" != $'• First change that is wrapped over two lines.\n• Second change.\n• Third change.' ]; then
    echo "FAIL play notes are not one bullet per entry"
    failures=$((failures + 1))
else
    echo "ok   play notes are one bullet per entry, headings dropped, wrapped lines joined"
fi
cut_notes="$(play play 1.0.0)"
if [ "${#cut_notes}" -gt 500 ] || [[ "$cut_notes" == *"Never reached"* ]] || [[ "$cut_notes" != *… ]]; then
    echo "FAIL play notes are not cut to 500 characters (${#cut_notes})"
    failures=$((failures + 1))
else
    echo "ok   play notes stay within 500 characters"
fi
expect fail "play notes, unknown version" play play 9.9.9

if [ "$failures" -gt 0 ]; then
    echo "$failures check(s) failed" >&2
    exit 1
fi
