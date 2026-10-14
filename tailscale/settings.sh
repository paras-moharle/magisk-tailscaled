#!/system/bin/sh
set -e

# Custom settings

# Set Magisk Tailscale module variables
# Must match `id` in module.prop: Magisk/KernelSU name the module folder
# after it, and the manager treats two modules with one id as the same.
TS_MOD_DIR="/data/adb/modules/paras-tailscaled"
export TS_MOD_PROP="${TS_MOD_DIR}/module.prop"

# Set tailscale directory variables
TS_DIR="/data/adb/tailscale"
TS_BIN_DIR="${TS_DIR}/bin"

# Where "what version is current?" is answered from. This is a release asset
# behind GitHub's /releases/latest/ redirect, not the GitHub API:
# unauthenticated api.github.com allows only 60 requests/hour per IP, and
# phones sit behind CGNAT where a whole carrier can share one address, so an
# API-based check gets 403'd for hours at a time.
# The feed is a file on the default branch, not /releases/latest/download/:
# this repo publishes BOTH module releases (v1.0.3) and daemon binaries
# (v1.105.0-android), so "latest" would flip between the two and break the
# check roughly half the time. One pinned URL cannot do that.
export TS_LATEST_URL="${TS_LATEST_URL:-https://raw.githubusercontent.com/paras-moharle/tailscaled-reborn/main/tailscale-latest.json}"

# Set path environment for busybox & other.
export PATH="${TS_DIR}/bin:${TS_DIR}/scripts:/data/adb/magisk:/data/adb/ksu/bin:$PATH:/system/bin:${TS_MOD_DIR}/system/bin"
export HOME="/data/adb/tailscale/" # Because tailscaled will write log to $HOME

# tailscaled inherits TMPDIR from whoever started it. Start it once from an app
# and it keeps pointing at that app's cache directory forever — and when that
# app is later uninstalled the path vanishes, incoming Taildrop files fail to
# stage, and every send reports success while nothing is ever written.
# Pin it inside the module so the daemon is independent of its launcher.
export TMPDIR="${TS_DIR}/tmp"
mkdir -p "$TMPDIR"

# Set tailscaled & tailscale configuration
export TS_DAEMON_CMD="tailscaled -no-logs-no-support"

# Set tailscaled directory variables
export TS_RUN_DIR="${TS_DIR}/run"

# Set tailscaled log variables
export TS_LOG_FILE="${TS_RUN_DIR}/tailscaled.log"
export TS_RUN_LOG_FILE="${TS_RUN_DIR}/runs.log"

# The tailscale CLI. Defined here rather than only in tailscaled.api so every
# script that sources settings.sh can reach it -- an undefined TS_CLI expands to
# nothing and turns "tailscale" into a no-op that silently succeeds.
export TS_CLI="${TS_BIN_DIR}/tailscale"

# Where Taildrop deliveries are placed so they show up in the Files app.
export TS_TAILDROP_DIR="${TS_TAILDROP_DIR:-/sdcard/Download/Tailscale}"

# ---------------------------------------------------------------------------
# CLI symlink
# ---------------------------------------------------------------------------
# The `tailscale` CLI is the same binary as `tailscaled`. Upstream's
# createTailscaleSymlink() tries to maintain this itself, but it derives the
# link from os.Executable(), and because we install atomically via rename the
# running daemon can end up resolving to a replaced inode. That has been
# observed leaving `tailscale -> tailscaled.prev`, which makes every
# `tailscale` invocation silently run the OLD binary (wrong version, wrong
# update feed). Repair it explicitly wherever we start or update the daemon.
ensure_cli_symlink() {
	[ -f "${TS_BIN_DIR}/tailscaled" ] || return 0
	[ "$(readlink "${TS_BIN_DIR}/tailscale" 2>/dev/null || true)" = "tailscaled" ] && return 0
	ln -sf "tailscaled" "${TS_BIN_DIR}/tailscale" 2>/dev/null || true
	return 0
}

