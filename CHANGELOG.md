## v2.1.0.0

### Tailscale binaries now track upstream

The bundled `tailscaled` is no longer frozen at whatever upstream shipped when
this module was released. Binaries are built from upstream Tailscale releases
by `builder/build-binaries.sh` and published as `v<version>-android` releases.

- Ships Tailscale **1.104.1** (was 1.90.6 in v2.0.0.1)
- `tailscale update` now works on-device — the Android client-update path is
  part of the port and points at our release feed
- `tailscaled.update` (new) schedules that check at boot and supervises it

### New: auto-update with rollback

```
tailscaled.update status     # current + latest, last check, backup
tailscaled.update check      # query (rate limited to once per interval)
tailscaled.update run        # update now if newer
tailscaled.update rollback   # restore the previous binary
```

- Runs from the boot service, once per `TS_UPDATE_INTERVAL_HOURS` (default 24)
- The download is done by the Go-based updater inside `tailscaled`, not by
  shell — a rooted phone has no other TLS-capable HTTP client (busybox `wget`
  is built without CA validation)
- Snapshots the outgoing binary first; if the daemon does not report a healthy
  backend after the update, it is restored automatically
- Opt out with `TS_UPDATE_AUTO=false` in `/data/adb/tailscale/settings.sh`

### Installer

- `customize.sh` verifies the downloaded binary actually executes on the device
  and reports its version, instead of failing later at boot
- Binaries come from `paras-moharle/tailscale-android-cli`

### Upstream-port maintenance

The Android port is now split into scripted transforms (asserted, one per edit)
plus `builder/patches/android-core.patch`, so an upstream refactor fails the
build loudly rather than silently producing a broken binary.

### Fixes found while testing on device

- The port no longer lets the binary create its own `tailscale` symlink.
  Upstream's `createTailscaleSymlink()` derives the link from
  `os.Executable()`, so once the link pointed at the `tailscaled.prev` left by
  a rollback, every later CLI call rewrote the same broken link — the CLI kept
  reporting (and updating from) the old version while the daemon ran the new
  one. The module now owns the link via `ensure_cli_symlink()`.
- `TS_BIN_DIR` was never defined in `settings.sh`, so anything using it
  resolved against `/`. It is defined now, and pinned defensively in the
  updater.

### Verified on device (OnePlus Nord 3, Android 15, KernelSU)

1.90.6 -> 1.104.1, state preserved, health warning gone, rollback and
auto-update both exercised end to end.

