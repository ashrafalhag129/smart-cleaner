package com.smartclean.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Opens Smart Cleaner when the user dials the secret code {@code *#*#4726726#*#*}.
 *
 * <p>The receiver is triggered by the system dialer through
 * {@code android.provider.Telephony.SECRET_CODE}. It only launches this app's own activity
 * and does not read, block or intercept calls.</p>
 */
public class SecretCodeReceiver extends BroadcastReceiver {

    private static final String TAG = "SecretCodeReceiver";
    private static final String ACTION_SECRET_CODE = "android.provider.Telephony.SECRET_CODE";

    /** The dial string shown to the user, matching the host value declared in the manifest. */
    public static final String SECRET_CODE = "*#*#4726726#*#*";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION_SECRET_CODE.equals(intent.getAction())) {
            Log.w(TAG, "Unexpected broadcast, ignoring.");
            return;
        }

        Intent launch = new Intent(context, MainActivity.class);
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        launch.putExtra(MainActivity.EXTRA_AUTO_CLEAN, true);
        context.startActivity(launch);
    }
}
