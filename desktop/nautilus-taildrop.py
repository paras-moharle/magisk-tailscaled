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
# Nautilus calls get_file_items() on its main thread for every selection change.
# Anything slow here stalls the file manager; anything that raises takes Nautilus
# down with it. Two mistakes this file used to make:
#
#   1. It ran `tailscale status --json` twice per right-click on the UI thread.
#   2. It tarred large directories on the UI thread, freezing Nautilus.
#
# So the menu does as little as possible: it reads a snapshot that is refreshed
# in the background, and staging happens in the sender thread after you have
# chosen a device.
#
# The snapshot is also refreshed synchronously whenever it has gone stale, and
# the very first open always waits for a real fetch. That is the difference
# between a menu that always lists your devices and one that shows whatever
# happened to be in the cache when you right-clicked.
#
# Requires: nautilus, python3-gi, the tailscale CLI and a running daemon.
#
# Debug: run from terminal with NAUTILUS_DEBUG=1 to see verbose logs.
#         nautilus -q && nautilus &

import json
import os
import subprocess
import sys
import tarfile
import tempfile
import threading
import time

import gi
gi.require_version("Gio", "2.0")
gi.require_version("GLib", "2.0")

# Nautilus 50+ lives in the 4.1 namespace; older releases exported 4.0.
for _ns in ("4.1", "4.0"):
    try:
        gi.require_version("Nautilus", _ns)
        break
    except ValueError:
        continue
else:
    sys.exit("taildrop: no known Nautilus GI namespace available")

from gi.repository import Gio, GLib, GObject, Nautilus  # noqa: E402

MENU_PREFIX = "TailBridgeExtensions"
LABEL = "Send via Taildrop"
SEND_TIMEOUT = 600

# How long a peer list stays usable before a right-click re-queries.
#
# A background refresher on a 3-second loop kept re-running `tailscale status`
# for the whole life of the file manager, for a list that is only ever read
# when someone opens a menu. It also made the menu depend on a thread it did
# not control: if that thread was wedged or slow, the menu showed a stale list
# with nothing to indicate why. Re-querying on demand, at most every few
# seconds, is both cheaper and more truthful.
STALE_AFTER = 5.0

# ------------------------------------------------------------------------- logging

def _debug(msg):
    """Write to stderr if NAUTILUS_DEBUG=1, otherwise be silent.
    Nautilus captures stderr; logs end up in journalctl.
    """
    if os.environ.get("NAUTILUS_DEBUG") == "1":
        ts = time.strftime("%H:%M:%S")
        print(f"[taildrop {ts}] {msg}", file=sys.stderr, flush=True)


def _run(args, timeout=30):
    """Run a command, return (rc, stdout, stderr). Never raises."""
    try:
        p = subprocess.run(
            args,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            timeout=timeout,
            check=False,
        )
    except FileNotFoundError:
        return 127, "", f"{args[0]} not found on PATH"
    except subprocess.TimeoutExpired:
        return 124, "", f"{args[0]} timed out after {timeout}s"
    except Exception as exc:
        return 125, "", str(exc)
    return p.returncode, p.stdout.decode("utf-8", "replace"), p.stderr.decode("utf-8", "replace")


# ------------------------------------------------------------------ peer lookup

def _fetch_peers():
    """Query `tailscale status --json` and return list of (name, ip, online).

    Returns an empty list on any failure. Never raises.
    """
    rc, out, err = _run(["tailscale", "status", "--json"], timeout=10)
    if rc != 0:
        _debug(f"tailscale status failed rc={rc}: {err.strip()}")
        return []
    if not out.strip():
        _debug("tailscale status returned empty output")
        return []

    try:
        data = json.loads(out)
    except json.JSONDecodeError as exc:
        _debug(f"could not parse status JSON: {exc}")
        return []

    backend = data.get("BackendState", "")
    if backend not in ("Running",):
        _debug(f"backend state is '{backend}', showing no peers")

    peers = []
    for key, peer in (data.get("Peer") or {}).items():
        online = peer.get("Online", False)
        ips = peer.get("TailscaleIPs") or []
        # Prefer IPv4, fall back to first available
        ip = next((a for a in ips if ":" not in a), None) or (ips[0] if ips else None)
        if not ip:
            continue
        # HostName is the local override; DNSName is the control-plane name.
        # DNSName may lag behind a rename, so prefer HostName.
        name = (peer.get("HostName") or peer.get("DNSName") or ip).split(".")[0]
        if not name:
            name = ip
        peers.append({"name": name, "ip": ip, "online": online})
        _debug(f"  peer: {name} ip={ip} online={online}")

    # Sort: online peers first (alphabetically), then offline
    peers.sort(key=lambda p: (not p["online"], p["name"].lower()))
    return peers


