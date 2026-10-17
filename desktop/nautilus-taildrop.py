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
import shutil
import subprocess
import sys
import tarfile
import tempfile
import threading
import urllib.parse
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

# How long one `tailscale file cp` may take. A Taildrop send of a normal file
# completes in one or two seconds; the slow part is the daemon deciding the
# peer is not answering, which it resolves one way or the other within a few
# seconds. This used to be 300s per file per attempt, so a single send that
# wedged kept its detached process alive for ten minutes, and every send during
# that window left another one behind. These are ceilings for a stuck command,
# not expectations.
PER_FILE_TIMEOUT = 120

# Hard stop for one sender process, however many files it was given. The
# sender is detached on purpose so it survives Nautilus closing, which also
# means nothing else will ever reap it: without a deadline it is a process
# that can outlive every reason for its own existence.
SENDER_DEADLINE = 600

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

_LOG_FILE = os.path.expanduser("~/.cache/taildrop-nautilus.log")
_log_lock = threading.Lock()

def _debug(msg):
    """Log to a file always, and to stderr when NAUTILUS_DEBUG=1.

    Nautilus is started from the desktop session, so NAUTILUS_DEBUG is not set
    for the process that actually matters and anything written only to stderr
    is lost. Every complaint about this extension being "fragile" was
    unanswerable for exactly that reason: there was no record of whether the
    menu was built, which peers it listed, or whether the click arrived.

    The file is appended to, capped in size, and every failure to write it is
    swallowed: logging must never be able to take the file manager down.
    """
    ts = time.strftime("%H:%M:%S")
    line = (f"[taildrop {ts} {threading.current_thread().name}] {msg}\n")
    try:
        # One writer at a time: the sender and the menu thread both log, and
        # interleaved appends were losing lines exactly when a trace mattered.
        with _log_lock:
            try:
                if os.path.getsize(_LOG_FILE) > 262144:
                    os.replace(_LOG_FILE, _LOG_FILE + ".1")
            except OSError:
                pass
            with open(_LOG_FILE, "a", encoding="utf-8") as fh:
                fh.write(line)
    except Exception:
        pass
    if os.environ.get("NAUTILUS_DEBUG") == "1":
        print(line, end="", file=sys.stderr, flush=True)


def _run(args, timeout=30):
    """Run a command, return (rc, stdout, stderr). Never raises."""
    try:
        _t0 = time.monotonic()
        p = subprocess.run(
            args,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            timeout=timeout,
            check=False,
        )
        _dt = time.monotonic() - _t0
        if _dt > 0.5:
            _debug(f"SLOW {args[0]} {' '.join(args[1:3])}: {_dt:.1f}s")
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
        _debug("peer lock held, fetching peers")
        peers = _fetch_peers()
        _debug(f"peer fetch done: {len(peers)} peers")
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
    """Tar a folder into a temp tarball named after the folder.

    The tarball used to be made with tempfile.mkstemp(prefix="taildrop-"), which
    puts its own random string in front of the name: sending a folder called
    "Holiday" arrived as taildrop-f2dsoszy-Holiday.tar.gz. The temporary name
    is where the file happens to sit, not what it is called, and the receiver
    sees only the name.
    """
    base = os.path.basename(folder.rstrip("/")) or "folder"
    scratch = tempfile.mkdtemp(prefix="taildrop-")
    path = os.path.join(scratch, f"{base}.tar.gz")
    try:
        with tarfile.open(path, "w:gz") as tf:
            tf.add(folder, arcname=base)
    except Exception:
        shutil.rmtree(scratch, ignore_errors=True)
        raise
    return path


def _discard_temp(path):
    """Remove a temp tarball and the scratch directory holding it."""
    try:
        shutil.rmtree(os.path.dirname(path), ignore_errors=True)
    except Exception:
        pass


