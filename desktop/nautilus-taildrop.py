#!/usr/bin/env python3
# SPDX-License-Identifier: BSD-3-Clause
# Copyright (c) 2026 paras-moharle
#
# Nautilus context-menu extension: send files to a Tailnet peer with Taildrop.
#
#   Nautilus "Send via Taildrop"  ->  submenu of online devices
#
# Installing this file makes Nautilus show "Send via Taildrop" on files and on
# folders. Everything else -- the daemon, the account, the peers -- is whatever
# `tailscale` already reports. The extension keeps no state and has no
# settings: it asks the daemon for the peer list each time the menu is built.
#
# Folders are tarred to a temporary file first, because Taildrop moves files,
# not directory trees. The archive is deleted after a successful send and kept
# on failure so the work can be retried rather than lost.
#
# Requires: nautilus, python3-gi, the tailscale CLI and a running daemon.

import json
import os
import subprocess
import tarfile
import tempfile
import threading

import gi

gi.require_version("Gio", "2.0")
gi.require_version("GLib", "2.0")
# Gdk is deliberately NOT required or imported. Nautilus 50 already holds Gdk
# 4.0; pulling Gdk 3.0 into this process to reach a beep() would load a second
# major version of the same library and abort the file manager.

# Nautilus 50 lives in the 4.1 namespace; older releases exported 4.0.
_NAMESPACES = ("4.1", "4.0")
for _ns in _NAMESPACES:
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

# Sending a large file outlives a menu invocation; never block the UI thread.
SEND_TIMEOUT = 600


def _log(msg):
    print(f"taildrop: {msg}", flush=True)


def _run(args, timeout=30):
    """Run a command, returning (rc, stdout, stderr) with text output."""
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
    return (
        p.returncode,
        p.stdout.decode("utf-8", "replace"),
        p.stderr.decode("utf-8", "replace"),
    )


def _notify(title, body):
    """Surface the outcome. A failed send must not vanish silently.

    Only libnotify is used, via whichever binding exists. Neither touches the
    toolkit Nautilus is running, so neither can take the file manager down --
    which is the whole reason Gdk was dropped earlier.
    """
    try:
        import notify2

        notify2.init("taildrop")
        notify2.Notification(title, body).show()
        return
    except Exception:
        pass
    if _run(["notify-send", "-a", "Taildrop", title, body], timeout=5)[0] == 0:
        return
    # Last resort: stderr, which lands in the journal.
    _log(f"{title}: {body}")


def daemon_state():
    """BackendState, or '' when the daemon cannot be reached."""
    rc, out, _ = _run(["tailscale", "status", "--json"])
    if rc != 0 or not out.strip():
        return ""
    try:
        return json.loads(out).get("BackendState", "") or ""
    except json.JSONDecodeError:
        return ""


def peers():
    """Online peers as [(name, tailscale_ip)], IPv4 preferred.

    A peer only matters here if it is online. IPv4 is preferred because it is
    the address that resolves without extra work on the receiving daemon.
    """
    rc, out, _ = _run(["tailscale", "status", "--json"])
    if rc != 0:
        return []
    try:
        data = json.loads(out)
    except json.JSONDecodeError:
        return []

    found = []
    for peer in (data.get("Peer") or {}).values():
        if not peer.get("Online"):
            continue
        ips = peer.get("TailscaleIPs") or []
        ip = next((a for a in ips if ":" not in a), None) or next(iter(ips), None)
        if not ip:
            continue
        name = peer.get("HostName") or peer.get("DNSName") or ip
        # "nord3.tailaddd.ts.net." -> "nord3"
        found.append((name.split(".")[0], ip))
    return sorted(found, key=lambda pair: pair[0].lower())


def _archive(folder):
    """tar.gz a directory so Taildrop has one file to move."""
    base = os.path.basename(folder.rstrip("/")) or "folder"
    fd, path = tempfile.mkstemp(prefix="taildrop-", suffix=f"-{base}.tar.gz")
    os.close(fd)
    with tarfile.open(path, "w:gz") as tf:
        tf.add(folder, arcname=base)
    return path


def _stage(fileinfo):
    """Local path for a selection: (path, archive_to_delete_or_None)."""
    uri = fileinfo.get_uri()
    if not uri.startswith("file://"):
        raise ValueError("only local files can be sent")
    path = Gio.File.new_for_uri(uri).get_path()
    if path is None:
        raise ValueError(f"cannot resolve {uri}")
    if os.path.isdir(path):
        return _archive(path), path
    return path, None


def send_async(path, dest, name, archive):
    """Send one file to one peer off the UI thread, then report the result."""

    def work():
        rc, out, err = _run(
            ["tailscale", "file", "cp", path, f"{dest}:"], timeout=SEND_TIMEOUT
        )
        if rc == 0:
            body = f"{os.path.basename(path)} -> {name}"
            _log(f"sent {body}")
            if archive and os.path.exists(archive):
                os.unlink(archive)
        else:
            detail = (err or out or "").strip().splitlines()
            body = (detail[-1] if detail else f"exit {rc}") + f" ({os.path.basename(path)})"
            _log(f"send failed: {body}")
            if archive:
                # Keep it so the send can be retried rather than rebuilt.
                _log(f"archive kept at {archive}")
                body += f"\nArchive kept: {archive}"
        GLib.idle_add(_notify, "Taildrop", body)

    threading.Thread(target=work, daemon=True).start()


class TaildropProvider(GObject.GObject, Nautilus.MenuProvider):
    """Adds "Send via Taildrop" with one submenu entry per online device."""

    def _items_for(self, files):
        """Menu items for a selection, or [] to add nothing to the menu."""
        if not files:
            return []
        # Nothing to offer while the daemon is down or nobody else is online.
        if daemon_state() in ("", "Stopped", "NeedsLogin"):
            return []
        online = peers()
        if not online:
            return []

        staged = []
        for info in files:
            try:
                staged.append((info, _stage(info)))
            except Exception as exc:
                _log(f"cannot stage {info.get_name()}: {exc}")
        if not staged:
            return []

        label = LABEL if len(staged) == 1 else f"{LABEL} ({len(staged)} files)"
        tip = "Send over your Tailnet with Taildrop"

        submenu = Nautilus.Menu()
        for name, ip in online:
            item = Nautilus.MenuItem(name=f"{MENU_PREFIX}::Send::{ip}", label=name,
                                     tip=f"Send to {name} ({ip})")
            item.connect("activate", self._on_activate, staged, name, ip)
            submenu.append_item(item)

        top = Nautilus.MenuItem(name=f"{MENU_PREFIX}::Taildrop", label=label, tip=tip)
        top.set_submenu(submenu)
        return [top]

    def _on_activate(self, _item, staged, name, ip):
        # One command per file so a failure names the file that caused it.
        for _info, (path, archive) in staged:
            send_async(path, ip, name, archive)
        return True

    # Nautilus calls these two; folder background is deliberately a no-op --
    # "send this folder" already appears when the folder itself is selected.
    def get_file_items(self, files):
        return self._items_for(files)

    def get_background_items(self, _folder):
        return []