// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026 paras-moharle

package com.paras.tailbridge;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Tailscale for a rooted phone running the daemon in userspace mode.
 *
 * Deliberately small: every control here was verified to actually change
 * something on the device. Taildrop is the headline feature; the rest is
 * status, device list, the preference toggles that genuinely take effect,
 * service control, software updates and the daemon log.
 *
 * There is no web interface: this app is the whole thing.
 */
public class MainActivity extends Activity {

	private static final String CONSOLE_URL = "https://login.tailscale.com/admin/machines";
	private static final String GITHUB_URL = "https://github.com/paras-moharle/magisk-tailscaled";
	private static final int REQ_PICK = 1001;

	private final Handler ui = new Handler(Looper.getMainLooper());

	/** peer name -> ip for online peers. */
	private final Map<String, String> peers = new LinkedHashMap<>();
	/** peers ticked as send targets. */
	private final Set<String> chosen = new LinkedHashSet<>();
	/** pref key -> the switch showing it, so a failed write can be undone. */
	private final Map<String, CheckBox> switches = new LinkedHashMap<>();

	/** Self-name / address values, patched in place by the poller. */
	private final List<TextView> nameValues = new ArrayList<>();
	private final List<String> nameShown = new ArrayList<>();

	private TextView statusView;
	private TextView versionView;
	private View dotView;

	private LinearLayout pSend;
	private LinearLayout pSettings;
	private LinearLayout pSystem;
	private final View[] navs = new View[3];

	private Button sendBtn;
	private CheckBox daemonSwitch;
	/** row that offers the software update once a newer build is known. */
	private View updateRow;
	private TextView updateTitle;
	private TextView updateSub;

	private JSONObject status;
	private String rootError;
	private boolean polling;
	private static final long POLL_MS = 5000;
	private String latestVersion = "";

