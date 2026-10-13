#!/system/bin/sh
set -e

# Custom settings

# Set Magisk Tailscale module variables
TS_MOD_DIR="/data/adb/modules/magisk-tailscaled" # Bad code, if magisk in the future change the path location, need to change this.
export TS_MOD_PROP="${TS_MOD_DIR}/module.prop"

# Set tailscale directory variables
TS_DIR="/data/adb/tailscale"
TS_BIN_DIR="${TS_DIR}/bin"

# Set path environment for busybox & other.
export PATH="${TS_DIR}/bin:${TS_DIR}/scripts:/data/adb/magisk:/data/adb/ksu/bin:$PATH:/system/bin:${TS_MOD_DIR}/system/bin"
export HOME="/data/adb/tailscale/" # Because tailscaled will write log to $HOME

# Set tailscaled & tailscale configuration
export TS_DAEMON_CMD="tailscaled -no-logs-no-support"

# Set tailscaled directory variables
export TS_RUN_DIR="${TS_DIR}/run"

# Set tailscaled log variables
export TS_LOG_FILE="${TS_RUN_DIR}/tailscaled.log"
export TS_RUN_LOG_FILE="${TS_RUN_DIR}/runs.log"

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

# Take the current time
CURRENT_TIME=$(date +"%I:%M %P")

# ---------------------------------------------------------------------------
# Auto-update settings
# ---------------------------------------------------------------------------
# The tailscaled binary is static and never updates itself, so without this it
# drifts behind upstream and Tailscale's own health check nags about it.
# Set TS_UPDATE_AUTO=false in this file to opt out entirely.
export TS_UPDATE_REPO="${TS_UPDATE_REPO:-paras-moharle/tailscale-android-cli}"
export TS_UPDATE_AUTO="${TS_UPDATE_AUTO:-true}"
export TS_UPDATE_INTERVAL_HOURS="${TS_UPDATE_INTERVAL_HOURS:-24}"

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