def _stage(fileinfo):
    """Return (local_path_to_send, temp_to_delete_or_None).

    For a folder, the temp is the tarball we just made. It used to return the
    user's own folder as the thing to delete, so a successful folder send ended
    in os.unlink() on a directory: IsADirectoryError, the sender thread died,
    and the user got no notification at all and no file. Deleting the folder
    would have been even worse had the call succeeded.
    """
    name, uri = fileinfo
    _debug(f"staging {name} ({uri})")
    if not uri.startswith("file://"):
        raise ValueError("only local files can be sent")
    # Decoded with the standard library rather than Gio.File. Anything from the
    # GObject introspection stack is unsafe to touch from the sender thread,
    # and this thread must stay plain Python and subprocess.
    path = urllib.parse.unquote(urllib.parse.urlparse(uri).path)
    if not path:
        raise ValueError(f"cannot resolve {uri}: {uri}")
    if os.path.isdir(path):
        tarball = _archive(path)
        return tarball, tarball
    _debug(f"staged {name} -> {path}")
    return path, None


def _safe_name(fileinfo):
    """Name of a selection entry, for logging. Safe to call anywhere."""
    try:
        return str(fileinfo[0])
    except Exception:
        return "<unnamed>"


def _describe(fileinfo):
    """Read a Nautilus.FileInfo into a plain (name, uri) tuple.

    Must be called on the main thread, while the menu is being built. This is
    the whole bug: the sender thread used to call fileinfo.get_uri() itself,
    and a Nautilus.FileInfo is a GObject that will not be touched from
    another thread. That call blocked forever, so the click registered, the
    sender thread started, and then nothing happened at all -- no send, no
    error, no notification. The log stopped at "sender thread started" every
    time, which is exactly what it did.

    Copying the two strings out on the main thread costs nothing and leaves
    the sender thread touching nothing but str, os and subprocess.
    """
    try:
        return (str(fileinfo.get_name()), str(fileinfo.get_uri()))
    except Exception as exc:
        _debug(f"could not read selection: {exc!r}")
        raise


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
    _debug(f"resolving {name} (acquiring peer lock)")
    """Live lookup of peer <name>, returning its current IP.

    Always forced: the menu may have been opened minutes ago, and tailscale
    re-registers and changes a device's tailnet IP on every connect. Sending to
    a stale IP fails, or worse, is aimed at whatever now holds that address.
    """
    # Try the snapshot first. It was refreshed when the menu was built, seconds
    # ago, so this is instant. Forcing a fresh query here cost six seconds on
    # this machine: the sender thread competing with the UI thread made each
    # `tailscale status` take seconds rather than milliseconds, and the send sat
    # there looking dead.
    #
    # Only if the name is missing does it query again, so a stale snapshot
    # cannot hide a device that appeared since the menu was built.
    for p in _get_peers():
        if p["name"] == name:
            return p["ip"]
    for p in _get_peers(force=True):
        if p["name"] == name:
            return p["ip"]
    return None


def _real_error(rc, err):
    """Did `tailscale file cp` actually fail?

    rc is authoritative. The CLI exits non-zero when it could not deliver --
    "can't send to 100.99.99.99: unknown target; not in your Tailnet" -- and
    zero when it did.

    stderr is not. A peer that has been idle makes the CLI print

        # warning: 100.76.39.7 is not replying; trying anyway

    and then deliver the file anyway, over a relay. Treating any stderr output
    as failure reported "Sent 0/1" for transfers that had in fact already
    arrived on the phone. Because that warning only appears when the phone has
    been asleep, the same file from the same machine failed or succeeded at
    random, which is exactly what "sending is broken" looked like from the
    outside.

    stderr is still read, but only for lines that are not a warning, so an
    error printed alongside a zero exit is not silently accepted.
    """
    if rc != 0:
        return True
    for line in err.splitlines():
        s = line.strip()
        if not s or s.startswith("#") or "warning" in s.lower():
            continue
        return True
    return False


