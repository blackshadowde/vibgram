package org.telegram.messenger;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.text.TextUtils;

/** Vibgram: receives UnifiedPush events from the distributor app (ntfy). */
public class VibePushReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) {
            return;
        }
        String token = intent.getStringExtra("token");
        if (token != null && !token.equals(VibePush.token())) {
            return;
        }
        switch (intent.getAction()) {
            case "org.unifiedpush.android.connector.NEW_ENDPOINT": {
                String endpoint = intent.getStringExtra("endpoint");
                if (!TextUtils.isEmpty(endpoint)) {
                    VibePush.saveEndpoint(endpoint);
                    AndroidUtilities.runOnUIThread(() -> PushListenerController.sendRegistrationToServer(VibePush.PUSH_TYPE_SIMPLE, endpoint));
                }
                break;
            }
            case "org.unifiedpush.android.connector.MESSAGE": {
                VibePush.ack(context, intent.getStringExtra("id"));
                final PendingResult result = goAsync();
                AndroidUtilities.runOnUIThread(() -> {
                    try {
                        VibePush.wake();
                    } catch (Throwable e) {
                        FileLog.e(e);
                    }
                    // Give the connection a few seconds to fetch the new messages.
                    AndroidUtilities.runOnUIThread(() -> {
                        try {
                            result.finish();
                        } catch (Throwable ignore) {
                        }
                    }, 8000);
                });
                break;
            }
            case "org.unifiedpush.android.connector.UNREGISTERED": {
                VibePush.saveEndpoint(null);
                break;
            }
            default:
                break;
        }
    }
}
