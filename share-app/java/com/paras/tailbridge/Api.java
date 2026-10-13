// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026 paras-moharle

package com.paras.tailbridge;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Client for the module's tailscaled.api. Every call runs as root. */
final class Api {

	private static final String PATH = "/data/adb/tailscale/scripts/tailscaled.api";

	private Api() {}

	private static JSONObject call(String... args) throws Exception {
		StringBuilder cmd = new StringBuilder(PATH);
		for (String a : args) {
			// The API re-validates everything; quote anyway so a filename with a
			// space cannot split into two arguments here.
			cmd.append(" '").append(a.replace("'", "'\\''")).append('\'');
		}
		String out = Root.sh(cmd.toString(), 240_000).trim();
		int i = out.indexOf('{');
		if (i < 0) {
			throw new IllegalStateException("no JSON from api: " + out);
		}
		JSONObject o = new JSONObject(out.substring(i));
		if (!o.optBoolean("ok", false)) {
			throw new IllegalStateException(o.optString("error", "unknown error"));
		}
		return o;
	}

	static final class Peer {
		final String name;
		final String ip;
		final String os;
		final boolean exitNode;

		Peer(String name, String ip, String os, boolean exitNode) {
			this.name = name;
			this.ip = ip;
			this.os = os;
			this.exitNode = exitNode;
		}

		@Override
		public String toString() {
			return name + "   " + ip;
		}
	}

	/**
	 * One call for the whole dashboard.
	 *
	 * Peers ride along in the same document, so the UI does not need a second
	 * root round-trip, and `status` is purely local (no network), which keeps
	 * the screen instant.
	 */
	static JSONObject status() throws Exception {
		return call("status");
	}

	static List<Peer> peersFrom(JSONObject st) {
		List<Peer> out = new ArrayList<>();
		JSONArray arr = st.optJSONArray("peers");
		if (arr == null) {
			return out;
		}
		for (int i = 0; i < arr.length(); i++) {
			JSONObject p = arr.optJSONObject(i);
			if (p == null) {
				continue;
			}
			JSONArray ips = p.optJSONArray("ips");
			String ip = ips != null && ips.length() > 0 ? ips.optString(0) : "";
			out.add(new Peer(
					p.optString("host", ip.isEmpty() ? "peer" : ip),
					ip,
					p.optString("os", ""),
					p.optBoolean("exitNode", false)));
		}
		return out;
	}

	/** Latest published version. Slow: touches the network. */
	static String updateCheck() throws Exception {
		JSONObject u = call("update-check");
		return u.optString("latest", "");
	}

	static boolean send(String peerIp, String path) throws Exception {
		call("send", peerIp, path);
		return true;
	}

	/** Apply a preference. Returns the refreshed status document. */
	static JSONObject set(String key, String value) throws Exception {
		return call("set", key, value);
	}

	/** start | stop | restart | status */
	static JSONObject service(String action) throws Exception {
		return call("service", action);
	}

	static JSONObject update() throws Exception {
		return call("update");
	}

	static JSONObject rollback() throws Exception {
		JSONObject o = call("rollback");
		return o;
	}

	static String logs(int lines) throws Exception {
		JSONObject o = call("logs", String.valueOf(lines));
		return o.optString("log", "");
	}

	/** @return {url, alreadyLoggedIn} */
	static JSONObject login() throws Exception {
		return call("login");
	}

	static JSONObject logout() throws Exception {
		return call("logout");
	}

	/** Prefs keyed the way the UI needs them. */
	static Map<String, Boolean> flagsFrom(JSONObject st) {
		JSONObject p = st.optJSONObject("prefs");
		Map<String, Boolean> m = new LinkedHashMap<>();
		if (p == null) {
			return m;
		}
		m.put("accept-dns", p.optBoolean("acceptDNS", false));
		m.put("accept-routes", p.optBoolean("routeAll", false));
		m.put("shields-up", p.optBoolean("shieldsUp", false));
		m.put("run-ssh", p.optBoolean("runSSH", false));
		m.put("run-webclient", p.optBoolean("runWebClient", false));
		m.put("advertise-exit-node", p.optBoolean("advertiseExitNode", false));
		m.put("snat-subnet-routes", p.optBoolean("snatSubnetRoutes", false));
		return m;
	}

	/**
	 * Copy a received file into this app's own cache so it can actually be read.
	 *
	 * Files under /sdcard are owned by the media_rw group and are unreadable to
	 * an ordinary app, even though a file manager can open them. Root can copy
	 * them somewhere this app owns, which is what the FileProvider then serves.
	 */
	static File materialize(android.content.Context ctx, String name, String srcDir)
			throws Exception {
		File cacheDir = new File(ctx.getCacheDir(), "open");
		if (!cacheDir.exists() && !cacheDir.mkdirs()) {
			throw new IllegalStateException("cannot create " + cacheDir);
		}
		String safe = name.replaceAll("[/\\\\]", "_").replace("'", "'\\''");
		File dest = new File(cacheDir, safe);

		String cmd = "cp -f '" + srcDir + "/" + safe + "' '"
				+ dest.getAbsolutePath() + "' && chmod 0644 '" + dest.getAbsolutePath() + "'";
		Root.sh(cmd, 60_000);

		if (!dest.exists() || dest.length() == 0) {
			throw new IllegalStateException("copy produced nothing for " + name);
		}
		return dest;
	}

	/** Pull anything waiting in the Taildrop inbox into Downloads/Tailscale. */
	static void receiveAndExport() throws Exception {
		call("receive", "0");
		Root.sh("mkdir -p '/sdcard/Download/Tailscale'; "
				+ "for f in /data/adb/tailscale/inbox/*; do "
				+ "  [ -f \"$f\" ] || continue; "
				+ "  mv -f \"$f\" '/sdcard/Download/Tailscale/' 2>/dev/null; "
				+ "done; chmod -R 0755 '/sdcard/Download/Tailscale' 2>/dev/null", 120_000);
	}
}