def _send_file(path, name, archive, done, total, peer_ip=None):
    """Send one file to peer <name> (not IP — resolved at call time)."""
    _debug(f"send_file {os.path.basename(path)} -> {name}")
    _progress(done, total, os.path.basename(path))

    # The address the menu already had. Resolving here instead cost seconds
    # per send and could not have found a different answer.
    dest_ip = peer_ip
    _debug(f"using menu address {dest_ip} for {name}")
    per_file = PER_FILE_TIMEOUT

    for attempt in (1, 2):
        if dest_ip is None:
            _debug(f"cannot resolve peer {name}")
            _notify("Taildrop: send failed",
                    f"{name} is no longer reachable")
            return False

        rc, out, err = _run(
            ["tailscale", "file", "cp", path, f"{dest_ip}:"],
            timeout=per_file,
        )
        _debug(f"file cp {os.path.basename(path)} -> {dest_ip}: "
               f"rc={rc} err={err.strip()[:200]!r}")
        if not _real_error(rc, err):
            _progress(done + 1, total, os.path.basename(path))
            if archive:
                _discard_temp(archive)
            return True

        # The address from the menu did not work. Now a live lookup is
        # justified: the device may have re-registered and moved.
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


def _send_async(files, label, peer_ip=None):
    """Send, in a separate process.

    This used to be a Python thread, and that was the last reason sending felt
    broken. Nautilus runs a GTK main loop that holds the GIL, so a background
    thread is not scheduled until the loop yields it. Measured here: the click
    at 20:31:32 did not reach `tailscale file cp` until 20:31:52, with nothing
    but an empty loop in between -- twenty seconds of the thread simply not
    running. The same work from any other process takes about a second.

    A child process has its own interpreter and its own GIL, so it is not
    scheduled around anything Nautilus is doing. It also survives Nautilus
    being closed mid-send, which a daemon thread did not: the thread died with
    the interpreter and the file was lost with no report.

    Staging happens here, on the main thread, where the selection has already
    been read out of the GObject, so the child is handed plain paths and needs
    no Nautilus at all.
    """
    staged = []
    for name, uri in files:
        try:
            staged.append(_stage((name, uri)))
        except Exception as exc:
            _debug(f"staging failed for {name}: {exc!r}")
            _notify("Taildrop: send failed", f"{name}: {exc}")
    if not staged:
        return

    argv = [sys.executable, os.path.abspath(__file__), "--send",
            str(peer_ip or ""), str(label)]
    for path, archive in staged:
        argv.append(path)
        if archive:
            argv.append(archive)

    env = dict(os.environ)
    env["PYTHONPATH"] = os.pathsep.join(
        p for p in [os.path.dirname(os.path.abspath(__file__)),
                    env.get("PYTHONPATH", "")] if p)
    _debug(f"spawning sender: {' '.join(argv[2:])}")
    try:
        subprocess.Popen(argv, env=env,
                         stdout=subprocess.DEVNULL,
                         stderr=subprocess.DEVNULL,
                         start_new_session=True)
    except Exception as exc:
        _debug(f"could not start sender: {exc!r}")
        _notify("Taildrop: send failed", f"Could not start the sender: {exc}")


def _send_worker_main(argv):
    """Entry point for the detached sender process. argv: ip, label, path[, archive]..."""
    ip, label = argv[0], argv[1]
    paths = argv[2:]
    total = len(paths)
    deadline = time.monotonic() + SENDER_DEADLINE
    ok = 0
    archives = []
    try:
        for n, item in enumerate(paths):
            path, archive = (item, None)
            if n % 2 == 1:
                archive = item
                archives.append(item)
            if time.monotonic() > deadline:
                _debug("sender hit its deadline; stopping early")
                _notify("Taildrop: send stopped",
                        f"Timed out after {SENDER_DEADLINE}s")
                break
            try:
                if _send_file(path, label, archive, done=n // 2, total=total,
                              peer_ip=ip or None):
                    ok += 1
            except Exception as exc:
                _debug(f"send raised for {path}: {exc!r}")
    finally:
        # A folder that never sent would otherwise leave its scratch directory
        # behind for the session. Sent ones are removed by _send_file; this
        # catches the rest.
        for a in archives:
            _discard_temp(a)
    _debug(f"sender finished: {ok}/{total} delivered to {label}")
    return 0 if ok == total else 1


