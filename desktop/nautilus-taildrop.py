#!/usr/bin/env python3
# SPDX-License-Identifier: BSD-3-Clause
# Copyright (c) 2026 paras-moharle
#
# Nautilus context-menu extension: send files to a Tailnet peer with Taildrop.
#
#   Nautilus "Send via Taildrop"  ->  submenu of online devices
#
# ── Why this file is shaped the way it is ───────────────────────────────────
#
# Nautilus calls get_file_items() on its main thread, for every selection
# change. Anything slow in here stalls the file manager, and anything that
# raises takes it down. Two mistakes this file used to make, both of which you
# can feel:
#
#   1. It ran `tailscale status --json` twice per right-click. A process spawn
#      plus a control-plane read is hundreds of milliseconds, on the UI thread.
#   2. It staged the selection while building the menu -- including tarring a
#      whole folder. Tarring a large directory on the main thread freezes
#      Nautilus for as long as the compression takes. This is almost certainly
#      what you saw.
#
# So the menu does no work: it reads a snapshot a background thread keeps warm,
# and staging happens in the sender thread after you have chosen a device.
# Building the menu is now a dict read.
#
# Requires: nautilus, python3-gi, the tailscale CLI and a running daemon.

import json
import os
import subprocess
import tarfile
import tempfile
import threading
import time

import gi

gi.require_version("Gio", "2.0")
gi.require_version("GLib", "2.0")

# Nautilus 50 lives in the 4.1 namespace; older releases exported 4.0.
for _ns in ("4.1", "4.0"):
    try:
        gi.require_version("Nautilus", _ns)
        break
    except ValueError:
        continue
else:  # pragma: no cover - only on a Nautilus we do not know
    raise SystemExit("taildrop: this Nautilus exposes no known GI namespace")

from gi.repository import Gio, GLib, GObject, Nautilus  # noqa: E402

MENU_PREFIX = "TailBridgeExtensions"
LABEL = "Send via Taildrop"
SEND_TIMEOUT = 600

# How often the snapshot is refreshed in the background. Short enough that a
# device that just came online appears promptly, long enough that this is one
# spawn every few seconds rather than one per right-click.
_REFRESH_SECONDS = 3.0


def _log(msg):
    print(f"taildrop: {msg}", flush=True)


def _run(args, timeout=30):
    try:
        p = subprocess.run(
            args,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            timeout=timeout,
            check=False,
        )
    except FileNotFoundError:
        return 127, "", f"{args[0]} is not installed"
    except subprocess.TimeoutExpired:
        return 124, "", f"{args[0]} timed out"
    except Exception as exc:  # never let an unexpected error escape
        return 125, "", str(exc)
    return (
        p.returncode,
        p.stdout.decode("utf-8", "replace"),
        p.stderr.decode("utf-8", "replace"),
    )


def _progress(done, total, name):
    """One notification, replaced in place, showing how far along we are.

    A notification per file would be noise, and none at all is what made a
    fast send look like a hang. Nautilus has nowhere to draw a progress bar, so
    the shade is the surface: same notification ID every time, so it updates.
    """
    if total <= 1:
        return
    if done < total:
        title = f"Sending {done + 1}/{total}"
    else:
        title = f"Sent {done}/{total}"
    try:
        subprocess.run(
            ["notify-send", "-a", "Taildrop", "-u", "low",
             "-i", "mail-send", "-r", "777", title, name],
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=5,
            check=False)
    except Exception:
        pass


def _notify(title, body):
    """Report the outcome. Never raises.

    Only libnotify, via whichever binding exists. Neither touches the toolkit
    Nautilus is running, so neither can take the file manager down.
    """
    try:
        import notify2

        notify2.init("taildrop")
        notify2.Notification(title, body).show()
        return
    except Exception:
        pass
    try:
        if _run(["notify-send", "-a", "Taildrop", title, body], timeout=5)[0] == 0:
            return
    except Exception:
        pass
    _log(f"{title}: {body}")


# ------------------------------------------------------------------ snapshot --
# Only ever written by the refresher thread and read by the UI thread. A plain
# tuple assignment is atomic under the GIL, so the menu never sees a half-built
# snapshot and never has to take a lock.
_snapshot = ("", [], 0.0)  # (backend_state, [(name, ip)], fetched_at)


def _invalidate():
    """Force the next read to re-query, e.g. after an address went stale."""
    global _snapshot
    _snapshot = ("", [], 0.0)


def _refresh_once():
    global _snapshot
    rc, out, _ = _run(["tailscale", "status", "--json"], timeout=5)
    if rc != 0 or not out.strip():
        _snapshot = ("", [], time.monotonic())
        return
    try:
        data = json.loads(out)
    except json.JSONDecodeError:
        _snapshot = ("", [], time.monotonic())
        return

    found = []
    for peer in (data.get("Peer") or {}).values():
        if not peer.get("Online"):
            continue
        ips = peer.get("TailscaleIPs") or []
        ip = next((a for a in ips if ":" not in a), None) or next(iter(ips), None)
        if not ip:
            continue
        name = (peer.get("HostName") or peer.get("DNSName") or ip).split(".")[0]
        found.append((name, ip))
    found.sort(key=lambda pair: pair[0].lower())
    _snapshot = (data.get("BackendState", "") or "", found, time.monotonic())


