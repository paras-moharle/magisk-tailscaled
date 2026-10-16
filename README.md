# Tailscaled Reborn

Tailscale on a rooted Android phone, with the daemon built from upstream and
updated automatically.

[![module](https://img.shields.io/badge/module-v1.0.16-4ea1ff)](module.prop)
[![tailscale](https://img.shields.io/badge/tailscale-1.104.1-3ddc84)](https://tailscale.com)
[![license](https://img.shields.io/badge/license-BSD--3--Clause-a78bfa)](LICENSE)

---

## Install

1. Download **`Tailscaled-Reborn-v1.0.16-full.zip`** from
   [releases](../../releases).
2. **Magisk / KernelSU → Modules → Install from storage.**
3. Reboot.
4. Open **TailBridge** → **System → Sign in**.

Needs **Magisk or KernelSU**, **arm64**, and **Android 8.0+**.

> Upgrading from an older version? Flash the new zip over the old one. Do
> **not** uninstall — that deletes your login.

Use the `-full` zip. The slim one downloads the daemon during install, which
fails on devices with no HTTPS-capable `wget`.

---

## Sending files

**From your phone** — open TailBridge, pick a device, pick files. Or share
straight from any app: sharing a file and choosing TailBridge opens a device
picker immediately.

**From your desktop** — GNOME Files: right-click → *Send via Taildrop*.

---

## Receiving files

Files land in `Downloads/Tailscale` and raise a notification with an *Open*
action. Nothing to tap on the phone.

**On Linux**, something has to pull them — the Tailscale CLI cannot receive by
itself, and the GUI clients that do this ship only for macOS and Windows:

```sh
install -Dm755 desktop/taildrop-receiver ~/.local/bin/taildrop-receiver
install -Dm644 desktop/taildrop-receiver.service \
                ~/.config/systemd/user/taildrop-receiver.service
systemctl --user enable --now taildrop-receiver.service
```

**On macOS and Windows** the official Tailscale app already does this.

---

## GNOME Files context menu

```sh
install -Dm644 desktop/nautilus-taildrop.py \
                ~/.local/share/nautilus-python/extensions/taildrop.py
nautilus -q
```

`nautilus -q` is required — Nautilus loads extensions once at startup, so an
edited file will not be running until it restarts.

---

## Build

```sh
./builder/build-binaries.sh v1.104.1   # daemon, from an upstream tag
./share-app/build.sh                   # the app
./builder/build-module.sh --full       # flashable zips
```

Go 1.27+. No Android NDK for `arm64`.

---

## Credits

- **[tailscale/tailscale](https://github.com/tailscale/tailscale)** — the daemon, BSD 3-Clause.
- **[anasfanani/Magisk-Tailscaled](https://github.com/anasfanani/Magisk-Tailscaled)** — the module this was forked from.
- **Magisk / libsu** (topjohnwu), **KernelSU** (tiann), **[jq](https://github.com/jqlang/jq)**.

Not affiliated with Tailscale Inc. BSD 3-Clause — [LICENSE](LICENSE).

Details: [CHANGELOG.md](CHANGELOG.md).