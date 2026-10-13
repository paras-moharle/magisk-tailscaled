# Tailscaled for Magisk / KernelSU — with TailBridge

Run Tailscale on a rooted Android phone as a **userspace daemon**, controlled by
a small native app called **TailBridge**, and keep the binary **current
automatically**.

[![module](https://img.shields.io/badge/module-v1.0.4-4ea1ff)](module.prop)
[![tailscale](https://img.shields.io/badge/tailscale-1.104.1-3ddc84)](https://tailscale.com)
[![license](https://img.shields.io/badge/license-BSD--3--Clause-a78bfa)](LICENSE)

---

## What this is

This is a **new project** that started life as a fork of
[anasfanani/Magisk-Tailscaled](https://github.com/anasfanani/Magisk-Tailscaled)
and is now versioned independently at **1.0.0**. The original module is credited
in full below — the installer, the service manager and, most importantly, the
Android port of Tailscale all come from there.

Three things were rebuilt:

| | Was | Now |
|---|---|---|
| **tailscaled** | frozen at 1.98.8; `tailscale update` pointed at a dead repo | rebuilt from upstream Tailscale, currently **1.104.1**, auto-updating |
| **Interface** | no usable UI (the web dashboard never worked reliably) | **TailBridge**, a single native app that is the whole interface |
| **Install** | asked `api.github.com` — 60 req/hr *per IP*, and phones sit behind CGNAT | uses release assets through `/releases/latest/`, no API, no rate limit |

---

## TailBridge

One app, no web server, no HTML. It is bundled **inside the module zip** and
installed for you when you flash — so a fresh install gives you a working
interface immediately.

<p align="center">
  <em>Send · Settings · System</em>
</p>

### Send
- **Taildrop out** — tick devices, hit Send, pick files. Sharing from Gallery,
  Files or a browser lands on the same screen with the files staged, so there is
  no separate share flow and no dialog to dismiss first.
- **Your addresses, one tap to copy** — IPv4 and IPv6.
- **Per-device** copy address and send-to-this-one-device.
- Selection is remembered, so after the first setup it is: pick files, Send.

> Why this app exists at all: the Play Store Tailscale app registers a share
> target, but it owns the Android VPN slot. This module deliberately does **not**
> use `VpnService` so another VPN app can run alongside it — which means without a
> share-target app there would be no way to send a file from the phone at all.

### Settings
Only toggles that were **verified to actually change something** on this build:

| Toggle | Effect |
|---|---|
| Accept DNS | use tailnet DNS names |
| Accept routes | follow subnet routes advertised by peers |
| Shields up | refuse incoming connections |
| Tailscale SSH | let tailnet members SSH in |
| Web client | serve the admin UI on the device |
| Advertise as exit node | share this device's internet |
| SNAT subnet routes | masquerade subnet traffic here |
| Device name | rename this device |

Each one writes through to the daemon and the switch is put back if the write
fails — no toggle that silently does nothing.

### System
- **Run tailscaled** — one switch that starts or stops the daemon. Stopped is a
  normal state, not an error: the app says so, greys out what needs a running
  daemon, and still lets you start it.
- **Restart daemon**, account sign-in/sign-out, version + module version,
  **check for updates**, **roll back**, daemon log, GitHub link, credits.

### About the look
Deliberately plain: grouped rows on a dark surface, one accent colour used
exactly once (the Send button), and flat icon-over-word navigation. No glass, no
gradients, nothing that fights the text.

---

## Updating, and getting back

`tailscaled.update` checks at boot, installs a newer binary, and **puts the old
one back if anything goes wrong**.

```sh
tailscaled.update status     # current + latest, last check, rollback point
tailscaled.update check      # query only
tailscaled.update run        # update now if newer exists
tailscaled.update rollback   # restore the previous binary
```

The same actions are in the app under **System**.

Tune it in `/data/adb/tailscale/settings.sh`:

```sh
TS_UPDATE_AUTO=true              # false disables it entirely
TS_UPDATE_INTERVAL_HOURS=24      # how often to look
TS_UPDATE_SETTLE_HOURS=6         # hold a brand-new build this long
```

**Nothing is compiled on the phone.** A release here is a prebuilt `arm64`
binary; the phone downloads a tarball, swaps it, restarts the daemon and
health-checks. If the daemon does not come back healthy, the previous binary is
restored automatically.

---

## Install

1. Download `Tailscaled-Reborn-v1.0.6-full.zip` from
   [releases](../../releases) — **prefer this one**. The slim zip is 68 KB and
   downloads the daemon at install time, which needs a working HTTPS-capable
   `wget`; many modern Android builds have none, and that install will fail. The
   `-full` zip carries the binary and always installs.
2. **KernelSU / Magisk** → Modules → *Install from storage*.
3. Reboot.
4. Open **TailBridge** and grant it root permission (KernelSU Manager →
   Superuser → TailBridge). The app checks for this first and tells you plainly
   if it is missing.

> **Coming from an older version or the original module?** This project now installs under
> its own module id (`paras-tailscaled`) so the manager stops treating it as the
> original. **Remove the old module before flashing**, or the two will fight over
> `/data/adb/tailscale`. Your login is preserved.

Signing in: **System → Sign in** in the app, which opens your browser, or from a
terminal:

```sh
su -c 'tailscale login'
su -c 'tailscaled.service status'
```

Upgrading from the upstream module **keeps your login** — `tailscaled.state` is
preserved across installs.

---

## Still userspace mode

This runs `tailscaled -tun=userspace-networking`; there is no kernel TUN
device. That is what lets another VPN app run at the same time. Consequences,
stated plainly:

- **MagicDNS works**, via the Android DNS manager (iDNS hijack)
- **Subnet routes** need an explicit route per advertised prefix
- **Exit nodes** work but are not recommended 24/7 (battery)
- IPv6 support is limited
- The phone needs root, always

---

## Building it yourself

```bash
# 1. Build Android tailscaled from an upstream Tailscale release
./builder/build-binaries.sh              # latest
./builder/build-binaries.sh v1.104.1     # a specific tag

# 2. Build the app (no Gradle, no Android Studio)
./share-app/build.sh

# 3. Package the flashable zips
./builder/build-module.sh            # slim
./builder/build-module.sh --full     # bundles the daemon, for offline installs
```

Builds use `GOOS=android GOARCH=arm64 CGO_ENABLED=0` with Go 1.27+ — **no
Android NDK required**, which is why CI stays simple. (32-bit `android/arm`
*requires* cgo and therefore the NDK; it is opt-in via `--with-arm`.)

`build-module.sh` refuses to produce a zip that has CRLF line endings, a shell
syntax error, a missing required file, or an app APK with no `classes.dex`. A
broken package should fail here, not on someone's phone.

### How the Android port stays maintainable

Upstream refactors constantly, so the port is split in two:

- `builder/port-android.sh` — **asserted transforms**. Each is an exact literal
  replacement with a hard assertion. If upstream changes a build tag, the build
  **fails loudly** instead of quietly emitting a binary that installs fine and
  misbehaves on the phone.
- `builder/patches/android-core.patch` — new files and substantial code. Rarely
  conflicts, because it is mostly additions.

Moving 1.98.8 → 1.104.1 was real work: `ssh/tailssh` had been split into files
that exclude Android, `linuxfw` marks moved to `tsconst`, and upstream dropped
`wlynxg/anet`.

---

## Credits

This project would not exist without the following. The hard parts are theirs.

### Tailscale
- [tailscale/tailscale](https://github.com/tailscale/tailscale) — the daemon
  itself. BSD 3-Clause. © Tailscale, Inc. and the Tailscale AUTHORS.
- [Tailscale userspace networking](https://tailscale.com/kb/1112/userspace-networking/)
  — the mode this module relies on.

### Magisk-Tailscaled — the original module, and the Android port
- [anasfanani/Magisk-Tailscaled](https://github.com/anasfanani/Magisk-Tailscaled)
  — the module this project was forked from: the installer, the service manager,
  and the Android port in `builder/`, which this project builds on directly.
- [anasfanani/tailscale-android-cli](https://github.com/anasfanani/tailscale-android-cli)
  — `GOOS=android` builds of Tailscale, and the Android client-update path in
  `clientupdate/clientupdate_android.go` that `tailscale update` uses here.
- The original author also wrote the mock `getent` that makes Tailscale SSH work
  on Android, where `os/user` needs glibc.

### Foundations referenced by the port
- [John Wu & Authors — Magisk](https://github.com/topjohnwu/Magisk) — the module format.
- [topjohnwu — libsu](https://github.com/topjohnwu/libsu) — root shell access.
- [tiann — KernelSU](https://github.com/tiann/KernelSU) — `ksud module` management.
- [kkurt — kxxt.dev: *Full Tailscale on Android*](https://www.kxxt.dev/blog/full-tailscale-on-android-and-remote-unlocking/)
  — the write-up the Android routing and fwmark behaviour is based on.
- [BieHDC/anet](https://github.com/BieHDC/anet) — the `wlynxg/anet` fork carrying
  the Android interface-enumeration fix used by `net/netmon`.

### Tools
- [jq](https://github.com/jqlang/jq) — JSON processing (prebuilt for Android by
  [theshoqanebi/jq-build-for-android](https://github.com/theshoqanebi/jq-build-for-android))
- UPX — **not used**: it has produced broken Android binaries before.

---

## License

BSD 3-Clause — see [LICENSE](LICENSE).

Tailscale is a trademark of Tailscale Inc. This project is **not** affiliated
with or endorsed by Tailscale Inc. It is provided as-is; you are responsible for
what you flash onto your device.