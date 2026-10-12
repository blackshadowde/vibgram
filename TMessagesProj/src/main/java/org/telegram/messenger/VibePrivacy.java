package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_account;

/**
 * Vibgram privacy shields.
 *  - No "X joined Telegram" alerts.
 *  - Only your contacts can add you to groups and channels (others must send an invite link).
 *  - Hide your phone number.
 * The first two are switched on once by default; everything can be changed later.
 */
public class VibePrivacy {

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("vibeprivacy", Context.MODE_PRIVATE);
    }

    public static void applyDefaultsOnce(int account) {
        if (!UserConfig.getInstance(account).isClientActivated()) {
            return;
        }
        String key = "defaults_" + UserConfig.getInstance(account).getClientUserId();
        if (prefs().getBoolean(key, false)) {
            return;
        }
        prefs().edit().putBoolean(key, true).apply();
        setJoinedAlertsOff(account, true);
        setInvitesContactsOnly(account, true);
    }

    public static boolean joinedAlertsOff(int account) {
        return !MessagesController.getNotificationsSettings(account).getBoolean("EnableContactJoined", true);
    }

    public static void setJoinedAlertsOff(int account, boolean off) {
        MessagesController.getNotificationsSettings(account).edit().putBoolean("EnableContactJoined", !off).apply();
        MessagesController.getInstance(account).enableJoined = !off;
        TL_account.setContactSignUpNotification req = new TL_account.setContactSignUpNotification();
        req.silent = off;
        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> {
        });
    }

    public static boolean invitesContactsOnly(int account) {
        return prefs().getBoolean("invites_" + UserConfig.getInstance(account).getClientUserId(), false);
    }

    public static void setInvitesContactsOnly(int account, boolean on) {
        prefs().edit().putBoolean("invites_" + UserConfig.getInstance(account).getClientUserId(), on).apply();
        TL_account.setPrivacy req = new TL_account.setPrivacy();
        req.key = new TLRPC.TL_inputPrivacyKeyChatInvite();
        if (on) {
            req.rules.add(new TLRPC.TL_inputPrivacyValueAllowContacts());
            req.rules.add(new TLRPC.TL_inputPrivacyValueDisallowAll());
        } else {
            req.rules.add(new TLRPC.TL_inputPrivacyValueAllowAll());
        }
        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> {
        });
    }

    public static boolean phoneHidden(int account) {
        return prefs().getBoolean("phone_" + UserConfig.getInstance(account).getClientUserId(), false);
    }

    public static void setPhoneHidden(int account, boolean hidden) {
        prefs().edit().putBoolean("phone_" + UserConfig.getInstance(account).getClientUserId(), hidden).apply();
        TL_account.setPrivacy req = new TL_account.setPrivacy();
        req.key = new TLRPC.TL_inputPrivacyKeyPhoneNumber();
        if (hidden) {
            req.rules.add(new TLRPC.TL_inputPrivacyValueDisallowAll());
        } else {
            req.rules.add(new TLRPC.TL_inputPrivacyValueAllowAll());
        }
        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> {
        });
    }

    // ------------------------------------------------------------------ status ("What are you obsessed with?")

    /** Status lives in the Telegram bio, so friends see it. Your old bio comes back when it is cleared. */
    public static void setStatus(int account, String status, long clearAtMillis) {
        TLRPC.UserFull full = MessagesController.getInstance(account).getUserFull(UserConfig.getInstance(account).getClientUserId());
        SharedPreferences p = prefs();
        String uid = String.valueOf(UserConfig.getInstance(account).getClientUserId());
        if (!p.getBoolean("status_active_" + uid, false)) {
            p.edit().putString("old_bio_" + uid, full != null && full.about != null ? full.about : "").apply();
        }
        p.edit().putBoolean("status_active_" + uid, true).putString("status_" + uid, status).putLong("status_clear_" + uid, clearAtMillis).apply();
        updateBio(account, status);
    }

    public static String currentStatus(int account) {
        String uid = String.valueOf(UserConfig.getInstance(account).getClientUserId());
        return prefs().getBoolean("status_active_" + uid, false) ? prefs().getString("status_" + uid, null) : null;
    }

    public static void clearStatus(int account) {
        SharedPreferences p = prefs();
        String uid = String.valueOf(UserConfig.getInstance(account).getClientUserId());
        if (!p.getBoolean("status_active_" + uid, false)) {
            return;
        }
        String old = p.getString("old_bio_" + uid, "");
        p.edit().putBoolean("status_active_" + uid, false).remove("status_" + uid).remove("status_clear_" + uid).apply();
        updateBio(account, old);
    }

    /** Called on app open: clears a status whose time is up. */
    public static void checkStatusExpiry(int account) {
        String uid = String.valueOf(UserConfig.getInstance(account).getClientUserId());
        long clearAt = prefs().getLong("status_clear_" + uid, 0);
        if (clearAt > 0 && System.currentTimeMillis() > clearAt) {
            clearStatus(account);
        }
    }

    private static void updateBio(int account, String about) {
        TL_account.updateProfile req = new TL_account.updateProfile();
        req.flags = 4;
        req.about = about == null ? "" : about;
        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            TLRPC.UserFull full = MessagesController.getInstance(account).getUserFull(UserConfig.getInstance(account).getClientUserId());
            if (error == null && full != null) {
                full.about = req.about;
                NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.userInfoDidLoad, UserConfig.getInstance(account).getClientUserId(), full);
            }
        }));
    }
}
