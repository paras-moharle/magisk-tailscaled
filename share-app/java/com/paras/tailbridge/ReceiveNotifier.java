// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026 paras-moharle

package com.paras.tailbridge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.os.Build;

/**
 * Posts the Taildrop arrival notification.
 *
 * <p>The receiver script runs as root and cannot post a useful notification
 * itself: {@code cmd notification post} returns success but only creates a
 * transient shell channel with no actions, so the alert vanishes and offers
 * nothing to tap. Action buttons need a real Notification posted by a real
 * app, so the script detects the delivery and hands the news here over an
 * explicit broadcast.
 */
public class ReceiveNotifier extends BroadcastReceiver {

	private static final String CHANNEL = "taildrop";
	private static final String TAG = "taildrop";

	static final String ACTION = "com.paras.tailbridge.RECEIVED";
	static final String EXTRA_NAMES = "names";
	static final String EXTRA_COUNT = "count";
	static final String EXTRA_DIR = "dir";
	static final String EXTRA_FIRST = "first";

	/** A SAF document URI for a path under /sdcard.
	 *
	 * <p>A raw file:// URI cannot be handed to another app on Android 7+ (that
	 * is what FileUriExposedException is about) and this app has no
	 * FileProvider. The storage documents provider needs neither: it is a
	 * system provider, so no manifest entry and no permission, and the URI it
	 * builds is one the Files app already knows how to open.
	 *
	 * <p>Hand-rolling the string is what broke this first time. The correct
	 * form is .../document/<primary%3ADownload%2FTailscale>, which
	 * DocumentsContract encodes for us; the hand-written variant had no
	 * /document/ segment and the button silently did nothing.
	 */
	private static Uri docUri(String path) {
		if (path == null) {
			return null;
		}
		String rel = path.startsWith("/sdcard/") ? path.substring("/sdcard/".length()) : null;
		if (rel == null || rel.isEmpty()) {
			return null;
		}
		return DocumentsContract.buildDocumentUri("com.android.externalstorage.documents",
				"primary:" + rel);
	}

	/** Pin an intent to the system file manager, to avoid a chooser.
	 *
	 * <p>A directory intent with {@code vnd.android.document/directory} resolves
	 * on a real phone to an APK installer, a code editor <i>and</i> the file
	 * manager, because those apps claim the type broadly. Tapping the button
	 * then opens a three-way dialog unrelated to opening a folder, which reads
	 * as a dead button. Where a file manager can be identified, target it
	 * directly; otherwise leave the intent implicit and let Android ask.
	 */
	private static void pinToFileManager(Context ctx, Intent intent) {
		try {
			android.content.pm.PackageManager pm = ctx.getPackageManager();
			java.util.List<android.content.pm.ResolveInfo> hits =
					pm.queryIntentActivities(intent, 0);
			for (android.content.pm.ResolveInfo ri : hits) {
				String pkg = ri.activityInfo.packageName;
				if (pkg != null && pkg.contains("documentsui")) {
					intent.setPackage(pkg);
					return;
				}
			}
			// No recognisable file manager. Wrap in an explicit chooser so the
			// tap always produces something the user can see. An unresolved
			// implicit intent is what makes a notification button look dead.
			intent.setPackage(null);
		} catch (Exception ignored) {
			// Best effort only.
		}
	}

	/** Guess a MIME type from the extension. */
	private static String mimeOf(String name) {
		if (name == null) {
			return "*/*";
		}
		int dot = name.lastIndexOf('.');
		String ext = dot > 0 ? name.substring(dot + 1).toLowerCase(java.util.Locale.ROOT) : "";
		switch (ext) {
			case "txt": case "log": case "md": return "text/plain";
			case "json": return "application/json";
			case "png": return "image/png";
			case "jpg": case "jpeg": return "image/jpeg";
			case "gif": return "image/gif";
			case "webp": return "image/webp";
			case "pdf": return "application/pdf";
			case "zip": return "application/zip";
			case "mp4": return "video/mp4";
			case "mp3": return "audio/mpeg";
			case "apk": return "application/vnd.android.package-archive";
			default: return "*/*";
		}
	}