def _refresher():
    while True:
        try:
            _refresh_once()
        except Exception as exc:  # a refresher crash would silently empty the menu
            _log(f"refresh failed: {exc}")
        time.sleep(_REFRESH_SECONDS)


def _ensure_refresher():
    global _started
    if _started:
        return
    _started = True
    threading.Thread(target=_refresher, daemon=True).start()
    # One synchronous read so the very first right-click is not empty.
    try:
        _refresh_once()
    except Exception:
        pass


_started = False


# ------------------------------------------------------------------- sending --
def _archive(folder):
    base = os.path.basename(folder.rstrip("/")) or "folder"
    fd, path = tempfile.mkstemp(prefix="taildrop-", suffix=f"-{base}.tar.gz")
    os.close(fd)
    with tarfile.open(path, "w:gz") as tf:
        tf.add(folder, arcname=base)
    return path


def _stage(fileinfo):
    """Local path for a selection: (path, archive_to_delete_or_None).

    Runs on the sender thread, never on the UI thread.
    """
    uri = fileinfo.get_uri()
    if not uri.startswith("file://"):
        raise ValueError("only local files can be sent")
    path = Gio.File.new_for_uri(uri).get_path()
    if path is None:
        raise ValueError(f"cannot resolve {uri}")
    if os.path.isdir(path):
        return _archive(path), path
    return path, None


def _peer_now(label):
    """Resolve a peer's current address by name, ignoring the cache.

    A cached address is fine until the peer's tailnet IP changes -- which it
    does when a device re-registers. `tailscale file cp` does not fail loudly
    for an address that has gone away; it queues the file and reports success,
    so the send is simply lost. Re-resolving at send time is what makes this
    survive an address change.
    """
    _invalidate()
    _refresh_once()
    for name, ip in _snapshot[1]:
        if name == label:
            return ip
    return None


def _send(path, label, archive, done=0, total=1):
    name = os.path.basename(path)
    _progress(done, total, name)

    # A stale or wedged connection must not hold the whole batch hostage.
    per_file = max(30, SEND_TIMEOUT // 2)
    # Resolve the address at send time, not from the menu-time cache.
    dest = _peer_now(label)
    for attempt in (1, 2):
        if dest is None:
            _notify("Taildrop: send failed",
                    f"{label} is not an online device any more ({name})")
            return
        rc, out, err = _run(
            ["tailscale", "file", "cp", path, f"{dest}:"], timeout=per_file
        )
        if rc == 0:
            _progress(done + 1, total, name)
            if archive and os.path.exists(archive):
                os.unlink(archive)
            if done + 1 >= total:
                _notify("Taildrop", f"Sent {total} file(s) → {label}")
            return
        # The address may have moved under us. Re-resolve and try once more.
        if attempt == 1:
            again = _peer_now(label)
            if again and again != dest:
                dest = again
                continue
            break

    detail = (err or out or "").strip().splitlines()
    body = (detail[-1] if detail else f"exit {rc}") + f" ({name} → {label})"
    if archive:
        # Keep it so the send can be retried rather than rebuilt.
        body += f"\nArchive kept: {archive}"
    _notify("Taildrop: send failed", body)


def _send_async(files, label):
    """Stage and send on a worker thread, one command per file."""

    def work():
        staged = []
        for info in files:
            try:
                staged.append((info, _stage(info)))
            except Exception as exc:
                _notify("Taildrop: send failed", f"{info.get_name()}: {exc}")
        if not staged:
            return
        total = len(staged)
        for n, (_info, (path, archive)) in enumerate(staged):
            _send(path, label, archive, done=n, total=total)

    threading.Thread(target=work, daemon=True).start()


class TaildropProvider(GObject.GObject, Nautilus.MenuProvider):
    """Adds "Send via Taildrop" with one submenu entry per online device."""

    def _items_for(self, files):
        """Build the menu. Reads a cache; never spawns, never touches disk."""
        try:
            _ensure_refresher()
            if not files:
                return []

            backend, online, _ = _snapshot
            if backend in ("", "Stopped", "NeedsLogin") or not online:
                return []

            submenu = Nautilus.Menu()
            for name, ip in online:
                item = Nautilus.MenuItem(
                    name=f"{MENU_PREFIX}::Send::{ip}", label=name,
                    tip=f"Send to {name} ({ip})")
                item.connect("activate", self._on_activate, list(files), name, ip)
                submenu.append_item(item)

            n = len(files)
            label = LABEL if n == 1 else f"{LABEL} ({n} files)"
            top = Nautilus.MenuItem(name=f"{MENU_PREFIX}::Taildrop",
                                    label=label, tip="Send over your Tailnet")
            top.set_submenu(submenu)
            return [top]
        except Exception as exc:
            # Nautilus does not handle an exception from here gracefully; it
            # takes the window with it. A missing menu entry is recoverable, a
            # crashed file manager is not.
            _log(f"menu build failed: {exc}")
            return []

    def _on_activate(self, _item, files, name, ip):
        # `ip` is deliberately not used for the send: see _peer_now. It is only
        # what the menu entry was labelled with.
        _send_async(files, name)
        return True

    # Nautilus calls these two. The folder background is deliberately a no-op:
    # "send this folder" already appears when the folder itself is selected.
    def get_file_items(self, files):
        return self._items_for(files)

    def get_background_items(self, _folder):
        return []