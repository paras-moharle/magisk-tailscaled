#!/system/bin/sh
# Remove everything this module installed.
MODDIR="${0%/*}"

rm -rf /data/adb/tailscale
SERVICE_DIR="/data/adb/service.d"
if [ -f "$SERVICE_DIR/tailscaled_service.sh" ]; then
    rm -f "$SERVICE_DIR/tailscaled_service.sh"
fi

# TailBridge is installed as an ordinary user app by customize.sh, so removing
# the module used to leave it behind: a launcher icon with no daemon behind it,
# which can only ever say "grant root permission".
#
# Unconditionally, because deciding "is this the module that installed it?"
# means trusting $0, and uninstall.sh is not reliably invoked with its own path.
# If you are mid-migration between module ids, flash the new module afterwards
# and customize.sh will install the app again.
if pm list packages 2>/dev/null | grep -q "com.paras.tailbridge"; then
    pm uninstall com.paras.tailbridge
fi

exit 0