// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026 paras-moharle

package com.paras.tailbridge;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/** Copies a shared/picked content:// URI into a cache file we can send. */
final class Stage {

	private Stage() {}

	static File copy(Context ctx, Uri uri, File dir) throws Exception {
		if (!dir.exists() && !dir.mkdirs()) {
			throw new IllegalStateException("cannot create " + dir);
		}
		File out = unique(dir, displayName(ctx, uri));

		ContentResolver cr = ctx.getContentResolver();
		try (InputStream in = cr.openInputStream(uri)) {
			if (in == null) {
				throw new IllegalStateException("cannot open " + uri);
			}
			try (OutputStream os = new FileOutputStream(out)) {
				byte[] buf = new byte[64 * 1024];
				int n;
				while ((n = in.read(buf)) > 0) {
					os.write(buf, 0, n);
				}
			}
		}
		return out;
	}

	private static String displayName(Context ctx, Uri uri) {
		String name = null;
		try (android.database.Cursor c =
					 ctx.getContentResolver().query(uri, null, null, null, null)) {
			if (c != null && c.moveToFirst()) {
				int i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
				if (i >= 0) {
					name = c.getString(i);
				}
			}
		} catch (Exception ignored) {
			// fall through to the path segment
		}
		if (name == null || name.isEmpty()) {
			name = uri.getLastPathSegment();
		}
		if (name == null || name.isEmpty()) {
			name = "shared-" + System.currentTimeMillis();
		}
		return name.replaceAll("[/\\\\:*?\"<>|\\r\\n]", "_");
	}

	private static File unique(File dir, String name) {
		File f = new File(dir, name);
		if (f.exists()) {
			int dot = name.lastIndexOf('.');
			String base = dot > 0 ? name.substring(0, dot) : name;
			String ext = dot > 0 ? name.substring(dot) : "";
			f = new File(dir, base + "-" + System.currentTimeMillis() + ext);
		}
		return f;
	}
}