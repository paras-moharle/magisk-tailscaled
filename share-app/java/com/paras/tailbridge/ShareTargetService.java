package com.paras.tailbridge;

import android.content.ComponentName;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.drawable.Icon;
import android.service.chooser.ChooserTargetInfo;
import android.service.chooser.ChooserTargetService;

import java.util.List;

/**
 * Puts TailBridge in the share sheet's own row, as a destination the sheet
 * renders rather than one it merely lists.
 *
 * <p>Without this, choosing TailBridge from another app starts an activity:
 * the system animates it to the foreground, the source app is pushed into the
 * background, and even a perfectly transparent window still feels like being
 * taken somewhere. The sheet is still on screen either way, and closing the
 * window reveals the source app having been disturbed.
 *
 * <p>As a direct-share target the sheet keeps ownership of the screen. It calls
 * back here with the shared intents, this hands them to MainActivity, and the
 * device picker is presented over the app the user never left.
 *
 * <p>Choosers do require the service to be bound, hence the real binder rather
 * than the null an unbound stub would hand back.
 */
public class ShareTargetService extends ChooserTargetService {

	private final android.os.IBinder binder =
			new android.os.Binder();

	@Override
	public void onPrepareChooserTarget(ChooserTargetInfo.Builder builder) {
		builder.setTitle(getString(R.string.app_name));
		builder.setSubtitle(getString(R.string.share_target_subtitle));
		builder.setIcon(Icon.createWithResource(this, R.drawable.ic_launcher));
	}

	@Override
	public List<ChooserTargetInfo> onGetChooserTargets(ComponentName target,
			IntentFilter targetFilter) {
		// The target is declared in res/xml/direct_share_targets.xml, so the
		// sheet already knows about it. Returning null means "nothing extra to
		// add", which is correct.
		return null;
	}

	@Override
	public void onSend(ChooserTargetInfo targetInfo, Intent[] shareIntents) {
		if (shareIntents == null || shareIntents.length == 0) {
			return;
		}
		// Hand the payload to the activity that already knows how to stage the
		// files and show the device picker. It is translucent and chrome-free, so
		// all that appears over the source app is the list of machines.
		Intent first = shareIntents[0];
		Intent next = new Intent(this, MainActivity.class);
		next.setAction(first.getAction());
		if (first.getClipData() != null) {
			next.setClipData(first.getClipData());
		}
		if (first.getData() != null) {
			next.setData(first.getData());
		}
		next.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
		startActivity(next);
	}

	@Override
	public android.os.IBinder onBind(Intent intent) {
		return binder;
	}
}
