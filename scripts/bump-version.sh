#!/usr/bin/env bash
# bump-version.sh - set versionName and versionCode for a release tag.
#
# Usage: scripts/bump-version.sh vMAJOR.MINOR.PATCH
#
# versionName becomes the tag without the leading "v" and versionCode becomes the
# current value plus one. The version must not be lower than the current one and
# the tag must not exist yet. On success it prints name=... and code=...

set -euo pipefail

die() {
    echo "bump-version: $*" >&2
    exit 1
}

tag="${1:-}"
[ -n "$tag" ] || die "usage: bump-version.sh vMAJOR.MINOR.PATCH"

tag_pattern='^v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$'
[[ "$tag" =~ $tag_pattern ]] || die "the tag must look like v1.2.3 (numbers only, no leading zeros)"

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
script="$root/scripts/version.sh"

new="${tag#v}"
current_name="$(bash "$script" get-name)"
current_code="$(bash "$script" get-code)"

name_pattern='^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$'
[[ "$current_name" =~ $name_pattern ]] || die "the current versionName '$current_name' is not MAJOR.MINOR.PATCH"

if git -C "$root" rev-parse -q --verify "refs/tags/$tag" > /dev/null; then
    die "the tag $tag already exists"
fi

IFS=. read -r nmaj nmin npat <<< "$new"
IFS=. read -r cmaj cmin cpat <<< "$current_name"
if (( nmaj < cmaj || (nmaj == cmaj && (nmin < cmin || (nmin == cmin && npat < cpat))) )); then
    die "$new is lower than the current version $current_name"
fi

code=$((current_code + 1))
bash "$script" set "$new" "$code"

echo "name=$new"
echo "code=$code"