# ts_fetch <url> — print a URL to stdout.
#
# A stock Android kernel has no standalone wget/curl; busybox provides one.
# IMPORTANT: this busybox build does NOT validate TLS certificates. Only use
# this for unauthenticated, non-sensitive reads such as the version feed.
# Never use it to download binaries — those go through the Go updater inside
# tailscaled, which does verify certificates.
ts_fetch() {
	if command -v curl >/dev/null 2>&1; then
		timeout 30 curl -fsSL "$1" 2>/dev/null
	elif command -v wget >/dev/null 2>&1; then
		timeout 30 wget -qO- "$1" 2>/dev/null
	elif command -v busybox >/dev/null 2>&1; then
		timeout 30 busybox wget -qO- "$1" 2>/dev/null
	else
		return 1
	fi
}

# latest_version — newest published Android build, or empty if unreachable.
latest_version() {
	_json="$(ts_fetch "$TS_LATEST_URL")" || return 1
	[ -n "$_json" ] || return 1
	printf '%s' "$_json" | "${TS_BIN_DIR}/jq" -r '.version // empty' 2>/dev/null
}

# When the current feed entry was published, as a unix timestamp.
#
# An epoch integer rather than ISO-8601 on purpose: Android's toybox date has no
# `-d`, so parsing a formatted timestamp would fail on the one machine that
# needs it. An absent or empty field means "settled", so an older feed file can
# never block updates.
latest_published_epoch() {
	_json="$(ts_fetch "$TS_LATEST_URL")" || return 1
	[ -n "$_json" ] || return 1
	printf '%s' "$_json" | "${TS_BIN_DIR}/jq" -r '.publishedAtEpoch // empty' 2>/dev/null
}

# Take the current time
CURRENT_TIME=$(date +"%I:%M %P")

# ---------------------------------------------------------------------------
# Auto-update settings
# ---------------------------------------------------------------------------
# The tailscaled binary is static and never updates itself, so without this it
# drifts behind upstream and Tailscale's own health check nags about it.
# Set TS_UPDATE_AUTO=false in this file to opt out entirely.
# Where `tailscale update` fetches the v<version>-android release from. This
# must be the repo the build workflow actually publishes to.
export TS_UPDATE_REPO="${TS_UPDATE_REPO:-paras-moharle/tailscaled-reborn}"
export TS_UPDATE_AUTO="${TS_UPDATE_AUTO:-true}"
export TS_UPDATE_INTERVAL_HOURS="${TS_UPDATE_INTERVAL_HOURS:-24}"

# A freshly published build is held back for this long before phones are offered
# it. The check interval is not the risk; the timing is. Without a window, a bad
# upstream release reaches every phone within minutes of our CI finishing, which
# is too fast to notice and withdraw. Rollback still protects each device, but
# it is better not to roll the whole fleet back in the first place.
export TS_UPDATE_SETTLE_HOURS="${TS_UPDATE_SETTLE_HOURS:-6}"

# Coloring
normal="\033[0m"
orange="\033[1;38;5;208m"
red="\033[1;31m"
green="\033[1;32m"
yellow="\033[1;33m"
blue="\033[1;34m"

# Logs function
log() {
  # Selects the text color according to the parameters
  case $1 in
  Info) color="${blue}" ;;
  Success) color="${green}" ;;
  Error) color="${red}" ;;
  Warning) color="${yellow}" ;;
  Debug) color="${orange}" ;;
  *) color="${normal}" ;;
  esac
  # Add messages to time and parameters
  message="${CURRENT_TIME} [$1]: $2"
  if [ -z "$color" ]; then
    echo "${CURRENT_TIME} [Debug]: $1 $2" >>${TS_RUN_LOG_FILE} 2>&1
    return
  fi
  if [ -t 1 ]; then
    # Prints messages to the console
    echo "${color}${message}${normal}"
    echo "${message}" >>${TS_RUN_LOG_FILE} 2>&1
  else
    # Print messages to a log file
    echo "${message}" >>${TS_RUN_LOG_FILE} 2>&1
  fi
}

[ -n "${DEBUG:-}" ] && set -u && set -x && PS4='+ ${0##*/}:${LINENO}: ' || true