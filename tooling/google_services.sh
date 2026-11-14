#!/usr/bin/env bash
# Handles the Firebase project configuration of a build (app/google-services.json), which is not in the
# repository: a build that has it delivers notifications through Google's push service, one without
# runs the helper-app path only.
#
# Usage:
#   tooling/google_services.sh install <destination>   reads the JSON on stdin, checks that it names this
#                                                      app, and writes it readable by the owner only
#   tooling/google_services.sh app-id <file>           prints the Firebase app id the file holds for this app
#   tooling/google_services.sh application-id          prints the application id of the app itself
#
# The application id is read from app/build.gradle.kts. REPO_ROOT overrides the repository it is read from.

set -euo pipefail

root="${REPO_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"

application_id="$(sed -n 's/^ *applicationId = "\(.*\)"$/\1/p' "$root/app/build.gradle.kts")"
if [ -z "$application_id" ]; then
    echo "app/build.gradle.kts has no applicationId" >&2
    exit 1
fi

# The Firebase app id of this application's client entry; empty when the file names none.
app_id_of() {
    jq -r --arg package "$application_id" '
        [.client[]? | select(.client_info.android_client_info.package_name == $package)
            | .client_info.mobilesdk_app_id][0] // empty
    ' "$1" 2> /dev/null || true
}

case "${1:-}" in
    install)
        [ $# -eq 2 ] || { echo "usage: $0 install <destination>" >&2; exit 1; }
        staged="$(mktemp)"
        trap 'rm -f "$staged"' EXIT
        cat > "$staged"
        if [ -z "$(app_id_of "$staged")" ]; then
            echo "the Firebase configuration is not valid JSON or has no client for $application_id" >&2
            exit 1
        fi
        install -m 600 "$staged" "$2"
        ;;
    app-id)
        [ $# -eq 2 ] || { echo "usage: $0 app-id <file>" >&2; exit 1; }
        id="$(app_id_of "$2")"
        if [ -z "$id" ]; then
            echo "$2 has no client for $application_id" >&2
            exit 1
        fi
        printf '%s\n' "$id"
        ;;
    application-id)
        printf '%s\n' "$application_id"
        ;;
    *)
        echo "usage: $0 install <destination> | app-id <file> | application-id" >&2
        exit 1
        ;;
esac