# --------------------------------------------------------------- snapshot cache

# (fetched_at_monotonic, peers, error). A plain tuple assignment is atomic under
# the GIL, so a reader never sees half of a rebuild.
_cache = (0.0, [], "")
_cache_lock = threading.Lock()


def _get_peers(max_age=None, force=False):
    """Peers, re-querying if the snapshot is missing or old.

    Nautilus calls this on its main thread when a menu opens, so the fetch is
    kept to one `tailscale status` with a short timeout, and the result is
    reused while it is still fresh. Opening a menu twice in a row costs one
    query; adding a device and right-clicking immediately costs one query and
    shows the new device.

    force=True is for send time, where the answer must be current no matter
    what the menu happened to see.
    """
    global _cache
    if max_age is None:
        max_age = STALE_AFTER
    with _cache_lock:
        age = time.monotonic() - _cache[0]
        if not force and _cache[0] and age < max_age:
            return list(_cache[1])
        peers = _fetch_peers()
        # A failed fetch must not erase a list that worked a moment ago: a
        # single dropped CLI call would otherwise empty the menu.
        if peers:
            _cache = (time.monotonic(), peers, "")
        elif _cache[0]:
            _cache = (time.monotonic(), _cache[1], "query failed")
        else:
            _cache = (time.monotonic(), [], "query failed")
        _debug(f"fetched {len(_cache[1])} peers (age was {age:.1f}s)")
        return list(_cache[1])


# ------------------------------------------------------------------------- send

def _archive(folder):
    """Tar a folder into a self-deleting temp tarball."""
    base = os.path.basename(folder.rstrip("/")) or "folder"
    fd, path = tempfile.mkstemp(prefix="taildrop-", suffix=f"-{base}.tar.gz")
    os.close(fd)
    with tarfile.open(path, "w:gz") as tf:
        tf.add(folder, arcname=base)
    return path


def _stage(fileinfo):
    """Return (local_path, archive_to_delete_or_None). Runs on sender thread."""
    uri = fileinfo.get_uri()
    if not uri.startswith("file://"):
        raise ValueError("only local files can be sent")
    path = Gio.File.new_for_uri(uri).get_path()
    if path is None:
        raise ValueError(f"cannot resolve {uri}: {uri}")
    if os.path.isdir(path):
        return _archive(path), path
    return path, None


def _notify(title, body):
    """Post a desktop notification. Fails silently; result goes to _debug."""
    for method_name, args in [
        ("notify2", ["notify2", "-a", "Taildrop"]),
        ("send",    ["notify-send", "-a", "Taildrop"]),
    ]:
        cmd = args + [title, body]
        rc, _, _ = _run(cmd, timeout=5)
        if rc == 0:
            return
    _debug(f"notify failed — title={title!r} body={body!r}")


def _progress(done, total, name):
    """Progress notification: title updates as files are sent."""
    if total <= 1:
        return
    if done < total:
        title = f"Sending {done + 1}/{total}"
    else:
        title = f"Sent {done}/{total}"
    _run(["notify-send", "-a", "Taildrop", "-u", "low", "-i", "mail-send",
          "-r", "777", title, name], timeout=5)


def _resolve_peer(name):
    """Live lookup of peer <name>, returning its current IP.

    Always forced: the menu may have been opened minutes ago, and tailscale
    re-registers and changes a device's tailnet IP on every connect. Sending to
    a stale IP fails, or worse, is aimed at whatever now holds that address.
    """
    for p in _get_peers(force=True):
        if p["name"] == name:
            return p["ip"]
    return None