	@Override
	public void onReceive(Context ctx, Intent intent) {
		if (intent == null || !ACTION.equals(intent.getAction())) {
			return;
		}
		int count = intent.getIntExtra(EXTRA_COUNT, 0);
		if (count <= 0) {
			return;
		}
		String names = intent.getStringExtra(EXTRA_NAMES);
		String dir = intent.getStringExtra(EXTRA_DIR);
		if (names == null || names.trim().isEmpty()) {
			names = "Taildrop";
		}

		NotificationManager nm =
				(NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
		if (nm == null) {
			return;
		}

		// A channel is mandatory from Android 8 and this app is minSdk 26, so
		// there is no pre-O branch to carry.
		NotificationChannel ch = new NotificationChannel(CHANNEL,
				ctx.getString(R.string.taildrop_channel), NotificationManager.IMPORTANCE_HIGH);
		ch.setDescription(ctx.getString(R.string.taildrop_channel_desc));
		ch.enableVibration(true);
		nm.createNotificationChannel(ch);

		// Tapping the notification opens the app.
		Intent open = new Intent(ctx, MainActivity.class)
				.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
		PendingIntent openPi = PendingIntent.getActivity(ctx, 0, open,
				PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

		java.util.Map<String, Notification.Action> actions = new java.util.LinkedHashMap<>();

		// "Open folder" hands the directory to the system file manager.
		// The directory MIME type matters: without it the intent matches every
		// app that handles content:// loosely (a browser, a chat app) instead
		// of the one that can actually show a folder.
		Uri dirUri = docUri(dir);
		if (dirUri != null) {
			Intent browse = new Intent(Intent.ACTION_VIEW, dirUri)
					.setDataAndType(dirUri, DocumentsContract.Document.MIME_TYPE_DIR)
					.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
			pinToFileManager(ctx, browse);
			PendingIntent browsePi = PendingIntent.getActivity(ctx, 1, browse,
					PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
			actions.put("folder", new Notification.Action.Builder(null,
					ctx.getString(R.string.taildrop_open_folder), browsePi).build());
		}

		// "Open" the first file itself, when we know which one it was. Only a
		// single file: an action per file would push the notification past the
		// three-action limit and Android would drop the rest.
		String first = intent.getStringExtra(EXTRA_FIRST);
		if (first != null && !first.trim().isEmpty()) {
			Uri fileUri = docUri(first);
			if (fileUri != null) {
				String base = first.substring(first.lastIndexOf('/') + 1);
				Intent fileTarget = new Intent(Intent.ACTION_VIEW, fileUri)
						.setDataAndType(fileUri, mimeOf(base))
						.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
								| Intent.FLAG_ACTIVITY_NEW_TASK);
				// Wrapped so a tap always surfaces a chooser. An implicit
				// intent with no handler throws at tap time and the platform
				// swallows it, which is indistinguishable from a dead button.
				Intent view = Intent.createChooser(fileTarget,
						ctx.getString(R.string.taildrop_open_with));
				PendingIntent viewPi = PendingIntent.getActivity(ctx, 2, view,
						PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
				actions.put("open", new Notification.Action.Builder(null,
						ctx.getString(R.string.taildrop_open_file), viewPi).build());
			}
		}

		String title = ctx.getResources().getQuantityString(R.plurals.taildrop_title, count, count);

		Notification.Builder b = new Notification.Builder(ctx, CHANNEL)
				.setSmallIcon(R.drawable.ic_send)
				.setContentTitle(title)
				.setContentText(names)
				.setStyle(new Notification.BigTextStyle().bigText(names + "\n\n" + dir))
				.setAutoCancel(true)
				.setContentIntent(openPi);
		// "Open" before "Open folder": the file is the reason for the alert.
		if (actions.containsKey("open")) {
			b.addAction(actions.get("open"));
		}
		if (actions.containsKey("folder")) {
			b.addAction(actions.get("folder"));
		}

		// Reuse one tag: a second delivery replaces the first rather than
		// stacking notifications nobody is going to clear.
		nm.notify(TAG, 1, b.build());
	}
}