## v1.0.10

### The notification buttons actually work now, and there is an "Open" too

"Open folder" did nothing. The URI was hand-written as
`.../root/primary/Download/Tailscale`, but the storage documents provider
addresses a document as `.../document/primary%3ADownload%2FTailscale`; the
hand-rolled form has no `/document/` segment and resolves to nothing.
`DocumentsContract.buildDocumentUri` builds it correctly.

Two more things the URI needed:

- **The MIME type has to be pinned.** Without it the intent matches everything
  that handles `content://` loosely -- a browser and a chat app came back for a
  text file. The folder needs `vnd.android.document/directory` or DocumentsUI
  is not among the candidates at all.
- **"Open" is a new action**, for the first file received, typed from its
  extension. One action per file would exceed the three-action limit and the
  platform would silently drop the rest, so it is a single "Open".

"Open" sits first because the file is the reason for the alert.

### Offline devices are visible, and cannot be selected

The device list showed no presence at all, so an offline machine and a deleted
one looked identical -- and you could tick an offline device, get a success
toast, and have nothing delivered. Taildrop reports success on a queued send
regardless.

Peers now carry `online` through to the UI with a green or red dot. Offline
peers stay in the list, greyed, with their checkbox force-unchecked, their send
arrow disabled and a tap that explains why. `chosen` is pruned against the
online set on every refresh, so a device that goes down stops being a target by
itself. The button reads "No device online" rather than being a dead "Send
files". A device that is down should look down, not gone.

### Refresh button

The header has one. It forces a fresh root round-trip rather than re-rendering
what is already held, because peer presence, daemon state and the address list
all change on the device and not in the app. Without it the only way to see a
device come back was to close and reopen TailBridge.

### "No internet" is now distinguishable from "signed out"

Both showed an amber dot and said nothing. They are different problems: a
signed-out device cannot reach anything, while a signed-in device with no
internet still connects to peers on the same network directly -- it only loses
login, relays and MagicDNS. The header says which one it is, using the
daemon's own `Self.Online`.

### On INTERNET permission

Deliberately **not** added. TailBridge opens no sockets: it execs
`/system/bin/su` and talks to the daemon through that, and tailscaled does its
own networking outside the app's sandbox. The only permission the app declares
is `POST_NOTIFICATIONS`. Adding INTERNET would grant a capability it never uses
and tell users the app phones home, which it does not.

## v1.0.9

### Files sent from a desktop now arrive by themselves

Taildrop is push on the sender and pull on the receiver. `tailscale file cp`
drops a file in the phone's inbox and returns; the sender is told it worked and
the file goes nowhere, because the only thing that could pull it was a button in
an app that had to already be open. Linux had the mirror-image problem, and
`desktop/taildrop-receiver` covers that side.

`tailscaled.receive` now runs alongside the daemon: it pulls on an interval,
copies each delivery into `Downloads/Tailscale` so the Files app sees it, and
alerts you. Three bugs were found and fixed getting it there, all of them
silent:

- **`settings.sh` runs under `set -e`**, so killing a stale pid tore the whole
  script down. Every non-zero exit in a daemon loop is ordinary, so the loop
  now runs with errexit off.
- **The drain ran after `file get --wait`.** `--wait` pulled the file into the
  inbox first, so the snapshot that decides what is new had already seen it and
  classified every arrival as old. Files arrived and were never announced.
- **`case` with embedded newlines does not match under Android's shell**, so the
  same "is this new?" test silently answered yes to everything. Replaced with
  `grep -Fxq`.

The inbox is also never emptied by `tailscale file get`, so the receiver
compares against a pre-pull snapshot instead of re-announcing every file ever
received.

### Arrival alerts, with a button

`cmd notification post` returns success and posts nothing you can act on: it
creates a transient shell channel that is not in the shade afterwards and cannot
carry an action at all. So the script hands the delivery to TailBridge over an
explicit broadcast, and the app posts the real thing -- its own channel, high
importance, and an **Open folder** action that hands the path to the system file
manager via the storage documents provider.

One button, not two, on purpose. Opening a specific file needs a `FileProvider`
and a `file://` URI is not shareable across processes on Android 7+; opening the
folder needs neither. Same three taps, no manifest plumbing.

`POST_NOTIFICATIONS` is declared and requested on launch. It was missing
entirely, which is why the first attempt appeared to do nothing: the broadcast
reached the receiver, the process started, and the platform dropped the
notification on the floor.

