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

	/** Root works, but the module could not be reached. Distinct from no root. */
	static final class ApiBroken extends Exception {
		final boolean modulePresent;

		ApiBroken(String msg, boolean modulePresent) {
			super(msg);
			this.modulePresent = modulePresent;
		}
	}

	private static JSONObject call(String... args) throws Exception {
		StringBuilder cmd = new StringBuilder(PATH);
		for (String a : args) {
			// The API re-validates everything; quote anyway so a filename with a
			// space cannot split into two arguments here.
			cmd.append(" '").append(a.replace("'", "'\\''")).append('\'');
		}
		String out;
		try {
			out = Root.sh(cmd.toString(), 240_000).trim();
		} catch (Root.Denied d) {
			// Genuinely no root. The caller shows the permission screen.
			throw d;
		} catch (Exception e) {
			throw new ApiBroken("root is available but the module is not installed or "
					+ "is broken: " + e, false);
		}
		int i = out.indexOf('{');
		if (i < 0) {
			// The command ran, so root works. Something else is wrong, and
			// saying "grant root permission" here sends the user to a setting
			// that cannot possibly fix it.
			String why = out.isEmpty() ? "(no output)" : out;
			if (why.length() > 200) {
				why = why.substring(0, 200) + "…";
			}
			throw new ApiBroken("the module is installed but not answering: " + why, true);
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
		/** Reachability. Carried rather than filtered: the list should show a
		 *  device that is known but down, so it is obvious it is down instead
		 *  of appearing to have been removed. */
		final boolean online;
		/** When this peer was last seen, as the daemon reports it. */
		final String lastSeen;
		/** Offers itself as an exit node. */
		final boolean exitNodeOption;

		Peer(String name, String ip, String os, boolean exitNode, boolean online,
				String lastSeen, boolean exitNodeOption) {
			this.name = name;
			this.ip = ip;
			this.os = os;
			this.exitNode = exitNode;
			this.online = online;
			this.lastSeen = lastSeen;
			this.exitNodeOption = exitNodeOption;
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
					p.optBoolean("exitNode", false),
					p.optBoolean("online", false),
					p.optString("lastSeen", ""),
					p.optBoolean("exitNodeOption", false)));
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

	/** Round-trip time and path to a peer. */
	static JSONObject ping(String ip) throws Exception {
		return call("ping", ip);
	}

	/** What the Taildrop receiver collected while the app was away. */
	static JSONObject received() throws Exception {
		return call("received");
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

	/**
	 * Pull anything waiting in the Taildrop inbox into Downloads/Tailscale.
	 *
	 * Without this the daemon accepts inbound files and nothing ever shows them:
	 * they sit in a root-only inbox the user cannot reach. @return the names
	 * that were exported.
	 */
}