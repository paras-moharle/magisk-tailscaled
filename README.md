# Tailscaled Reborn

Tailscale on a rooted Android phone, controlled by a small native app, with the
daemon binary kept up to date automatically.

[![module](https://img.shields.io/badge/module-v1.0.10-4ea1ff)](module.prop)
[![tailscale](https://img.shields.io/badge/tailscale-1.104.1-3ddc84)](https://tailscale.com)
[![license](https://img.shields.io/badge/license-BSD--3--Clause-a78bfa)](LICENSE)

---

## What it does

- Runs **tailscaled** as a userspace daemon on Magisk / KernelSU.
- **TailBridge**, a native app, is the entire interface — send files, flip
  settings, control the daemon, update, roll back.
- Builds the Android daemon **from upstream Tailscale** and publishes it, so the
  phone tracks upstream instead of sitting on a frozen binary.
- **Taildrop** both directions: send from the phone, receive on your desktop.

---

## Requirements

### Android

| | |
|---|---|
| Root | Magisk or KernelSU |
| Arch | `arm64` (`arm` is opt-in at build time) |
| Android | 8.0+ (API 26) |
| Network | must reach your tailnet |

Root is required and always will be: there is no kernel TUN device, the daemon
runs in userspace mode.

### Desktop (to send and receive files)

| OS | Receiving | Sending |
|---|---|---|
| **Linux** | `desktop/taildrop-receiver` (below) | `desktop/nautilus-taildrop.py` (GNOME Files) |
| **macOS** | built in to the Tailscale app | built in |
| **Windows** | built in to the Tailscale app | built in |

Taildrop is push on the sender and pull on the receiver. Apple and Microsoft ship
a client that does the pulling for you. **Linux has no such client** — the CLI
cannot receive by itself, so a file you send to a Linux machine will not appear
until something runs `tailscale file get`. `taildrop-receiver` is that something.

---

## Install

### Android

1. Download **`Tailscaled-Reborn-<version>-full.zip`** from
   [releases](../../releases). Prefer the `-full` zip: it carries the daemon and
   installs offline. The slim zip is 68 KB and downloads the daemon during
   install, which needs an HTTPS-capable `wget` that many modern Android builds
   do not have.
2. **KernelSU / Magisk → Modules → Install from storage.**
3. Reboot.
4. Open **TailBridge** → **System → Sign in**, and grant TailBridge root when
   asked.

If you are moving from the original module, **remove it first**. Both write to
`/data/adb/tailscale` and will conflict. Your login survives the move.

### Linux — receive files unattended

Pulls the Taildrop inbox into `~/Downloads/Tailscale` every 5 seconds and
notifies you. Works on any distro with a running tailscaled.

```sh
install -Dm755 desktop/taildrop-receiver ~/.local/bin/taildrop-receiver
install -Dm644 desktop/taildrop-receiver.service \
                ~/.config/systemd/user/taildrop-receiver.service
systemctl --user enable --now taildrop-receiver.service
```

Override the folder or the interval with `TAILDROP_DEST` and
`TAILDROP_INTERVAL`.

This is a **user** service, so it runs while you are logged in. For a headless
box, enable lingering once:

```sh
sudo loginctl enable-linger "$USER"
```

### Linux — send from the file manager

Adds **Send via Taildrop** to the Nautilus (GNOME Files) context menu, with one
entry per online device.

```sh
install -Dm644 desktop/nautilus-taildrop.py \
                ~/.local/share/nautilus-python/extensions/taildrop.py
nautilus -q          # restart to pick it up
```

Folders are archived to a `.tar.gz` first, because Taildrop moves files. The
archive is deleted after a successful send and kept on failure so you can retry.

---

## Updates

The daemon binary is built from upstream Tailscale and published by CI. Phones
check once a day, and a newly published build is held for six hours before it is
offered, so a bad upstream release does not reach every phone the moment CI
finishes.

```sh
tailscaled.update status     # installed, latest, last check, rollback point
tailscaled.update check      # query only
tailscaled.update run        # update now
tailscaled.update rollback   # restore the previous binary
```

The same actions are in the app under **System**. Nothing is compiled on the
phone: a release is a prebuilt `arm64` tarball that gets swapped in, and if the
daemon does not come back healthy the previous binary is restored automatically.

Tune it in `/data/adb/tailscale/settings.sh`:

```sh
TS_UPDATE_AUTO=true            # false disables updating entirely
TS_UPDATE_INTERVAL_HOURS=24    # how often to look
TS_UPDATE_SETTLE_HOURS=6       # hold a brand-new build this long
```

---

## Userspace mode

tailscaled runs with `-tun=userspace-networking`; there is no kernel TUN device.
That is what allows another VPN app to run at the same time. In practice:

- **MagicDNS works**, via the Android DNS manager
- **Subnet routes** need an explicit route per advertised prefix
- **Exit nodes** work, but are not recommended around the clock (battery)
- **IPv6** support is limited

---

## Build

```sh
./builder/build-binaries.sh v1.104.1   # Android tailscaled from an upstream tag
./share-app/build.sh                   # the app (no Gradle)
./builder/build-module.sh --full       # flashable zips
```

Go 1.27+, `GOOS=android GOARCH=arm64 CGO_ENABLED=0`. **No Android NDK needed**
for `arm64`; 32-bit `android/arm` requires cgo and is opt-in via `--with-arm`.

`build-module.sh` refuses to emit a zip with CRLF endings, a shell syntax error,
a missing required file, or an APK with no `classes.dex`.

The Android port is kept maintainable with asserted transforms:
`builder/port-android.sh` makes literal replacements with hard assertions, so an
upstream refactor **fails the build loudly** instead of producing a binary that
installs and then misbehaves. Larger code lives in
`builder/patches/android-core.patch`.

---

## Credits

- **[tailscale/tailscale](https://github.com/tailscale/tailscale)** — the daemon.
  BSD 3-Clause, © Tailscale, Inc.
- **[anasfanani/Magisk-Tailscaled](https://github.com/anasfanani/Magisk-Tailscaled)**
  — the module this was forked from: installer, service manager, and the Android
  port in `builder/`.
- **[anasfanani/tailscale-android-cli](https://github.com/anasfanani/tailscale-android-cli)**
  — `GOOS=android` builds and the client-update path `tailscale update` uses.
- **[kkurt — kxxt.dev](https://www.kxxt.dev/blog/full-tailscale-on-android-and-remote-unlocking/)**
  — the Android routing behaviour the port is based on.
- **Magisk / libsu** (topjohnwu) and **KernelSU** (tiann) — module format and root.
- **[jq](https://github.com/jqlang/jq)** — JSON handling.

Tailscale is a trademark of Tailscale Inc. This project is not affiliated with or
endorsed by Tailscale Inc. You are responsible for what you flash onto a device.

---

## License

BSD 3-Clause — see [LICENSE](LICENSE).