## v1.0.8 -- release build verified

The v1.0.6 and v1.0.7 tags predate the feed-repo fix, so their `Build` steps ran
the old script and 404ed on the daemon binary. This tag is the first built with
the corrected script, and the first whose zips came from CI rather than by hand.

## v1.0.7

### Removed the original author's Telegram group

`tailscaled.service _troubleshoot` told users who hit a startup failure to
"Join telegram group at https://t.me/systembinsh/158". That is the original
author's support channel, inherited along with the rest of the module. It is
not ours to point people at, and sending strangers to someone else's group is
exactly the sort of thing that should not ship by accident.

The failure path now prints the commands that actually help -- follow the log,
check status, check for an update, start again -- instead of a link.

The repository's own homepage and description also pointed at that group and
copied the upstream summary; both now describe this fork instead.

## v1.0.6

### In-app sends did nothing at all

Tapping "Send to <peer>" opened the file picker, let you choose a file, and
then silently did nothing. No toast, no error, no log line -- the app behaved
as though the send had succeeded.

`launchPicker(List<String> ips)` was given the chosen peers and dropped them on
the floor. The list that `onActivityResult` dispatches to, `lastPick`, was
declared and read but never written to anywhere in the file, so it was always
empty and the result handler took its early-return every time.

The share sheet was unaffected because it routes through `pendingUris` and never
touches `lastPick`. That is why a share-sheet send reached the machine while an
in-app send vanished, which is the clearest evidence the two paths were never
equivalent.

The peers are now recorded before the picker opens, and the drop-it-quietly
branch logs its inputs and tells the user when nothing was picked -- a completed
picker result that goes nowhere should never again be invisible.

## v1.0.5 -- repo renamed

GitHub repo `paras-moharle/magisk-tailscaled` renamed to `paras-moharle/tailscaled-reborn`. Old URLs redirect automatically, but settings.sh, module.prop, update.json, the app's about link, and the install zip name now point at the new name directly. The second repo, `paras-moharle/tailscale-android-cli`, has been archived -- it was used for the original binary builds, which build-android.yml now runs from this repository.

## v1.0.4

### The auto-updater had never worked

`version_is_newer <a> <b>` returns true when **a** is newer than **b**, but all
three call sites passed `(installed, published)` and read the result as "an
update is available" -- the opposite sense. Measured against the real functions:

| feed | correct | before |
|---|---|---|
| 1.100 (older than installed 1.104.1) | hold | **update** -- would have downgraded the phone |
| 1.105, settled | update | **hold** -- would never have installed it |
| 1.105, fresh | hold | hold |
| 1.104 (same) | hold | hold |

So the updater would never install a newer build, and would have installed an
*older* one the moment the feed moved backwards. The "same version" row is the
only one that looked right, which is why this survived: installed and published
were equal in every state the module was ever in, so the one test anyone would
run by hand passes.

The arguments are now the other way round, the contract is spelled out above the
function, and `up_to_date`, the status line and `check` were all corrected.

### Settling window

A build published minutes ago is not offered for `TS_UPDATE_SETTLE_HOURS`
(default 6). The check interval was never the risk -- the timing was: without a
window, a bad upstream release reaches every phone within minutes of CI
publishing it, which is too fast to notice and withdraw. Rollback still protects
each device, but the fleet should not have to roll back at all.

The publication time travels in `tailscale-latest.json` as `publishedAtEpoch`, an
epoch integer rather than ISO-8601, because Android's toybox `date` cannot parse
a formatted timestamp. A feed with no such field is treated as settled, so an
older feed file can never block updates.

### Failures are reported now

A failed Android port build files an issue instead of dying in a CI log. See
v1.0.3.

## v1.0.3

An audit pass over the app and the module, mostly fixing things that were wrong
in ways that did not announce themselves.

### The send path lied

- **A shared file could go to the wrong device.** The picker's destination list
  was stored in a field that was never cleared, so after one picker-based send
  every later shared file was delivered to whichever device that picker had
  been opened for -- ignoring the checkboxes the user had just ticked.
- **"Sent 0 file(s)" was reported as success.** If every file failed to stage,
  nothing was transferred and no counter recorded the failure, so the run took
  the all-clear branch. Staging failures are now counted.