def _send_file(path, name, archive, done, total):
    """Send one file to peer <name> (not IP — resolved at call time)."""
    _progress(done, total, os.path.basename(path))

    # Resolve at send time, not at menu-open time.
    dest_ip = _resolve_peer(name)
    per_file = max(30, SEND_TIMEOUT // 2)

    for attempt in (1, 2):
        if dest_ip is None:
            _notify("Taildrop: send failed",
                    f"{name} is no longer reachable")
            return False

        rc, out, err = _run(
            ["tailscale", "file", "cp", path, f"{dest_ip}:"],
            timeout=per_file,
        )
        if rc == 0 and not err.strip():
            _progress(done + 1, total, os.path.basename(path))
            if archive and os.path.exists(archive):
                os.unlink(archive)
            return True

        # If the IP moved, re-resolve and retry once.
        if attempt == 1:
            new_ip = _resolve_peer(name)
            if new_ip and new_ip != dest_ip:
                _debug(f"IP changed for {name}: {dest_ip} -> {new_ip}, retrying")
                dest_ip = new_ip
                continue
        break

    # All retries exhausted.
    detail = (err or out or "").strip().splitlines()
    body = (detail[-1] if detail else f"exit {rc}") + f" ({os.path.basename(path)} → {name})"
    if archive:
        body += f"\nArchive kept: {archive}"
    _notify("Taildrop: send failed", body)
    return False


def _send_async(files, label):
    """Stage all files then send each to the chosen peer, one at a time."""
    def work():
        staged = []
        for info in files:
            try:
                staged.append((info, _stage(info)))
            except Exception as exc:
                _notify("Taildrop: send failed",
                        f"{info.get_name()}: {exc}")
        if not staged:
            return

        total = len(staged)
        ok = 0
        for n, (_info, (path, archive)) in enumerate(staged):
            if _send_file(path, label, archive, done=n, total=total):
                ok += 1

        if ok == total:
            _notify("Taildrop", f"Sent {total} file(s) → {label}")
        else:
            _notify("Taildrop", f"Sent {ok}/{total} file(s) → {label}")

    threading.Thread(target=work, daemon=True, name="taildrop-send").start()


# -------------------------------------------------------------------- Nautilus

class TaildropProvider(GObject.GObject, Nautilus.MenuProvider):
    """Adds "Send via Taildrop" with one menu entry per online peer."""

    def _build_menu(self, files):
        """Return a list of Nautilus.MenuItem (empty list on error)."""
        try:
            if not files:
                return []

            peers = _get_peers()
            online = [p for p in peers if p["online"]]

            if not online:
                _debug("no online peers, returning empty menu")
                return []

            submenu = Nautilus.Menu()
            for p in online:
                item = Nautilus.MenuItem(
                    name=f"{MENU_PREFIX}::Send::{p['ip']}",
                    label=p["name"],
                    tip=f"Send to {p['name']} ({p['ip']})")
                item.connect("activate", self._on_activate, list(files), p["name"])
                submenu.append_item(item)

            n = len(files)
            top_label = LABEL if n == 1 else f"{LABEL} ({n} files)"
            top = Nautilus.MenuItem(
                name=f"{MENU_PREFIX}::Taildrop",
                label=top_label,
                tip="Send over your Tailnet")
            top.set_submenu(submenu)
            return [top]

        except Exception as exc:
            # NEVER raise from here — it kills Nautilus.
            _debug(f"_build_menu crashed: {exc}")
            return []

    def _on_activate(self, _item, files, peer_name):
        """Send files to the chosen peer. Peer name (not IP) is intentional:
        it forces _resolve_peer() to do a live lookup at send time."""
        _debug(f"sending {len(files)} file(s) to {peer_name}")
        _send_async(files, peer_name)

    # get_file_items: called when user right-clicks on selected files/folders.
    def get_file_items(self, files):
        return self._build_menu(files)

    # get_background_items: called when user right-clicks on empty space in a
    # folder. We intentionally do not offer to "send the folder" from here
    # because the folder-entry already appears when the folder itself is selected.
    def get_background_items(self, _folder):
        return []
