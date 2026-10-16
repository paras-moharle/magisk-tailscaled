package android.service.chooser;

import android.content.ComponentName;
import android.content.Intent;
import android.content.IntentFilter;

/**
 * Compile-time stub. NOT shipped in the APK. See ChooserTargetInfo.java.
 */
public abstract class ChooserTargetService extends android.app.Service {

	public void onPrepareChooserTarget(ChooserTargetInfo.Builder builder) {
	}

	public void onSend(ChooserTargetInfo targetInfo, Intent[] shareIntents) {
	}

	public java.util.List<ChooserTargetInfo> onGetChooserTargets(
			ComponentName target, IntentFilter targetFilter) {
		return null;
	}
}
