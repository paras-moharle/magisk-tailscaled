## v1.0.1

### The module is no longer confused with the original

`module.prop` carried `id=magisk-tailscaled`, which is the same id as
[anasfanani/Magisk-Tailscaled](https://github.com/anasfanani/Magisk-Tailscaled).
Magisk and KernelSU key modules by id, so the two are the same module as far as
the manager is concerned — which is why managers kept offering "update" for a
module that was already current.

The id is now `paras-tailscaled`, and `TS_MOD_DIR` follows it.

**Migration:** if you had the old id installed, remove it before flashing this
one. Both modules drive `/data/adb/tailscale`, and running them together will
fight over the daemon. Your login survives — `tailscaled.state` lives in
`/data/adb/tailscale`, which `uninstall.sh` does not touch on a module update,
and is preserved across installs either way.

### The update feed advertised a stale build

`update.json` still said `v2.1.0.0` / `02010000` from the fork, so every manager
correctly concluded that an update was available — and would have installed the
old build. It is now generated from `module.prop` on each release, version and
versionCode included, and points at the `-full` zip.

### TailBridge

- **Live updates.** Renaming a device in the admin console now shows up within
  about five seconds instead of waiting for a manual refresh. Only the header
  and the name rows are repainted, so scrolling and in-progress taps survive.
- **Root is checked first.** On launch the app asks for root before doing
  anything else, so a missing grant reads as "grant root permission" rather than
  as a broken daemon.
- **Account in the app.** The tailnet you are signed in to is shown under
  System, with sign-in and sign-out. Sign-in opens the browser directly — there
  is no interstitial dialog, because Tailscale has no headless auth flow.
- **A real log viewer.** The daemon log was a plain message box. It is now
  monospace, colour-coded by severity, with the timestamp dimmed, a
  problems-only filter, copy and refresh.

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