- **The per-device send arrow destroyed the selection.** Its comment said it
  would not touch it; it cleared the set *and* persisted the result. It now
  sends to that one device and leaves the selection alone.
- **The Send button could lie.** Offline peers stayed in the ticked set, so it
  read "Send to laptop" while there was nothing to send to. The set is now
  pruned against what is actually online.

### The poll died and never came back

A tick that arrived while a slow `su` call was still running returned early
*without rescheduling*, ending the poll chain for the rest of the session. The
header would then freeze until the app was backgrounded and resumed.

### Lifecycle and threading

- No `onDestroy` cleanup existed, so work queued on the UI handler could try to
  show a dialog on a window that was gone.
- `onNewIntent` never called `setIntent`, so after any recreation the app read
  the original launcher intent instead of the share. Staged shares are now saved
  and restored across rotation and process death.
- `Root` read an error buffer without the monitor its writer thread appends
  under, while that thread could still be running.

### Taildrop could receive nothing

The receive path existed but was never reachable from the UI, so inbound files
sat in a root-only inbox nobody could open. **System → Receive files** now moves
them into `Downloads/Tailscale` and lists what arrived.

### Smaller things

- "Run tailscaled" followed `backend == "Running"` and so showed an
  unauthenticated daemon as stopped; it now uses the module's own `daemon` field.
- `latestVersion` was written on worker threads and read on the UI thread with no
  synchronisation.
- The "X is available" sub-label on the update row was unreachable, so the row
  never showed why it mattered.
- `<queries>` was missing, so on API 30+ the root-manager button could not see
  any root manager and silently fell through to Android app settings.
- Detached views were left reachable after the root gate, letting a late failed
  write flip a checkbox nobody can see.
- Removed: the refresh button (dead since the poll), `materialize()`,
  `Peer.toString()`, `join()`, `daemonSwitch`, `rootError`, the unreachable
  `refresh(userInitiated)` branch, two unused imports, 16 unused strings, three
  unused drawables, two unused styles and an unused id. `Peer.exitNode` was
  parsed and never shown -- it is now.
- `.gitignore` had duplicate entries.

## v1.0.2

### Sign-in did not work, and failed silently

`tailscaled.api login` ran `tailscale up`. That is the wrong command for
authentication: `up` changes settings, and it refuses to run at all when the
node has any non-default flag set -- which is precisely the state anyone who has
ever touched a setting is in:

```
Error: changing settings via 'tailscale up' requires mentioning all
non-default flags. To proceed, either re-run your command with --reset ...
```

So the URL was never produced, the app had nothing to open, and tapping "Sign
in" appeared to do nothing. It now uses `tailscale login`, which only
authenticates and ignores preferences.

Two more things had to be true for it to actually work:

- **The login has to outlive the app's shell session.** `tailscale login` blocks
  until the browser round-trip finishes. It used to run as a child of the script
  the app invokes, and the app tears that `su` session down the moment it has
  read the output -- killing the pending login, so even a correct URL led to a
  device that silently stayed signed out. It is now detached with `nohup` and
  reparented to init.
- **Failure is now reported.** If no URL can be produced, the endpoint returns
  `ok:false` with the command's own output, and the app shows it instead of
  reporting a sign-in that never happened.

### The daemon switch disagreed with reality

"Run tailscaled" was driven by `backend == "Running"`, so an unauthenticated or
starting daemon rendered as *stopped* while it was plainly alive. It now follows
the module's own `daemon` field. The Send button keeps requiring a signed-in
node, because that is when peers are actually reachable.

### No more internal names

The header showed Tailscale's raw backend enums -- "NeedsLogin", "NoState".
Those are now "Not signed in", "Connecting…", "Stopped". The placeholder
hostname `localhost` is hidden until you are actually signed in.

### Root permission gets a real screen

Missing root was a line of text above an otherwise normal screen. It is now a
full-screen gate: large lock, the message centred, and one obvious action that
opens your **root manager** -- Android's app settings is not where superuser
permission lives, so sending you there was pointless. The nav bar is hidden
while it is up, because nothing behind it is reachable.

### Uninstalled cleanly

`uninstall.sh` left TailBridge installed: a launcher icon with no daemon behind
it that could only ever say "grant root permission". Removing the module now
removes the app too.

### Dropped the refresh button

It was redundant next to the five-second poll, and redundant controls that do
nothing visible are worse than no control at all.

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

