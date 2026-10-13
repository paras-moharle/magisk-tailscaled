#!/usr/bin/env bash
# Build the installable module zip.
#
#   builder/build-module.sh            slim zip (installer downloads tailscaled)
#   builder/build-module.sh --full     also bundle the daemon + jq, for offline installs
#
# The app APK is always bundled and installed by customize.sh, so a flash gives
# you a working interface with no second download.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
root="$(dirname "$here")"
dist="$root/dist"
stage="$(mktemp -d)"
trap 'rm -rf "$stage"' EXIT

FULL=0
[ "${1:-}" = "--full" ] && FULL=1

version="$(sed -n 's/^version=//p' "$root/module.prop")"
name="Magisk-Tailscaled-${version}"
arch="arm64"

say() { printf '%s\n' "$*"; }

# --- app ---------------------------------------------------------------------
if [ ! -f "$root/share-app/build/TailBridge.apk" ]; then
	say "==> building TailBridge"
	"$root/share-app/build.sh" >/dev/null
fi
apk="$root/share-app/build/TailBridge.apk"
[ -f "$apk" ] || { say "!! app APK missing"; exit 1; }

# --- stage -------------------------------------------------------------------
say "==> staging"
mkdir -p "$stage/app" "$stage/tailscale/scripts" "$stage/META-INF/com/google/android"
cp "$root/module.prop" "$root/customize.sh" "$root/service.sh" \
   "$root/uninstall.sh" "$root/LICENSE" "$stage/"
cp -r "$root/META-INF/com/google/android/." "$stage/META-INF/com/google/android/"
cp "$root/tailscale/settings.sh" "$stage/tailscale/"
cp "$root/tailscale/scripts/"*.sh "$root/tailscale/scripts/tailscaled.inotify" \
   "$root/tailscale/scripts/tailscaled.service" "$root/tailscale/scripts/tailscaled.api" \
   "$root/tailscale/scripts/tailscaled.update" "$stage/tailscale/scripts/" 2>/dev/null || \
cp "$root/tailscale/scripts/"* "$stage/tailscale/scripts/"
cp "$apk" "$stage/app/TailBridge.apk"

# --- sanity: never ship a package that cannot install -----------------------
chmod +x "$stage/tailscale/scripts/"*
fail() { say "!! $1"; exit 1; }

# Everything Magisk/KernelSU runs at install time or boot must parse, or the
# device silently ends up with a half-installed module.
for f in "$stage"/*.sh "$stage/tailscale/settings.sh" "$stage/tailscale/scripts/"*; do
	[ -f "$f" ] || continue
	sh -n "$f" 2>/dev/null || fail "shell syntax error in ${f#$stage/}"
done

# A stray CR turns every line into a syntax error on the device. Only text
# files are checked; the APK is binary and will of course match.
crlf=$(find "$stage" -type f \
	\( -name '*.sh' -o -name '*.prop' -o -name '*.json' -o -name '*.md' -o -name 'LICENSE' \) \
	-exec grep -lU $'\r' {} + 2>/dev/null || true)
[ -z "$crlf" ] || fail "CRLF line endings in: $(echo "$crlf" | tr '\n' ' ')"

# The WebUI was removed; make sure nothing brings it back.
[ -e "$stage/webroot" ] && fail "webroot/ must not ship"
[ -e "$stage/action.sh" ] && fail "action.sh must not ship (it only existed for the WebUI)"

for req in module.prop customize.sh service.sh uninstall.sh \
	   META-INF/com/google/android/update-binary \
	   META-INF/com/google/android/updater-script \
	   tailscale/settings.sh app/TailBridge.apk; do
	[ -e "$stage/$req" ] || fail "missing $req"
done

# The APK is the only interface this fork has, so an empty one is a broken zip.
[ -s "$stage/app/TailBridge.apk" ] || fail "app APK is empty"
unzip -l "$stage/app/TailBridge.apk" | grep -q 'classes.dex' || fail "app APK has no classes.dex"

say "    checks passed"

say "==> zipping"
mkdir -p "$dist"
( cd "$stage" && zip -qr9 "$dist/${name}.zip" . -x '.*' )

if [ "$FULL" = 1 ]; then
	# The slim zip above must stay slim: the installer downloads tailscaled and
	# jq from the release feed so a version bump does not need a new module.
	seed="$here/seed-bin"
	[ -d "$seed" ] || { say "!! --full needs builder/seed-bin/{tailscaled,jq}"; exit 1; }
	mkdir -p "$stage/tailscale/bin"
	cp "$seed/tailscaled" "$stage/tailscale/bin/tailscaled-${arch}"
	cp "$seed/jq" "$stage/tailscale/bin/jq-${arch}"
	( cd "$stage" && zip -qr9 "$dist/${name}-full.zip" . -x '.*' )
fi

for z in "$dist/${name}.zip" "$dist/${name}-full.zip"; do
	[ -f "$z" ] || continue
	say ""
	say "==> $(basename "$z")  ($(du -h "$z" | cut -f1))"
	unzip -l "$z" | grep -E 'module.prop|TailBridge|customize.sh|settings.sh|bin/' || true
done
say ""
say "Done."