def _send_work(files, label, peer_ip):
    staged = []
    failed = 0
    for info in files:
        try:
            staged.append((info, _stage(info)))
        except Exception as exc:
            failed += 1
            _debug(f"staging failed for {_safe_name(info)}: {exc!r}")
            _notify("Taildrop: send failed", f"{_safe_name(info)}: {exc}")
    if not staged:
        return

    total = len(staged)
    ok = 0
    for n, (_info, (path, archive)) in enumerate(staged):
        try:
            if _send_file(path, label, archive, done=n, total=total,
                        peer_ip=peer_ip):
                ok += 1
        except Exception as exc:
            failed += 1
            _debug(f"send raised for {path}: {exc!r}")
            _notify("Taildrop: send failed",
                    f"{os.path.basename(path)}: {exc}")

    if ok == total and not failed:
        _notify("Taildrop", f"Sent {total} file(s) → {label}")
    else:
        _notify("Taildrop", f"Sent {ok}/{total + failed} file(s) → {label}")


# -------------------------------------------------------------------- Nautilus

class TaildropProvider(GObject.GObject, Nautilus.MenuProvider):
    """Adds "Send via Taildrop" with one menu entry per online peer."""

    def _build_menu(self, files):
        """Return a list of Nautilus.MenuItem (empty list on error)."""
        try:
            if not files:
                return []

            # Read every FileInfo now, on the main thread, while it is legal
            # to. The sender thread must never see these objects.
            selection = [_describe(f) for f in files]

            # Log the selection: if the click never reaches the sender, this is
            # the record of what was on offer at the time.
            _debug(f"get_file_items: {len(selection)} selection(s): "
                   + ", ".join(_safe_name(f) for f in selection))

            peers = _get_peers()
            online = [p for p in peers if p["online"]]
            _debug(f"menu: {len(online)} online of {len(peers)} known: "
                   + (", ".join(p["name"] for p in online) or "none"))

            if not online:
                _debug("no online peers, returning empty menu")
                return []

            submenu = Nautilus.Menu()
            for p in online:
                item = Nautilus.MenuItem(
                    name=f"{MENU_PREFIX}::Send::{p['ip']}",
                    label=p["name"],
                    tip=f"Send to {p['name']} ({p['ip']})")
                item.connect("activate", self._on_activate, selection,
                                  p["name"], p["ip"])
                submenu.append_item(item)

            n = len(selection)
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

    def _on_activate(self, _item, files, peer_name, peer_ip):
        """Send files to the peer the user just picked.

        The address from the menu is used as-is. Re-querying the peer list at
        send time cost seconds on every single send, and it bought nothing:
        the menu had just listed that device, so the address cannot have
        changed in the interval between drawing the menu and clicking it. If it
        somehow has, the send fails and the retry re-resolves, which is the
        only case where the lookup was ever needed.
        """
        _debug(f"sending {len(files)} file(s) to {peer_name} ({peer_ip})")
        _send_async(files, peer_name, peer_ip)

    # get_file_items: called when user right-clicks on selected files/folders.
    def get_file_items(self, files):
        return self._build_menu(files)

    # get_background_items: called when user right-clicks on empty space in a
    # folder. We intentionally do not offer to "send the folder" from here
    # because the folder-entry already appears when the folder itself is selected.
    def get_background_items(self, _folder):
        return []


# ------------------------------------------------------------------ sender mode

# When invoked as `nautilus-taildrop.py --send <ip> <label> <path>...`, this
# file is not an extension at all: it is the detached sender that the menu
# spawns. Nautilus must never import this path, and the sender must never
# import Nautilus, so the two live in one file but never run in one process.
if __name__ == "__main__" and len(sys.argv) > 1 and sys.argv[1] == "--send":
    sys.exit(_send_worker_main(sys.argv[2:]))
