#!/usr/bin/env bash
# Fetch the jars the in-game e2e test needs into e2e/.server (gitignored):
#   - the latest Paper 26.2 build, checked against the SHA-256 the Paper API publishes;
#   - ViaVersion + ViaBackwards, pinned by SHA-512, so the 26.1 Mineflayer bots can join.
#     They exist only on this test server and are never shipped with the plugin.
#   - the plugin jar from target/ (run ./mvnw package first).
set -euo pipefail

cd "$(dirname "$0")"
SERVER=.server
UA="raspberryjuice-e2e/1.0"
VIA_VERSION_URL="https://cdn.modrinth.com/data/P1OZGk5p/versions/FaishMnD/ViaVersion-5.12.0.jar"
VIA_VERSION_SHA512="2dfe562109179f08685dc84a66aedf7c810592223b5287b6355ba1e741d1737d015ca0c444b4bc01e5db2a4b10549d5028bf2e3f3337219ded709e44b579a4d8"
VIA_BACKWARDS_URL="https://cdn.modrinth.com/data/NpvuJQoq/versions/SxGhdsPK/ViaBackwards-5.12.0.jar"
VIA_BACKWARDS_SHA512="dba076b3283eb5987e3d37a63b57802e7946cf8a2d3b6f68ff57ddbc4eb648e2084decd212fef2cd0cde5187bc6565959c31b496bfb38000e67e3ffab4c290b3"

mkdir -p "$SERVER/plugins"

read -r paper_url paper_sha256 < <(curl -sf -H "User-Agent: $UA" \
	"https://fill.papermc.io/v3/projects/paper/versions/26.2/builds/latest" \
	| python3 -c "import sys,json; d=json.load(sys.stdin)['downloads']['server:default']; print(d['url'], d['checksums']['sha256'])")
curl -sf -H "User-Agent: $UA" -o "$SERVER/paper.jar" "$paper_url"
echo "$paper_sha256  $SERVER/paper.jar" | sha256sum -c -

fetch_pinned() {
	local url=$1 sha512=$2 dest=$3
	curl -sf -H "User-Agent: $UA" -o "$dest" "$url"
	echo "$sha512  $dest" | sha512sum -c -
}
fetch_pinned "$VIA_VERSION_URL" "$VIA_VERSION_SHA512" "$SERVER/plugins/ViaVersion.jar"
fetch_pinned "$VIA_BACKWARDS_URL" "$VIA_BACKWARDS_SHA512" "$SERVER/plugins/ViaBackwards.jar"

rm -f "$SERVER"/plugins/raspberryjuice-*.jar
shopt -s nullglob
jars=(../target/raspberryjuice-*.jar)
if [ ${#jars[@]} -ne 1 ]; then
	echo "Expected exactly one target/raspberryjuice-*.jar; run ./mvnw -B package -DskipTests first." >&2
	exit 1
fi
cp "${jars[0]}" "$SERVER/plugins/"
echo "e2e server ready in e2e/$SERVER"
