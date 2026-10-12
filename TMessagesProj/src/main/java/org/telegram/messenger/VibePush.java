package org.telegram.messenger;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.text.TextUtils;

import org.telegram.tgnet.ConnectionsManager;

import java.util.List;
import java.util.UUID;

/**
 * Vibgram notifications without Google: UnifiedPush.
 *
 * A distributor app (ntfy, free on Play Store and F-Droid) gives us a web
 * address. We register that address with Telegram as a "simple push" device.
 * When a message arrives, Telegram pings the address, ntfy wakes Vibgram,
 * and Vibgram connects and shows the notification the normal way.
 */
public class VibePush {

    /** Telegram token_type 4 = simple push (an address that gets pinged). */
    public static final int PUSH_TYPE_SIMPLE = 4;

    static final String ACTION_REGISTER = "org.unifiedpush.android.distributor.REGISTER";
    static final String ACTION_MESSAGE_ACK = "org.unifiedpush.android.distributor.MESSAGE_ACK";
    public static final String NTFY_PACKAGE = "io.heckel.ntfy";

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("vibepush", Context.MODE_PRIVATE);
    }

    /** Our private id for the distributor (lets us ignore messages that are not ours). */
    public static String token() {
        SharedPreferences p = prefs();
        String t = p.getString("token", null);
        if (t == null) {
            t = UUID.randomUUID().toString();
            p.edit().putString("token", t).apply();
        }
        return t;
    }

    public static String savedEndpoint() {
        return prefs().getString("endpoint", null);
    }

    static void saveEndpoint(String endpoint) {
        prefs().edit().putString("endpoint", endpoint).apply();
    }

    static String savedDistributor() {
        return prefs().getString("distributor", null);
    }

    /** Package of an installed UnifiedPush distributor (ntfy preferred), or null. */
    public static String findDistributor(Context ctx) {
        try {
            List<ResolveInfo> list = ctx.getPackageManager().queryBroadcastReceivers(new Intent(ACTION_REGISTER), 0);
            String first = null;
            for (ResolveInfo ri : list) {
                if (ri.activityInfo == null || ctx.getPackageName().equals(ri.activityInfo.packageName)) {
                    continue;
                }
                String pkg = ri.activityInfo.packageName;
                if (NTFY_PACKAGE.equals(pkg)) {
                    return pkg;
                }
                if (first == null) {
                    first = pkg;
                }
            }
            return first;
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        }
    }

    public static boolean hasDistributor() {
        return findDistributor(ApplicationLoader.applicationContext) != null;
    }

    /** Ask the distributor for an address; the answer arrives in {@link VibePushReceiver}. */
    public static void register(Context ctx) {
        String distributor = findDistributor(ctx);
        if (distributor == null) {
            return;
        }
        prefs().edit().putString("distributor", distributor).apply();
        Intent i = new Intent(ACTION_REGISTER);
        i.setPackage(distributor);
        i.putExtra("token", token());
        i.putExtra("application", ctx.getPackageName());
        i.putExtra("message", "Vibgram");
        try {
            int flags = Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
            PendingIntent pi = PendingIntent.getBroadcast(ctx, 0, new Intent("org.unifiedpush.dummy_app"), flags);
            i.putExtra("pi", pi);
        } catch (Throwable ignore) {
        }
        ctx.sendBroadcast(i);
    }

    static void ack(Context ctx, String id) {
        String distributor = savedDistributor();
        if (TextUtils.isEmpty(id) || distributor == null) {
            return;
        }
        Intent i = new Intent(ACTION_MESSAGE_ACK);
        i.setPackage(distributor);
        i.putExtra("token", token());
        i.putExtra("id", id);
        ctx.sendBroadcast(i);
    }

    /** A push arrived: connect every signed-in account so new messages come in and notify. */
    static void wake() {
        ApplicationLoader.postInitApplication();
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (UserConfig.getInstance(a).isClientActivated()) {
                ConnectionsManager.getInstance(a).resumeNetworkMaybe();
                ConnectionsManager.onInternalPushReceived(a);
            }
        }
    }

    // ------------------------------------------------------------------ built-in mode (no extra app)

    /** Built-in instant notifications are on by default when no ntfy app is installed. */
    public static boolean builtInEnabled() {
        return prefs().getBoolean("builtin", true);
    }

    public static void setBuiltInEnabled(Context ctx, boolean on) {
        prefs().edit().putBoolean("builtin", on).apply();
        applyMode(ctx);
    }

    /**
     * Picks how notifications arrive: ntfy app if installed (best for battery),
     * otherwise the built-in connection service.
     */
    public static void applyMode(Context ctx) {
        if (hasDistributor()) {
            VibeConnectionService.stop(ctx);
            if (savedEndpoint() == null) {
                register(ctx);
            }
            return;
        }
        android.content.SharedPreferences global = MessagesController.getGlobalNotificationsSettings();
        if (builtInEnabled()) {
            global.edit().putBoolean("pushConnection", true).apply();
            for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
                if (UserConfig.getInstance(a).isClientActivated()) {
                    ConnectionsManager.getInstance(a).setPushConnectionEnabled(true);
                }
            }
            VibeConnectionService.start(ctx);
        } else {
            VibeConnectionService.stop(ctx);
        }
    }

    public static final PushListenerController.IPushListenerServiceProvider PROVIDER = new PushListenerController.IPushListenerServiceProvider() {
        @Override
        public boolean hasServices() {
            return hasDistributor();
        }

        @Override
        public String getLogTitle() {
            return "UnifiedPush";
        }

        @Override
        public void onRequestPushToken() {
            String endpoint = savedEndpoint();
            if (!TextUtils.isEmpty(endpoint)) {
                PushListenerController.sendRegistrationToServer(PUSH_TYPE_SIMPLE, endpoint);
            }
            register(ApplicationLoader.applicationContext);
        }

        @Override
        public int getPushType() {
            return PUSH_TYPE_SIMPLE;
        }
    };
}
