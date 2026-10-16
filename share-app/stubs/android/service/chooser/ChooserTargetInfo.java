package android.service.chooser;

/**
 * Compile-time stub. NOT shipped in the APK.
 *
 * android.service.chooser is a hidden platform API: it is how an app offers
 * itself as a destination that renders inside the share sheet itself, and it is
 * absent from the public SDK's android.jar. The real class is provided by the
 * platform at runtime; these declarations exist only so javac can type-check the
 * code below, and the build drops every class under stubs/ before dexing.
 *
 * Signatures mirror android-34. If they drift from the platform the app throws
 * NoSuchMethodError when the sheet opens it, which is why nothing here is
 * allowed into the dex.
 */
public class ChooserTargetInfo {

	public static class Builder {
		public Builder setTitle(CharSequence title) {
			return this;
		}

		public Builder setSubtitle(CharSequence subtitle) {
			return this;
		}

		public Builder setIcon(android.graphics.drawable.Icon icon) {
			return this;
		}

		public ChooserTargetInfo build() {
			return new ChooserTargetInfo();
		}
	}
}
