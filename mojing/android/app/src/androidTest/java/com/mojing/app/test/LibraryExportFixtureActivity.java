package com.mojing.app.test;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;

/** Test-only bootstrap: grant the app control access; document access still uses SAF. */
public final class LibraryExportFixtureActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (!isGenericEmulator()) {
            finish();
            return;
        }
        grantUriPermission("com.mojing.app", Uri.parse("content://" + LibraryExportDocumentsProvider.AUTHORITY),
                Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        finish();
    }

    private boolean isGenericEmulator() {
        String fingerprint = Build.FINGERPRINT == null ? "" : Build.FINGERPRINT.toLowerCase(java.util.Locale.US);
        String model = Build.MODEL == null ? "" : Build.MODEL.toLowerCase(java.util.Locale.US);
        return fingerprint.startsWith("generic") || fingerprint.contains("emulator")
                || fingerprint.contains("goldfish") || fingerprint.contains("ranchu")
                || model.contains("emulator") || model.contains("sdk_");
    }
}
