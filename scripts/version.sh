#!/usr/bin/env bash
# version.sh - read and write versionName and versionCode in app/build.gradle.kts
#
# Usage:
#   scripts/version.sh get-name
#   scripts/version.sh get-code
#   scripts/version.sh set <versionName> <versionCode>

set -euo pipefail

die() {
    echo "version: $*" >&2
    exit 1
}

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
file="$root/app/build.gradle.kts"
[ -f "$file" ] || die "app/build.gradle.kts was not found"

read_one() {
    local label="$1" pattern="$2" lines
    mapfile -t lines < <(sed -n "$pattern" "$file")
    [ "${#lines[@]}" -eq 1 ] || die "expected exactly one $label line in app/build.gradle.kts, found ${#lines[@]}"
    printf '%s\n' "${lines[0]}"
}

get_name() {
    read_one versionName 's/^[[:space:]]*versionName = "\([^"]*\)"[[:space:]]*$/\1/p'
}

get_code() {
    read_one versionCode 's/^[[:space:]]*versionCode = \([0-9][0-9]*\)[[:space:]]*$/\1/p'
}

case "${1:-}" in
    get-name)
        get_name
        ;;
    get-code)
        get_code
        ;;
    set)
        name="${2:-}"
        code="${3:-}"
        [[ "$name" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || die "invalid versionName: $name"
        [[ "$code" =~ ^[0-9]+$ ]] || die "invalid versionCode: $code"
        if [ "$code" -lt 1 ] || [ "$code" -gt 2100000000 ]; then
            die "versionCode must be between 1 and 2100000000"
        fi

        # Both lines must exist exactly once before the file is touched.
        get_name > /dev/null
        get_code > /dev/null

        sed -i -E "s/^([[:space:]]*versionName = \")[^\"]*(\")[[:space:]]*\$/\1${name}\2/" "$file"
        sed -i -E "s/^([[:space:]]*versionCode = )[0-9]+[[:space:]]*\$/\1${code}/" "$file"

        if [ "$(get_name)" != "$name" ] || [ "$(get_code)" != "$code" ]; then
            die "the file was not updated as expected"
        fi
        ;;
    *)
        die "usage: version.sh get-name | get-code | set <versionName> <versionCode>"
        ;;
esac