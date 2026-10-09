package com.mojing.app.test;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

/** Test APK only: bootstrap the exact control URI; document access still uses SAF. */
public final class MediaBundleFixtureActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        grantUriPermission("com.mojing.app", Uri.parse("content://" + MediaBundleDocumentsProvider.AUTHORITY),
                Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        finish();
    }
}