	private List<String> targets = new ArrayList<>();
	private final List<Uri> pendingUris = new ArrayList<>();
	private String defaultTargets = "";

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_main);

		statusView = findViewById(R.id.status);
		versionView = findViewById(R.id.version);
		dotView = findViewById(R.id.dot);
		pSend = findViewById(R.id.pSend);
		pSettings = findViewById(R.id.pSettings);
		pSystem = findViewById(R.id.pSystem);

		navs[0] = findViewById(R.id.navSend);
		navs[1] = findViewById(R.id.navSettings);
		navs[2] = findViewById(R.id.navSystem);
		for (int i = 0; i < navs.length; i++) {
			final int n = i;
			navs[i].setOnClickListener(v -> tab(n));
		}

		findViewById(R.id.refresh).setOnClickListener(v -> refresh(true));

		defaultTargets = getPreferences(0).getString("targets", "");
		captureShare(getIntent());
		tab(0);
		checkRoot();
	}

	/**
	 * Root first, before anything else is attempted.
	 *
	 * Without it every later failure looks like a broken daemon rather than an
	 * app the root manager has not been told to trust.
	 */
	private void checkRoot() {
		new Thread(() -> {
			try {
				Root.sh("id");
				ui.post(this::refresh);
			} catch (Exception e) {
				ui.post(() -> renderOffline(getString(R.string.need_root_short)));
			}
		}).start();
	}

	@Override
	protected void onNewIntent(android.content.Intent intent) {
		super.onNewIntent(intent);
		captureShare(intent);
		tab(0);
	}

	/**
	 * Share-sheet entry point.
	 *
	 * A share lands on the Send tab with the files staged, and the same device
	 * list the app already has decides where they go — one interface, no
	 * separate share screen and no intermediate dialog.
	 */
	private void captureShare(android.content.Intent in) {
		if (in == null || in.getAction() == null) {
			return;
		}
		String a = in.getAction();
		if (android.content.Intent.ACTION_SEND.equals(a)) {
			android.net.Uri u = in.getParcelableExtra(android.content.Intent.EXTRA_STREAM);
			if (u != null) {
				pendingUris.add(u);
			} else {
				addSharedText(in.getStringExtra(android.content.Intent.EXTRA_TEXT));
			}
		} else if (android.content.Intent.ACTION_SEND_MULTIPLE.equals(a)) {
			ArrayList<android.net.Uri> list =
					in.getParcelableArrayListExtra(android.content.Intent.EXTRA_STREAM);
			if (list != null) {
				pendingUris.addAll(list);
			}
			addSharedText(in.getStringExtra(android.content.Intent.EXTRA_TEXT));
		}
		if (!pendingUris.isEmpty()) {
			refresh(false);
		}
	}

	/** Taildrop moves files, so shared text becomes one. */
	private void addSharedText(String text) {
		if (text == null || text.trim().isEmpty()) {
			return;
		}
		try {
			java.io.File dir = new java.io.File(getCacheDir(), "share");
			if (!dir.exists() && !dir.mkdirs()) {
				return;
			}
			java.io.File f = new java.io.File(dir, "shared-" + System.currentTimeMillis() + ".txt");
			try (java.io.OutputStream os = new java.io.FileOutputStream(f)) {
				os.write(text.getBytes("UTF-8"));
			}
			pendingUris.add(android.net.Uri.fromFile(f));
		} catch (Exception ignored) {
			// nothing to stage
		}
	}

	@Override
	protected void onResume() {
		super.onResume();
		refresh(false);
		startPolling();
	}

	@Override
	protected void onPause() {
		super.onPause();
		stopPolling();
	}

	private void tab(int n) {
		ScrollView[] scrolls = {
				findViewById(R.id.scrollSend),
				findViewById(R.id.scrollSettings),
				findViewById(R.id.scrollSystem),
		};
		for (int i = 0; i < scrolls.length; i++) {
			scrolls[i].setVisibility(i == n ? View.VISIBLE : View.GONE);
			// Active tab is white, inactive is grey. The accent is saved for
			// the one thing that should pull the eye: Send.
			int c = getColor(i == n ? R.color.text : R.color.hint);
			ImageView icon = (ImageView) navs[i].findViewById(R.id.navIcon);
			if (icon != null) {
				icon.setColorFilter(c);
			}
			TextView label = (TextView) navs[i].findViewById(R.id.navLabel);
			if (label != null) {
				label.setTextColor(c);
				label.setTypeface(null, i == n ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
			}
		}
	}

	/** Every dialog goes through here so they all share the app's surface. */
	private AlertDialog.Builder alert() {
		return new AlertDialog.Builder(this, R.style.Theme_Share_Dialog);
	}

	// ----------------------------------------------------------------- data

	/** One local root round-trip fills every panel; the network check follows. */
	private void refresh() {
		refresh(false);
	}

	private void refresh(boolean userInitiated) {
		if (userInitiated) {
			toast(getString(R.string.checking));
		}
		new Thread(() -> {
			JSONObject st = null;
			String err = null;
			try {
				st = Api.status();
			} catch (Root.Denied e) {
				err = getString(R.string.need_root_short);
			} catch (Exception e) {
				err = String.valueOf(e.getMessage());
			}

			final JSONObject fSt = st;
			final String fErr = err;
			ui.post(() -> {
				status = fSt;
				rootError = fErr;
				if (fSt == null) {
					renderOffline(fErr);
				} else {
					render(fSt);
				}
			});

			if (st == null) {
				return;
			}
			final JSONObject stFinal = st;

			// Off the critical path.
			new Thread(() -> {
				try {
					final String latest = Api.updateCheck();
					final String cur = stFinal.optString("version", "");
					if (latest != null && !latest.isEmpty() && !latest.equals(cur)) {
						latestVersion = latest;
						ui.post(this::paintUpdateRow);
					}
				} catch (Exception ignored) {
					// offline; leave the row as the plain "check" action
				}
			}).start();
		}).start();
	}

	/**
	 * Every read of the daemon goes through `su`, so any failure here means we
	 * do not have root. Say that in plain words rather than showing a raw shell
	 * error, which means nothing to the person holding the phone.
	 */
	private void renderOffline(String err) {
		statusView.setText(getString(R.string.need_root_short));
		versionView.setText(getString(R.string.grant_root));
		dotView.getBackground().mutate().setTint(getColor(R.color.warn));

		pSend.removeAllViews();
		pSettings.removeAllViews();
		pSystem.removeAllViews();

		note(pSend, getString(R.string.need_root));
		LinearLayout g = group(pSend, getString(R.string.root_setup));
		action(g, getString(R.string.root_manager), getString(R.string.root_manager_sub),
				v -> openAppSettings());

		// The underlying reason, kept small: useful when reporting a problem,
		// not something to show as the headline.
		if (err != null && !err.isEmpty() && !err.equals(getString(R.string.need_root_short))) {
			note(pSend, err);
		}
	}

	/** Paint the header from a status document. @return true when online. */
	private boolean applyHeader(JSONObject st) {
		String backend = st.optString("backend", "?");
		boolean online = "Running".equals(backend);
		JSONObject self = st.optJSONObject("self");
		String host = self == null ? "" : self.optString("host", "");

		statusView.setText(online ? getString(R.string.connected)
				: "Stopped".equals(backend) ? getString(R.string.stopped) : backend);
		versionView.setText((host.isEmpty() ? "" : host + "  \u00b7  ") + "tailscaled "
				+ st.optString("version", "?"));
		dotView.getBackground().mutate().setTint(getColor(
				online ? R.color.ok : ("Stopped".equals(backend) ? R.color.err : R.color.warn)));
		return online;
	}

	/**
	 * Update only what can change under the user's fingers.
	 *
	 * Renaming a device in the admin console is the common case, and waiting for
	 * a manual refresh made the app look stale. Nothing here rebuilds a panel,
	 * so the scroll position and any half-finished tap survive.
	 */
	private void patchNames(JSONObject st) {
		JSONObject self = st.optJSONObject("self");
		if (self == null) {
			return;
		}
		String host = self.optString("host", "");
		for (int i = 0; i < nameValues.size() && i < nameShown.size(); i++) {
			if (!nameShown.get(i).equals(host)) {
				nameValues.get(i).setText(host);
				nameShown.set(i, host);
			}
		}
	}

	/** Foreground poll. Cheap: one local read, no network. */
	private final Runnable poll = new Runnable() {
		@Override
		public void run() {
			if (polling) {
				return;
			}
			polling = true;
			new Thread(() -> {
				JSONObject st = null;
				try {
					st = Api.status();
				} catch (Exception ignored) {
					// offline or root gone; leave the screen as it is
				}
				final JSONObject fSt = st;
				ui.post(() -> {
					polling = false;
					if (fSt != null) {
						applyHeader(fSt);
						patchNames(fSt);
					}
				});
			}).start();
			ui.postDelayed(this, POLL_MS);
		}
	};

	private void startPolling() {
		ui.removeCallbacks(poll);
		ui.postDelayed(poll, POLL_MS);
	}

	private void stopPolling() {
		ui.removeCallbacks(poll);
	}

	private void render(JSONObject st) {
		boolean online = applyHeader(st);

		List<Api.Peer> ps = Api.peersFrom(st);
		peers.clear();
		for (Api.Peer p : ps) {
			peers.put(p.name, p.ip);
		}

		buildSend(ps, online);
		buildSettings(st, online);
		buildSystem(st, online);
	}

	// ------------------------------------------------------------------ home

	/**
	 * Home: what this device is, where files go, and the daemon switch. Plain
	 * grouped rows, the way a system settings screen reads.
	 */
	private void buildSend(List<Api.Peer> ps, boolean online) {
		pSend.removeAllViews();
		nameValues.clear();
		nameShown.clear();
		switches.clear();
		daemonSwitch = null;

		if (chosen.isEmpty() && !defaultTargets.isEmpty()) {
			for (String n : defaultTargets.split(",")) {
				if (!n.isEmpty() && peers.containsKey(n)) {
					chosen.add(n);
				}
			}
		}

		JSONObject self = status == null ? null : status.optJSONObject("self");

		// ── this device ──
		LinearLayout g = group(pSend, getString(R.string.this_device));
		if (self != null) {
			TextView nv = value(g, getString(R.string.device_name), self.optString("host", "\u2014"));
			nameValues.add(nv);
			nameShown.add(self.optString("host", "\u2014"));
		}
		JSONArray ips = self == null ? null : self.optJSONArray("tailscaleIPs");
		if (ips != null) {
			for (int i = 0; i < ips.length(); i++) {
				// The address is the thing people actually want, so the whole
				// row copies it and says so.
				final String ip = ips.optString(i);
				copyRow(g, i == 0 ? getString(R.string.ipv4) : getString(R.string.ipv6), ip);
			}
		}
		if (self != null) {
			String relay = self.optString("relay", "");
			value(g, getString(R.string.relay), relay.isEmpty() ? getString(R.string.direct) : relay);
		}

		// ── send targets ──
		LinearLayout t = group(pSend, getString(R.string.choose_devices));
		if (ps.isEmpty()) {
			plain(t, getString(R.string.no_peers_short));
		} else {
			for (Api.Peer p : ps) {
				line(t);
				t.addView(peerRow(p));
			}
		}

		// The one accent in the app, placed directly under what it acts on.
		sendBtn = new Button(this);
		sendBtn.setBackgroundResource(R.drawable.btn_primary);
		sendBtn.setTextColor(getColor(R.color.onAccent));
		sendBtn.setTextSize(16);
		sendBtn.setTypeface(null, android.graphics.Typeface.BOLD);
		sendBtn.setAllCaps(false);
		sendBtn.setSingleLine(true);
		sendBtn.setStateListAnimator(null);
		sendBtn.setMinWidth(0);
		sendBtn.setMinHeight(0);
		sendBtn.setOnClickListener(v -> onSendPressed());
		LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT, dp(54));
		slp.topMargin = dp(16);
		pSend.addView(sendBtn, slp);

		if (!pendingUris.isEmpty()) {
			note(pSend, getString(R.string.shared_n, pendingUris.size()));
		}

		// ── daemon ──
		LinearLayout d = group(pSend, getString(R.string.daemon));
		daemonSwitch = addSwitch(d, getString(R.string.service),
				getString(R.string.service_sub), online,
				v -> service(v.isChecked() ? "start" : "stop"));
		action(d, getString(R.string.restart), null, v -> service("restart"));
		action(d, getString(R.string.console), null, v -> open(CONSOLE_URL));

		updateSendButton(online);
	}

	private View peerRow(Api.Peer p) {
		LinearLayout row = row();

		CheckBox cb = new CheckBox(this);
		cb.setChecked(chosen.contains(p.name));
		cb.setButtonTintList(android.content.res.ColorStateList.valueOf(
				getColor(chosen.contains(p.name) ? R.color.text : R.color.hint)));
		row.addView(cb);

		LinearLayout col = col();
		TextView n = new TextView(this);
		n.setText(p.name);
		n.setTextColor(getColor(R.color.text));
		n.setTextSize(15);
		n.setSingleLine(true);
		col.addView(n);
		TextView meta = new TextView(this);
		meta.setText(p.ip + (p.os.isEmpty() ? "" : "  ·  " + p.os));
		meta.setTextColor(getColor(R.color.text2));
		meta.setTextSize(12);
		col.addView(meta);
		row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

		// A peer's address is worth keeping too, so it copies like one.
		row.addView(iconButton(R.drawable.ic_copy, R.color.hint, R.string.copy, v -> copy(p.ip)));

		// Send straight to this one device without touching the selection.
		row.addView(iconButton(R.drawable.ic_send, R.color.text, R.string.send, v -> {
			chosen.clear();
			chosen.add(p.name);
			saveTargets();
			launchPicker(java.util.Collections.singletonList(p.ip));
		}));

		// Tapping anywhere else on the row is the same as the checkbox.
		OnToggle peer = () -> {
			if (!chosen.remove(p.name)) {
				chosen.add(p.name);
			}
			saveTargets();
			refresh(false);
		};
		row.setOnClickListener(v -> peer.run());
		row.setClickable(true);
		cb.setOnClickListener(v -> peer.run());
		return row;
	}

	/** A small square icon that acts as a trailing row button. */
	private View iconButton(int drawable, int tint, int label, View.OnClickListener l) {
		ImageView v = new ImageView(this);
		v.setImageResource(drawable);
		v.setColorFilter(getColor(tint));
		v.setContentDescription(getString(label));
		v.setPadding(dp(13), dp(14), dp(13), dp(14));
		v.setClickable(true);
		v.setBackgroundResource(R.drawable.row_press);
		v.setOnClickListener(l);
		return v;
	}

	private interface OnToggle {
		void run();
	}

	private void copy(String ip) {
		android.content.ClipboardManager cm = (android.content.ClipboardManager)
				getSystemService(CLIPBOARD_SERVICE);
		if (cm != null) {
			cm.setPrimaryClip(android.content.ClipData.newPlainText("tailscale", ip));
			toast(getString(R.string.copied, ip));
		}
	}

	/** Send button: either deliver an incoming share, or open the file picker. */
	private void onSendPressed() {
		List<String> to = currentTargets();
		if (to.isEmpty()) {
			toast(getString(R.string.pick_a_device));
			return;
		}
		if (!pendingUris.isEmpty()) {
			dispatch(new ArrayList<>(pendingUris));
			pendingUris.clear();
			return;
		}
		launchPicker(to);
	}

	private void saveTargets() {
		StringBuilder b = new StringBuilder();
		for (String n : chosen) {
			if (b.length() > 0) {
				b.append(',');
			}
			b.append(n);
		}
		defaultTargets = b.toString();
		getPreferences(0).edit().putString("targets", defaultTargets).apply();
	}

	private List<String> currentTargets() {
		List<String> out = new ArrayList<>();
		for (String n : chosen) {
			String ip = peers.get(n);
			if (ip != null) {
				out.add(ip);
			}
		}
		return out;
	}

	private void updateSendButton(boolean online) {
		if (sendBtn == null) {
			return;
		}
		int n = chosen.size();
		sendBtn.setEnabled(online && n > 0);
		if (n == 1) {
			sendBtn.setText(getString(R.string.send_to, chosen.iterator().next()));
		} else if (n > 1) {
			sendBtn.setText(getString(R.string.send_to_n, n));
		} else {
			sendBtn.setText(getString(R.string.send_files));
		}
	}

	private void launchPicker(List<String> ips) {
		if (ips.isEmpty()) {
			toast(getString(R.string.pick_a_device));
			return;
		}
		targets = new ArrayList<>(ips);
		Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
		i.addCategory(Intent.CATEGORY_OPENABLE);
		i.setType("*/*");
		i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
		try {
			startActivityForResult(i, REQ_PICK);
		} catch (Exception e) {
			toast(getString(R.string.no_picker));
		}
	}

	@Override
	protected void onActivityResult(int req, int result, Intent data) {
		super.onActivityResult(req, result, data);
		if (req != REQ_PICK || result != RESULT_OK || data == null) {
			return;
		}
		final List<Uri> uris = new ArrayList<>();
		if (data.getClipData() != null) {
			for (int i = 0; i < data.getClipData().getItemCount(); i++) {
				uris.add(data.getClipData().getItemAt(i).getUri());
			}
		} else if (data.getData() != null) {
			uris.add(data.getData());
		}
		if (uris.isEmpty() || targets.isEmpty()) {
			return;
		}
		dispatch(uris);
	}

	/** every file -> every chosen peer, reporting partial failures. */
	private void dispatch(List<Uri> uris) {
		final List<String> to = new ArrayList<>(targets.isEmpty() ? currentTargets() : targets);
		toast(getString(R.string.sending));
		new Thread(() -> {
			java.io.File dir = new java.io.File(getCacheDir(), "send");
			List<java.io.File> files = new ArrayList<>();
			for (Uri u : uris) {
				try {
					files.add(Stage.copy(this, u, dir));
				} catch (Exception ignored) {
					// counted as a failure
				}
			}
			int ok = 0, failed = 0;
			StringBuilder problems = new StringBuilder();
			for (String peer : to) {
				for (java.io.File f : files) {
					try {
						Api.send(peer, f.getAbsolutePath());
						ok++;
					} catch (Root.Denied e) {
						throw e;
					} catch (Exception e) {
						failed++;
						if (problems.length() < 300) {
							problems.append(peer).append(": ").append(e.getMessage()).append('\n');
						}
					}
				}
			}
			final int fOk = ok, fFailed = failed;
			final String p = problems.toString().trim();
			ui.post(() -> {
				if (fFailed == 0) {
					toast(getString(R.string.sent_ok, fOk, to.size()));
				} else {
					alert()
							.setTitle(R.string.send_failed)
							.setMessage(getString(R.string.sent_partial, fOk, fFailed) + "\n\n" + p)
							.setPositiveButton(android.R.string.ok, null)
							.show();
				}
				// Re-read so the staged-items note clears once it is sent.
				refresh(false);
			});
		}).start();
	}

	// -------------------------------------------------------------- settings

	private void buildSettings(JSONObject st, boolean online) {
		pSettings.removeAllViews();
		switches.clear();
		Map<String, Boolean> flags = Api.flagsFrom(st);

		LinearLayout c1 = group(pSettings, getString(R.string.connectivity));
		addToggle(c1, "accept-dns", getString(R.string.t_accept_dns),
				getString(R.string.d_accept_dns), flags, online);
		addToggle(c1, "accept-routes", getString(R.string.t_accept_routes),
				getString(R.string.d_accept_routes), flags, online);
		addToggle(c1, "shields-up", getString(R.string.t_shields_up),
				getString(R.string.d_shields_up), flags, online);

		LinearLayout c2 = group(pSettings, getString(R.string.services));
		addToggle(c2, "run-ssh", getString(R.string.t_ssh), getString(R.string.d_ssh), flags, online);
		addToggle(c2, "run-webclient", getString(R.string.t_webclient),
				getString(R.string.d_webclient), flags, online);

		LinearLayout c3 = group(pSettings, getString(R.string.this_device));
		addToggle(c3, "advertise-exit-node", getString(R.string.t_adv_exit),
				getString(R.string.d_adv_exit), flags, online);
		addToggle(c3, "snat-subnet-routes", getString(R.string.t_snat),
				getString(R.string.d_snat), flags, online);

		// device name
		LinearLayout hr = new LinearLayout(this);
		hr.setOrientation(LinearLayout.HORIZONTAL);
		hr.setGravity(Gravity.CENTER_VERTICAL);
		EditText in = new EditText(this);
		in.setHint(getString(R.string.hostname_hint));
		JSONObject prefs = st.optJSONObject("prefs");
		in.setText(prefs == null ? "" : prefs.optString("hostname", ""));
		in.setSingleLine(true);
		in.setTextColor(getColor(R.color.text));
		in.setHintTextColor(getColor(R.color.hint));
		in.setTextSize(15);
		in.setPadding(dp(12), 0, dp(12), 0);
		in.setBackgroundResource(R.drawable.field);
		hr.addView(in, new LinearLayout.LayoutParams(0, dp(46), 1f));

		TextView apply = new TextView(this);
		apply.setText(R.string.apply);
		apply.setTextColor(getColor(online ? R.color.text : R.color.hint));
		apply.setTextSize(14);
		apply.setGravity(Gravity.CENTER);
		apply.setPadding(dp(18), dp(13), dp(6), dp(13));
		apply.setEnabled(online);
		apply.setOnClickListener(v -> applyPref("hostname",
				in.getText().toString().trim(), getString(R.string.hostname)));
		hr.addView(apply);
		c3.addView(hr);

		if (!online) {
			note(pSettings, getString(R.string.daemon_stopped_note));
		}
		note(pSettings, getString(R.string.footnote));
	}

	private void addToggle(LinearLayout g, String key, String label, String desc,
			Map<String, Boolean> flags, boolean online) {
		CheckBox sw = addSwitch(g, label, desc, flags.getOrDefault(key, false), v -> {
			if (online) {
				applyPref(key, String.valueOf(v.isChecked()), label);
			} else {
				v.setChecked(!v.isChecked());
			}
		});
		switches.put(key, sw);
	}

	private void applyPref(String key, String value, String label) {
		new Thread(() -> {
			try {
				final JSONObject st = Api.set(key, value);
				ui.post(() -> {
					toast(label + " " + (isTrue(value) ? "on" : "off"));
					status = st;
					buildSettings(st, "Running".equals(st.optString("backend", "")));
				});
			} catch (Root.Denied e) {
				ui.post(() -> toast(getString(R.string.need_root_short)));
			} catch (Exception e) {
				ui.post(() -> {
					toast(getString(R.string.failed, String.valueOf(e.getMessage())));
					// Put the switch back: the change did not stick.
					CheckBox sw = switches.get(key);
					if (sw != null) {
						sw.setOnCheckedChangeListener(null);
						sw.setChecked(!sw.isChecked());
					}
				});
			}
		}).start();
	}

	private static boolean isTrue(String v) {
		return "true".equals(v);
	}

	// ---------------------------------------------------------------- system

	private void buildSystem(JSONObject st, boolean online) {
		pSystem.removeAllViews();
		updateRow = null;
		updateTitle = null;
		updateSub = null;

		LinearLayout c = group(pSystem, getString(R.string.connectivity));
		value(c, getString(R.string.backend), st.optString("backend", "?"));
		JSONObject self = st.optJSONObject("self");
		if (self != null) {
			TextView nv = value(c, getString(R.string.device_name), self.optString("host", "\u2014"));
			nameValues.add(nv);
			nameShown.add(self.optString("host", "\u2014"));
		}
		JSONObject tn = st.optJSONObject("tailnet");
		String account = tn == null ? "" : tn.optString("name", "");

		// ── account ──
		LinearLayout a1 = group(pSystem, getString(R.string.account));
		value(a1, getString(R.string.tailnet), account.isEmpty()
				? getString(R.string.not_signed_in) : account);
		action(a1, getString(R.string.sign_in), null, v -> doLogin());
		if (!account.isEmpty()) {
			action(a1, getString(R.string.sign_out), null, v -> confirmSignOut());
		}

		LinearLayout u = group(pSystem, getString(R.string.software));
		value(u, getString(R.string.tailscaled_version), st.optString("version", "?"));
		value(u, getString(R.string.module_version), st.optString("module", "?"));

		updateRow = action(u, getString(R.string.check_updates), null, v -> updateNow());
		updateTitle = updateRow.findViewById(R.id.rowTitle);
		updateSub = updateRow.findViewById(R.id.rowSub);
		paintUpdateRow();

		action(u, getString(R.string.rollback), null, v -> rollback());
		action(u, getString(R.string.logs), null, v -> showLogs());

		LinearLayout a = group(pSystem, getString(R.string.about));
		action(a, getString(R.string.github_link), null, v -> open(GITHUB_URL));
		note(pSystem, getString(R.string.credits_body));
	}

	/** Offer the update by name once the feed tells us there is a newer build. */
	private void paintUpdateRow() {
		if (updateRow == null || updateTitle == null) {
			return;
		}
		boolean has = !latestVersion.isEmpty();
		updateTitle.setText(has ? getString(R.string.install_update, latestVersion)
				: getString(R.string.check_updates));
		if (updateSub != null) {
			updateSub.setVisibility(has ? View.VISIBLE : View.GONE);
			if (has) {
				updateSub.setText(getString(R.string.update_available, latestVersion));
			}
		}
	}

	private String join(JSONArray a) {
		StringBuilder b = new StringBuilder();
		for (int i = 0; i < a.length(); i++) {
			if (i > 0) {
				b.append(",  ");
			}
			b.append(a.optString(i));
		}
		return b.toString();
	}

	private void service(String action) {
		toast(getString(R.string.working));
		new Thread(() -> {
			try {
				final JSONObject st = Api.service(action);
				ui.post(() -> {
					toast(getString(R.string.done));
					status = st;
					latestVersion = "";
					render(st);
				});
			} catch (Exception e) {
				ui.post(() -> {
					toast(getString(R.string.failed, String.valueOf(e.getMessage())));
					refresh(false);
				});
			}
		}).start();
	}

	private void updateNow() {
		toast(getString(R.string.checking));
		new Thread(() -> {
			try {
				Api.update();
				latestVersion = "";
				ui.post(() -> {
					toast(getString(R.string.done));
					refresh(false);
				});
			} catch (Exception e) {
				ui.post(() -> toast(getString(R.string.failed, String.valueOf(e.getMessage()))));
			}
		}).start();
	}

	private void rollback() {
		alert()
				.setTitle(R.string.rollback)
				.setMessage(R.string.rollback_confirm)
				.setPositiveButton(android.R.string.ok, (d, w) -> {
					new Thread(() -> {
						try {
							ui.post(() -> toast(getString(R.string.done)));
							Api.rollback();
							ui.post(() -> refresh(false));
						} catch (Exception e) {
							ui.post(() -> toast(getString(R.string.failed,
									String.valueOf(e.getMessage()))));
						}
					}).start();
				})
				.setNegativeButton(android.R.string.cancel, null)
				.show();
	}

	/** Log viewer: monospace, colour-coded by severity, filterable, copyable. */
	private void showLogs() {
		new Thread(() -> {
			try {
				final String log = Api.logs(400);
				ui.post(() -> logDialog(log));
			} catch (Exception e) {
				ui.post(() -> toast(getString(R.string.failed, String.valueOf(e.getMessage()))));
			}
		}).start();
	}

	private void logDialog(String log) {
		LinearLayout root = new LinearLayout(this);
		root.setOrientation(LinearLayout.VERTICAL);

		// ── header ──
		LinearLayout head = new LinearLayout(this);
		head.setOrientation(LinearLayout.HORIZONTAL);
		head.setGravity(Gravity.CENTER_VERTICAL);
		head.setPadding(dp(20), dp(18), dp(12), dp(12));

		LinearLayout titles = new LinearLayout(this);
		titles.setOrientation(LinearLayout.VERTICAL);
		TextView t = new TextView(this);
		t.setText(R.string.logs);
		t.setTextColor(getColor(R.color.text));
		t.setTextSize(17);
		t.setTypeface(null, android.graphics.Typeface.BOLD);
		titles.addView(t);
		final TextView count = new TextView(this);
		count.setTextColor(getColor(R.color.hint));
		count.setTextSize(12);
		titles.addView(count);
		head.addView(titles, new LinearLayout.LayoutParams(0,
				ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

		final TextView filter = new TextView(this);
		filter.setText(R.string.filter_all);
		filter.setTextColor(getColor(R.color.text2));
		filter.setTextSize(13);
		filter.setGravity(Gravity.CENTER);
		filter.setPadding(dp(14), dp(8), dp(14), dp(8));
		head.addView(filter);
		root.addView(head);

		// ── body ──
		android.widget.ScrollView scroll = new android.widget.ScrollView(this);
		scroll.setPadding(dp(20), 0, dp(20), 0);
		scroll.setHorizontalScrollBarEnabled(false);
		TextView body = new TextView(this);
		body.setTextSize(11);
		body.setTextColor(getColor(R.color.text2));
		body.setTypeface(android.graphics.Typeface.MONOSPACE);
		body.setLineSpacing(dp(3), 1f);
		body.setPadding(0, dp(4), 0, dp(12));
		scroll.addView(body, new ViewGroup.LayoutParams(
				ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
		root.addView(scroll, new LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT, dp(380)));

		// ── footer ──
		LinearLayout foot = new LinearLayout(this);
		foot.setOrientation(LinearLayout.HORIZONTAL);
		foot.setPadding(dp(12), dp(4), dp(12), dp(12));
		TextView copy = footButton(R.string.copy);
		TextView reload = footButton(R.string.refresh);
		TextView close = footButton(R.string.close);
		foot.addView(copy, new LinearLayout.LayoutParams(0, dp(44), 1));
		foot.addView(reload, new LinearLayout.LayoutParams(0, dp(44), 1));
		foot.addView(close, new LinearLayout.LayoutParams(0, dp(44), 1));
		root.addView(foot);

		final AlertDialog d = alert().setView(root).create();
		final String[] lines = log.split("\n");
		final boolean[] problemsOnly = {false};

		Runnable paint = () -> {
			CharSequence out = paintLog(lines, problemsOnly[0]);
			body.setText(out);
			int shown = problemsOnly[0] ? countProblems(lines) : lines.length;
			count.setText(getString(R.string.log_lines, shown));
			filter.setText(problemsOnly[0] ? R.string.filter_all : R.string.filter_problems);
			scroll.post(() -> scroll.fullScroll(View.FOCUS_UP));
		};

		filter.setOnClickListener(v -> {
			problemsOnly[0] = !problemsOnly[0];
			paint.run();
		});
		copy.setOnClickListener(v -> {
			android.content.ClipboardManager cm = (android.content.ClipboardManager)
					getSystemService(CLIPBOARD_SERVICE);
			if (cm != null) {
				cm.setPrimaryClip(android.content.ClipData.newPlainText("tailscale",
						String.join("\n", lines)));
				toast(getString(R.string.log_copied));
			}
		});
		reload.setOnClickListener(v -> {
			d.dismiss();
			showLogs();
		});
		close.setOnClickListener(v -> d.dismiss());

		paint.run();
		d.show();
	}

	private TextView footButton(int label) {
		TextView b = new TextView(this);
		b.setText(label);
		b.setTextColor(getColor(R.color.text));
		b.setTextSize(14);
		b.setGravity(Gravity.CENTER);
		b.setClickable(true);
		b.setBackgroundResource(R.drawable.row_press);
		return b;
	}

	/** Tailscale logs as "YYYY/MM/DD HH:MM:SS [v1] thing: detail". */
	private CharSequence paintLog(String[] lines, boolean problemsOnly) {
		android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder();
		boolean any = false;
		for (String line : lines) {
			if (line.trim().isEmpty()) {
				continue;
			}
			boolean problem = isProblem(line);
			if (problemsOnly && !problem) {
				continue;
			}
			any = true;

			int split = 19;
			if (line.length() > split && isTimestamp(line.substring(0, split))) {
				sb.append(line, 0, split);
				sb.setSpan(new android.text.style.ForegroundColorSpan(
						getColor(R.color.hint)), sb.length() - split, sb.length(),
						android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
				sb.append(line, split, line.length());
			} else {
				sb.append(line);
			}
			int start = sb.length() - line.length();
			sb.setSpan(new android.text.style.ForegroundColorSpan(
					getColor(problem ? R.color.err : R.color.text2)), start, sb.length(),
					android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
			sb.append('\n');
		}
		if (!any) {
			sb.append(getString(R.string.no_logs));
		}
		return sb;
	}

	private int countProblems(String[] lines) {
		int n = 0;
		for (String l : lines) {
			if (!l.trim().isEmpty() && isProblem(l)) {
				n++;
			}
		}
		return n;
	}

	private static boolean isProblem(String line) {
		String l = line.toLowerCase(java.util.Locale.ROOT);
		return l.contains("error") || l.contains("fatal") || l.contains("panic")
				|| l.contains("warn") || l.contains("failed") || l.contains("denied");
	}

	private static boolean isTimestamp(String s) {
		if (s.length() < 19) {
			return false;
		}
		return Character.isDigit(s.charAt(0)) && s.charAt(4) == '/' && s.charAt(7) == '/'
				&& s.charAt(10) == ' ' && s.charAt(13) == ':' && s.charAt(16) == ':';
	}

	/**
	 * Sign in hands off to the browser: Tailscale has no headless auth flow, and
	 * this is the only way to authorise a device.
	 */
	private void doLogin() {
		toast(getString(R.string.working));
		new Thread(() -> {
			try {
				final JSONObject r = Api.login();
				ui.post(() -> {
					if (r.optBoolean("alreadyLoggedIn", false)) {
						toast(getString(R.string.already_signed_in));
						refresh(false);
						return;
					}
					String url = r.optString("url", "");
					if (url.isEmpty()) {
						toast(getString(R.string.no_login_url));
						return;
					}
					open(url);
					toast(getString(R.string.sign_in_opened));
				});
			} catch (Exception e) {
				ui.post(() -> toast(getString(R.string.failed, String.valueOf(e.getMessage()))));
			}
		}).start();
	}

	private void confirmSignOut() {
		alert()
				.setTitle(R.string.sign_out)
				.setMessage(R.string.sign_out_confirm)
				.setPositiveButton(R.string.sign_out, (d, w) -> {
					new Thread(() -> {
						try {
							Api.logout();
							ui.post(() -> {
								toast(getString(R.string.signed_out));
								refresh(false);
							});
						} catch (Exception e) {
							ui.post(() -> toast(getString(R.string.failed,
									String.valueOf(e.getMessage()))));
						}
					}).start();
				})
				.setNegativeButton(android.R.string.cancel, null)
				.show();
	}

	private void openAppSettings() {
		try {
			startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
					Uri.fromParts("package", getPackageName(), null)));
		} catch (Exception ignored) {
			// nothing to open
		}
	}

	private void open(String url) {
		try {
			startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
		} catch (Exception e) {
			toast(getString(R.string.no_browser));
		}
	}

	// --------------------------------------------------------------- widgets

	/**
	 * A grouped list surface: a heading, then a rounded box of contiguous rows
	 * with hairlines between them. This is the only shape on the page.
	 */
	private LinearLayout group(LinearLayout parent, String heading) {
		if (heading != null) {
			TextView t = new TextView(this);
			t.setText(heading);
			t.setTextColor(getColor(R.color.text2));
			t.setTextSize(12);
			t.setTypeface(null, android.graphics.Typeface.BOLD);
			t.setAllCaps(true);
			t.setLetterSpacing(0.08f);
			t.setPadding(dp(4), dp(20), 0, dp(8));
			parent.addView(t);
		} else {
			LinearLayout.LayoutParams s = new LinearLayout.LayoutParams(
					ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
			s.topMargin = dp(16);
			parent.addView(new View(this), s);
		}

		LinearLayout g = new LinearLayout(this);
		g.setOrientation(LinearLayout.VERTICAL);
		g.setBackgroundResource(R.drawable.group);
		LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		parent.addView(g, lp);
		return g;
	}

	/** Hairline between rows in a group; never above the first one. */
	private void line(LinearLayout g) {
		if (g.getChildCount() == 0) {
			return;
		}
		View v = new View(this);
		v.setBackgroundResource(R.drawable.divider);
		LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT, 1);
		lp.leftMargin = dp(16);
		lp.rightMargin = dp(16);
		g.addView(v, lp);
	}

	/** A non-interactive row: label left, value right. */
	private TextView value(LinearLayout g, String label, String v) {
		line(g);
		LinearLayout row = row();
		TextView l = new TextView(this);
		l.setText(label);
		l.setTextColor(getColor(R.color.text));
		l.setTextSize(15);
		l.setSingleLine(true);
		row.addView(l, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

		TextView val = new TextView(this);
		val.setText(v);
		val.setTextColor(getColor(R.color.text2));
		val.setTextSize(14);
		val.setMaxLines(2);
		val.setEllipsize(android.text.TextUtils.TruncateAt.END);
		LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
		vlp.leftMargin = dp(14);
		row.addView(val, vlp);
		g.addView(row);
		return val;
	}

	/** A value row that copies when tapped. */
	private void copyRow(LinearLayout g, String label, String v) {
		line(g);
		LinearLayout row = row();
		row.setBackgroundResource(R.drawable.row_press);
		row.setClickable(true);
		row.setOnClickListener(x -> copy(v));

		TextView l = new TextView(this);
		l.setText(label);
		l.setTextColor(getColor(R.color.text));
		l.setTextSize(15);
		l.setSingleLine(true);
		row.addView(l, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

		TextView val = new TextView(this);
		val.setText(v);
		val.setTextColor(getColor(R.color.text2));
		val.setTextSize(14);
		val.setSingleLine(true);
		val.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
		row.addView(val);

		ImageView cp = new ImageView(this);
		cp.setImageResource(R.drawable.ic_copy);
		cp.setColorFilter(getColor(R.color.hint));
		LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(dp(15), dp(15));
		clp.leftMargin = dp(12);
		row.addView(cp, clp);
		g.addView(row);
	}

	/** A tappable row: title, optional sub-label, optional trailing hint. */
	private View action(LinearLayout g, String title, String sub, View.OnClickListener l) {
		line(g);
		LinearLayout row = row();
		row.setBackgroundResource(R.drawable.row_press);
		row.setClickable(true);

		LinearLayout col = col();
		TextView t = new TextView(this);
		t.setId(R.id.rowTitle);
		t.setText(title);
		t.setTextColor(getColor(R.color.text));
		t.setTextSize(15);
		t.setSingleLine(true);
		t.setEllipsize(android.text.TextUtils.TruncateAt.END);
		col.addView(t);

		if (sub != null && !sub.isEmpty()) {
			TextView s = new TextView(this);
			s.setId(R.id.rowSub);
			s.setText(sub);
			s.setTextColor(getColor(R.color.text2));
			s.setTextSize(12);
			col.addView(s);
		}

		row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

		if (l != null) {
			ImageView chev = new ImageView(this);
			chev.setImageResource(R.drawable.ic_chevron);
			chev.setColorFilter(getColor(R.color.hint));
			LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(dp(16), dp(16));
			clp.leftMargin = dp(10);
			row.addView(chev, clp);
			row.setOnClickListener(l);
		}
		g.addView(row);
		return row;
	}

	/** A row with a trailing switch. Returns it so a failed write can be undone. */
	private CheckBox addSwitch(LinearLayout g, String title, String sub, boolean on,
			OnChecked l) {
		line(g);
		LinearLayout row = row();
		LinearLayout col = col();

		TextView t = new TextView(this);
		t.setId(R.id.rowTitle);
		t.setText(title);
		t.setTextColor(getColor(R.color.text));
		t.setTextSize(15);
		col.addView(t);

		if (sub != null && !sub.isEmpty()) {
			TextView s = new TextView(this);
			s.setId(R.id.rowSub);
			s.setText(sub);
			s.setTextColor(getColor(R.color.text2));
			s.setTextSize(12);
			col.addView(s);
		}
		row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

		CheckBox sw = new CheckBox(this);
		sw.setButtonTintList(android.content.res.ColorStateList.valueOf(
				getColor(on ? R.color.text : R.color.hint)));
		sw.setChecked(on);
		row.addView(sw);

		// Set the value before wiring the listener so painting the screen can
		// never be mistaken for the user flipping it.
		sw.setOnCheckedChangeListener((CompoundButton b, boolean checked) -> {
			b.setButtonTintList(android.content.res.ColorStateList.valueOf(
					getColor(checked ? R.color.text : R.color.hint)));
			if (l != null) {
				l.on(b);
			}
		});
		if (l != null) {
			// Tapping anywhere on the row flips it, like a system switch, so
			// the label is a target too and not just a caption.
			row.setClickable(true);
			row.setOnClickListener(v -> sw.performClick());
		}
		g.addView(row);
		return sw;
	}

	private void plain(LinearLayout g, String text) {
		TextView t = new TextView(this);
		t.setText(text);
		t.setTextColor(getColor(R.color.text2));
		t.setTextSize(14);
		t.setPadding(dp(16), dp(16), dp(16), dp(16));
		g.addView(t);
	}

	private LinearLayout row() {
		LinearLayout row = new LinearLayout(this);
		row.setOrientation(LinearLayout.HORIZONTAL);
		row.setGravity(Gravity.CENTER_VERTICAL);
		row.setPadding(dp(16), dp(14), dp(16), dp(14));
		return row;
	}

	private LinearLayout col() {
		LinearLayout c = new LinearLayout(this);
		c.setOrientation(LinearLayout.VERTICAL);
		return c;
	}

	private interface OnChecked {
		void on(CompoundButton b);
	}

	private void note(LinearLayout parent, String text) {
		TextView t = new TextView(this);
		t.setText(text);
		t.setTextColor(getColor(R.color.hint));
		t.setTextSize(12);
		t.setLineSpacing(dp(4), 1f);
		t.setPadding(dp(4), dp(16), dp(4), 0);
		parent.addView(t);
	}

	private void toast(String msg) {
		Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
	}

	private int dp(int v) {
		return (int) (v * getResources().getDisplayMetrics().density);
	}
}