package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;

/**
 * Vibgram message requests, like Discord.
 *
 * A DM from someone who is not in your contacts, and whom you have never written to,
 * is a request: it stays out of your DM rail, does not ping you, and waits in
 * "Message requests" until you Accept, Ignore or Block it.
 * Chats you already talk in are never requests.
 */
public class VibeRequests {

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("vibereq", Context.MODE_PRIVATE);
    }

    private static String key(int account, long did) {
        return "ok_" + UserConfig.getInstance(account).getClientUserId() + "_" + did;
    }

    public static boolean isAccepted(int account, long did) {
        return prefs().getBoolean(key(account, did), false);
    }

    public static void accept(int account, long did) {
        prefs().edit().putBoolean(key(account, did), true).apply();
    }

    /** Safe to call from the notifications thread. */
    public static boolean isRequest(int account, long did) {
        if (!VibeLayout.ON || did <= 0 || !DialogObject.isUserDialog(did)) {
            return false;
        }
        if (isAccepted(account, did)) {
            return false;
        }
        MessagesController mc = MessagesController.getInstance(account);
        TLRPC.User user = mc.getUser(did);
        if (user == null || user.self || user.bot || user.contact || user.mutual_contact || user.support || user.verified
                || UserObject.isDeleted(user) || UserObject.isService(did) || UserObject.isReplyUser(did)
                || did == UserConfig.getInstance(account).getClientUserId()) {
            return false;
        }
        TLRPC.Dialog dialog;
        ArrayList<MessageObject> top;
        try {
            dialog = mc.dialogs_dict.get(did);
            top = mc.dialogMessage.get(did);
        } catch (Throwable e) {
            return false;
        }
        if (dialog != null && dialog.read_outbox_max_id > 0) {
            // You have written to them before: a normal chat.
            accept(account, did);
            return false;
        }
        if (top != null && !top.isEmpty() && top.get(0) != null && top.get(0).isOut()) {
            // You started this chat.
            accept(account, did);
            return false;
        }
        return true;
    }

    public static CharSequence preview(int account, long did) {
        ArrayList<MessageObject> top = MessagesController.getInstance(account).dialogMessage.get(did);
        if (top == null || top.isEmpty() || top.get(0) == null) {
            return "Wants to send you a message";
        }
        MessageObject m = top.get(0);
        if (m.caption != null && m.caption.length() > 0) {
            return m.caption;
        }
        if (m.isPhoto()) {
            return "Photo";
        }
        if (m.isVideo()) {
            return "Video";
        }
        if (m.isVoice()) {
            return "Voice message";
        }
        if (m.isAnyKindOfSticker()) {
            return "Sticker";
        }
        return m.messageText != null && m.messageText.length() > 0 ? m.messageText : "Message";
    }
}
