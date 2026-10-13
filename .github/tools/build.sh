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

# Fetch the release once; a missing asset for one arch must not abort the build.
echo "- Querying ${TS_BIN_REPO}"
RELEASE_JSON="$(curl -fsSL "https://api.github.com/repos/${TS_BIN_REPO}/releases/latest")" || {
	echo "!! Could not reach ${TS_BIN_REPO}" >&2
	exit 1
}

AVAILABLE_ARCH=""
for ARCH in arm64 arm; do
	URL="$(jq -r --arg re "^tailscale_[0-9.]+_${ARCH}\\.tgz$" \
		'.assets[]? | select(.name | test($re)) | .browser_download_url' <<<"$RELEASE_JSON" | head -1)"

	if [ -z "$URL" ]; then
		echo "  ! no ${ARCH} binary published; skipping"
		continue
	fi

	echo "- Downloading tailscaled for ${ARCH}..."
	curl -fsSL "$URL" | tar -xz -C "$BIN_DIR" --transform="s|^tailscale|tailscale|" \
		|| {
		echo "!! failed to fetch ${ARCH}" >&2
		exit 1
	}
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

# build-time tooling must not ship inside the flashable zip
ZIP_EXCL=("*.git*" "dist/*" "*.zip" "*.json" "*.md" "builder/*" ".github/*" ".shellcheckrc")

echo "- Creating lightweight zip (downloads binaries on install)"
zip -9 -qr "${DIST_DIR}/Magisk-Tailscaled-${VERSION}.zip" . \
	-x "${ZIP_EXCL[@]}" "${BIN_DIR}/*"

echo "- Creating full zip (binaries bundled)"
if [ -n "$AVAILABLE_ARCH" ]; then
	# Same exclusions, but tailscale/bin is NOT excluded here — that is the
	# whole point of the full zip.
	zip -9 -qr "${DIST_DIR}/Magisk-Tailscaled-${VERSION}-full.zip" . \
		-x "${ZIP_EXCL[@]}"
else
	echo "  ! no binaries available; skipping full zip"
fi

rm -f "$BIN_DIR"/*

echo ""
echo "Build completed successfully!"
echo "========================================"
ls -lh "$DIST_DIR"
echo "========================================"
echo "Binaries embedded in full zip:${AVAILABLE_ARCH:- none}"