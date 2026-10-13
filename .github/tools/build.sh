#!/usr/bin/env bash
# build.sh — package the flashable module zips.
#
#   Magisk-Tailscaled-<ver>.zip       downloads tailscaled at install time
#   Magisk-Tailscaled-<ver>-full.zip  embeds tailscaled, no download needed
#
# The second form only differs when a binary for every requested architecture
# is actually published; otherwise it is skipped rather than shipping a zip
# that fails to install.
set -euo pipefail

cd "$(dirname "$0")/../.."

VERSION="${1:-$(grep '^version=' module.prop | cut -d= -f2)}"
DIST_DIR="dist"
BIN_DIR="tailscale/bin"
TS_BIN_REPO="${TS_BIN_REPO:-paras-moharle/tailscale-android-cli}"

mkdir -p "$BIN_DIR" "$DIST_DIR"

echo "Packaging Magisk-Tailscaled ${VERSION}"
echo "========================================"

# Discover binaries through the release-asset feed, not the GitHub API.
# Unauthenticated api.github.com allows 60 requests/hour per IP, and CI plus a
# phone behind CGNAT can easily exhaust that; /releases/latest/download/ is a
# plain redirect with no limit.
FEED_URL="${TS_BIN_FEED:-https://github.com/${TS_BIN_REPO}/releases/latest/download/latest.json}"

echo "- Querying ${FEED_URL}"
FEED_JSON="$(curl -fsSL "$FEED_URL" 2>/dev/null)" || FEED_JSON=""
if [ -z "$FEED_JSON" ]; then
	echo "!! Could not reach the release feed (${FEED_URL})" >&2
	echo "!! The GitHub API is an acceptable fallback for CI." >&2
	FEED_JSON="$(curl -fsSL "https://api.github.com/repos/${TS_BIN_REPO}/releases/latest" 2>/dev/null)" || {
		echo "!! Release feed and API both unreachable; cannot embed binaries." >&2
		exit 1
	}
fi

AVAILABLE_ARCH=""
for ARCH in arm64 arm; do
	# latest.json lists asset names; the GitHub API lists objects with URLs.
	# Accept either, and never let a jq hiccup abort the build under pipefail.
	NAME="$(jq -r --arg a "$ARCH" \
		'if (.assets|type)=="array" then
		   (.assets[] | select(.name | endswith("_" + $a + ".tgz")) | .name)
		 elif (.assets|type)=="object" then
		   (.assets | to_entries[] | select(.value | endswith("_" + $a + ".tgz")) | .value)
		 else empty end' <<<"$FEED_JSON" 2>/dev/null | head -1)" || NAME=""

	if [ -z "$NAME" ]; then
		echo "  ! no ${ARCH} binary published; skipping"
		continue
	fi

	URL="$(jq -r --arg n "$NAME" \
		'if (.assets|type)=="array" then
		   (.assets[] | select(.name == $n) | .browser_download_url)
		 else empty end' <<<"$FEED_JSON" 2>/dev/null | head -1)" || URL=""

	if [ -z "$URL" ]; then
		# release-asset feed: construct the stable download URL
		URL="https://github.com/${TS_BIN_REPO}/releases/latest/download/${NAME}"
	fi

	echo "- Downloading ${NAME}..."
	if ! curl -fsSL "$URL" | tar -xz -C "$BIN_DIR"; then
		echo "!! failed to fetch ${NAME}" >&2
		exit 1
	fi
	# The tgz holds a single file named `tailscaled`.
	if [ ! -f "$BIN_DIR/tailscaled" ]; then
		echo "!! archive for ${ARCH} did not contain tailscaled" >&2
		exit 1
	fi
	mv -f "$BIN_DIR/tailscaled" "$BIN_DIR/tailscaled-${ARCH}"
	chmod 0755 "$BIN_DIR/tailscaled-${ARCH}"
	AVAILABLE_ARCH="$AVAILABLE_ARCH $ARCH"
done

rm -f "$DIST_DIR"/Magisk-Tailscaled-*.zip

# Packaging lives in builder/build-module.sh so a CI build and a local build
# produce the same package. It stages an explicit file list (rather than zipping
# the whole tree, which used to ship share-app/ source but never the built APK),
# bundles TailBridge.apk, and refuses to emit a package that would fail to
# install.
echo "- Building TailBridge"
./share-app/build.sh

echo "- Packaging (slim + full)"
if [ -n "$AVAILABLE_ARCH" ]; then
	./builder/build-module.sh --full
else
	./builder/build-module.sh
fi

rm -f "$BIN_DIR"/*

echo ""
echo "TailBridge:  $(ls -lh share-app/build/TailBridge.apk 2>/dev/null | awk '{print $5}')"
echo ""
echo "Build completed successfully!"
echo "========================================"
ls -lh "$DIST_DIR"
echo "========================================"
echo "Binaries embedded in full zip:${AVAILABLE_ARCH:- none}"