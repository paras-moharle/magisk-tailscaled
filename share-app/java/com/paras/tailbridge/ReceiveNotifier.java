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

	/** /sdcard/Download/Tailscale as a SAF document URI.
	 *
	 * A raw file:// URI cannot be handed to another app on Android 7+ (that is
	 * what FileUriExposedException is about), and this app has no FileProvider.
	 * The storage documents provider needs no permission and no manifest
	 * plumbing, which is why the button opens the folder rather than a file.
	 */
	private static Uri folderUri(String dir) {
		if (dir == null || !dir.startsWith("/sdcard/")) {
			dir = "/sdcard/Download/Tailscale";
		}
		String rel = dir.substring("/sdcard/".length());
		return Uri.parse("content://com.android.externalstorage.documents/root/primary/"
				+ Uri.encode(rel, "/"));
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

		// The action opens the received folder in the system file manager.
		Intent browse = new Intent(Intent.ACTION_VIEW, folderUri(dir))
				.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
		PendingIntent browsePi = PendingIntent.getActivity(ctx, 1, browse,
				PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

		String title = ctx.getResources().getQuantityString(R.plurals.taildrop_title, count, count);

		Notification.Builder b = new Notification.Builder(ctx, CHANNEL)
				.setSmallIcon(R.drawable.ic_send)
				.setContentTitle(title)
				.setContentText(names)
				.setStyle(new Notification.BigTextStyle().bigText(names + "\n\n" + dir))
				.setAutoCancel(true)
				.setContentIntent(openPi)
				.addAction(new Notification.Action.Builder(
						null, ctx.getString(R.string.taildrop_open_folder), browsePi).build());

		// Reuse one tag: a second delivery replaces the first rather than
		// stacking notifications nobody is going to clear.
		nm.notify(TAG, 1, b.build());
	}
}