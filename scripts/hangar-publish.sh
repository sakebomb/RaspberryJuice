#!/usr/bin/env bash
# Upload a plugin jar as a new version of a Hangar project (https://hangar.papermc.io).
#
# Usage: hangar-publish.sh <jar> <version> <mc-versions>
#   mc-versions is comma-separated, e.g. "26.2" or "26.1,26.2".
# Env:   HANGAR_API_KEY (required), HANGAR_PROJECT (slug, required),
#        HANGAR_CHANNEL (default Release), HANGAR_URL (default https://hangar.papermc.io).
#
# Follows the documented v1 API: exchange the API key for a JWT, then POST a multipart upload.
set -euo pipefail

if [[ $# -ne 3 ]]; then
  echo "usage: $0 <jar> <version> <mc-versions>" >&2
  exit 2
fi
jar=$1 version=$2 mc_versions=$3
: "${HANGAR_API_KEY:?HANGAR_API_KEY is not set}"
: "${HANGAR_PROJECT:?HANGAR_PROJECT is not set}"
channel=${HANGAR_CHANNEL:-Release}
base=${HANGAR_URL:-https://hangar.papermc.io}
ua="RaspberryJuice-release (+https://github.com/sakebomb/RaspberryJuice)"

[[ -f $jar ]] || { echo "jar not found: $jar" >&2; exit 1; }

# The key goes in the query string because that is the only form the API accepts. curl reads
# the URL from stdin so the key never appears in the process list.
jwt=$(printf 'url = "%s/api/v1/authenticate?apiKey=%s"\n' "$base" "$HANGAR_API_KEY" \
  | curl --fail-with-body -sS -X POST -A "$ua" -K - \
  | jq -er .token) || { echo "Hangar authentication failed" >&2; exit 1; }

upload=$(jq -nc \
  --arg version "$version" \
  --arg channel "$channel" \
  --arg mc "$mc_versions" \
  '{
    version: $version,
    channel: $channel,
    description: "See the GitHub release for the changelog.",
    files: [{platforms: ["PAPER"]}],
    platformDependencies: {PAPER: ($mc | split(","))},
    pluginDependencies: {}
  }')

curl --fail-with-body -sS -X POST -A "$ua" \
  -H "Authorization: $jwt" \
  -F "versionUpload=${upload};type=application/json" \
  -F "files=@${jar};type=application/java-archive" \
  "$base/api/v1/projects/$HANGAR_PROJECT/upload"
echo
echo "Published $version to Hangar project $HANGAR_PROJECT"
