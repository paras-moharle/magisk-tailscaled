# Tailscaled Reborn

Tailscale for rooted Android — the daemon built from upstream, updated
automatically, and controlled by a native app instead of a web dashboard.

[![module](https://img.shields.io/badge/module-v1.0.12-4ea1ff)](module.prop)
[![tailscale](https://img.shields.io/badge/tailscale-1.104.1-3ddc84)](https://tailscale.com)
[![license](https://img.shields.io/badge/license-BSD--3--Clause-a78bfa)](LICENSE)

<!--
Everything a visitor needs, in the order they need it: what it is, whether it
runs on their phone, how to install, how it behaves. History and rationale live
in CHANGELOG.md, where they belong.
-->

## Quick start

1. Download **`Tailscaled-Reborn-v1.0.12-full.zip`** from
   [releases](../../releases).
2. **Magisk / KernelSU → Modules → Install from storage.**
3. Reboot.
4. Open **TailBridge** → **System → Sign in**.

That's it. Grant TailBridge root when asked.

> Upgrading from an earlier version of this fork? Just flash the new zip over
> the old one — do **not** uninstall first. Uninstalling deletes your login.
> Only remove the old module if it was the *original* upstream module
> (`anasfanani/Magisk-Tailscaled`), which uses a different module id and fights
> this one for the same directory.

---

## What you get

| | |
|---|---|
| **Daemon** | `tailscaled` built from upstream Tailscale, running in userspace mode so another VPN app can run alongside it |
| **App** | TailBridge — one native app: send files, settings, daemon control, update, roll back |
| **Updates** | CI builds the Android daemon daily from upstream, publishes it, and phones pull it — with automatic rollback if a new binary misbehaves |
| **Files** | Taildrop both ways, with a desktop receiver for Linux |

### TailBridge

**Send** — pick devices (green = online, red = offline), share from any app's
share sheet straight into a device picker, or send to one device in a tap.
Receiving is automatic: files land in `Downloads/Tailscale` and raise a
notification with an *Open* action.

**Settings** — only toggles verified to actually do something on this build:
accept DNS, accept routes, shields up, SSH, web client, advertise exit node,
SNAT subnet routes, device name. A toggle that fails to write reverts visibly
rather than pretending.

**System** — start/stop the daemon, restart, sign in/out, version, check for
updates, roll back, read the log.

---

## Requirements

### Android

| | |
|---|---|
| Root | **Magisk** or **KernelSU** |
| Architecture | `arm64` (`arm` is opt-in at build time) |
| Android | 8.0+ (API 26) |

Root is required and always will be: there is no kernel TUN device, so the
daemon runs in userspace mode.

### Desktop, for sending and receiving files

| OS | Receiving | Sending |
|---|---|---|
| **Linux** | `desktop/taildrop-receiver` — installed below | `desktop/nautilus-taildrop.py` — GNOME Files |
| **macOS** | built in to the Tailscale app | built in |
| **Windows** | built in to the Tailscale app | built in |

Taildrop is **push on the sender, pull on the receiver**. Apple and Microsoft
ship a client that does the pulling. Linux does not — the CLI cannot receive by
itself, so a file sent to a Linux machine stays invisible until something runs
`tailscale file get`. That something is the receiver below.

---

## Linux desktop setup

### Receive files unattended

Pulls the Taildrop inbox into `~/Downloads/Tailscale` every few seconds and
notifies you.

```sh
install -Dm755 desktop/taildrop-receiver ~/.local/bin/taildrop-receiver
install -Dm644 desktop/taildrop-receiver.service \
                ~/.config/systemd/user/taildrop-receiver.service
systemctl --user enable --now taildrop-receiver.service
```

Override the folder or interval with `TAILDROP_DEST` and `TAILDROP_INTERVAL`.

This is a *user* service, so it runs while you are logged in. For a headless
box:

```sh
sudo loginctl enable-linger "$USER"
```

### Send from the file manager

Adds **Send via Taildrop** to the Nautilus context menu, with one entry per
online device and a progress notification while it sends.

```sh
install -Dm644 desktop/nautilus-taildrop.py \
                ~/.local/share/nautilus-python/extensions/taildrop.py
nautilus -q
```

> **`nautilus -q` is required.** Nautilus loads Python extensions once at
> startup — editing the file does nothing until it is restarted, so a change
> that looks installed will not be running.

Folders are archived to a `.tar.gz` first, because Taildrop moves files. The
archive is deleted after a successful send and kept on failure so you can retry.

---

## How updates work

```
watch-upstream.yml     daily — notices a new upstream Tailscale release
  └─ build-android.yml ports Tailscale to Android, builds tailscaled,
                       publishes it, commits the feed pointer
       └─ the phone reads tailscale-latest.json, compares versions,
          and swaps the binary in — rolling back if it does not come back
```

The port is kept maintainable with **asserted transforms**: every change
`builder/port-android.sh` makes to upstream source is an exact replacement with
a hard assertion, so an upstream refactor **fails the build loudly** instead of
producing a binary that installs and then misbehaves.

Tune it in `/data/adb/tailscale/settings.sh`:

```sh
TS_UPDATE_AUTO=true            # false disables updating entirely
TS_UPDATE_INTERVAL_HOURS=24    # how often to look
TS_UPDATE_SETTLE_HOURS=6       # hold a brand-new build this long
```

Nothing is compiled on the phone: a release is a prebuilt `arm64` tarball that
gets swapped in, and the previous binary is restored automatically if the daemon
does not come back healthy.

---

## Known limits

- **Userspace mode.** No kernel TUN device. MagicDNS works via the Android DNS
  manager; subnet routes need an explicit route per prefix; exit nodes work but
  are not recommended around the clock (battery); IPv6 support is limited.
- **`arm` is not built by default.** It needs cgo and therefore the NDK.
- **Progress is per file, not per byte.** `tailscale file cp` reports nothing
  until it finishes; real byte-level progress would mean replacing Tailscale's
  transfer path.

---

## Build it yourself

```sh
./builder/build-binaries.sh v1.104.1   # Android tailscaled from an upstream tag
./share-app/build.sh                   # the app (no Gradle)
./builder/build-module.sh --full       # flashable zips
```

Go 1.27+, `GOOS=android GOARCH=arm64 CGO_ENABLED=0`. **No Android NDK needed**
for `arm64`.

`build-module.sh` refuses to emit a zip with CRLF line endings, a shell syntax
error, a missing required file, or an APK with no `classes.dex`. A package that
cannot work should fail here, not on someone's phone.

---

## Credits

- **[tailscale/tailscale](https://github.com/tailscale/tailscale)** — the daemon.
  BSD 3-Clause, © Tailscale, Inc.
- **[anasfanani/Magisk-Tailscaled](https://github.com/anasfanani/Magisk-Tailscaled)**
  — the module this was forked from: installer, service manager, and the Android
  port in `builder/`.
- **[kkurt — kxxt.dev](https://www.kxxt.dev/blog/full-tailscale-on-android-and-remote-unlocking/)**
  — the Android routing behaviour the port is based on.
- **Magisk / libsu** (topjohnwu) and **KernelSU** (tiann) — module format and root.
- **[jq](https://github.com/jqlang/jq)** — JSON handling.

Tailscale is a trademark of Tailscale Inc. This project is not affiliated with or
endorsed by Tailscale Inc. You are responsible for what you flash onto a device.

## License

BSD 3-Clause — see [LICENSE](LICENSE).