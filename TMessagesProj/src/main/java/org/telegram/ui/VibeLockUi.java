package org.telegram.ui;

import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.VibeLock;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.BulletinFactory;

import java.util.ArrayList;
import java.util.Collections;

/** Vibgram: the screens for Vibe lock (request, accept, status, turn off). */
public class VibeLockUi {

    /** Vibe lock works in one-to-one chats with real people. */
    public static boolean canUse(int account, long dialogId) {
        if (!VibeLock.supported() || dialogId <= 0 || dialogId == UserConfig.getInstance(account).getClientUserId()) {
            return false;
        }
        TLRPC.User u = MessagesController.getInstance(account).getUser(dialogId);
        return u != null && !u.bot && !UserObject.isDeleted(u) && !UserObject.isReplyUser(u) && !UserObject.isService(u.id);
    }

    private static String name(int account, long dialogId) {
        TLRPC.User u = MessagesController.getInstance(account).getUser(dialogId);
        return u != null ? UserObject.getFirstName(u) : "they";
    }

    private static void send(int account, long dialogId, String text) {
        SendMessagesHelper.getInstance(account).sendMessage(SendMessagesHelper.SendMessageParams.of(text, dialogId));
    }

    private static void sendRequest(int account, long dialogId) {
        String text = VibeLock.makeRequest(account, dialogId);
        if (text != null) {
            send(account, dialogId, text);
        }
    }

    private static boolean sendAccept(int account, long dialogId) {
        String text = VibeLock.accept(account, dialogId);
        if (text == null) {
            return false;
        }
        send(account, dialogId, text);
        return true;
    }

    private static void sendOff(int account, long dialogId) {
        VibeLock.turnOff(account, dialogId);
        send(account, dialogId, "🔓 Vibe lock is off\n" + VibeLock.OFF);
    }

    /** The lock button / menu entry. */
    public static void showMenu(BaseFragment fragment, int account, long dialogId, Runnable onChanged) {
        if (fragment == null || fragment.getParentActivity() == null || !canUse(account, dialogId)) {
            return;
        }
        final String who = name(account, dialogId);
        final int state = VibeLock.state(account, dialogId);
        AlertDialog.Builder b = new AlertDialog.Builder(fragment.getParentActivity());
        b.setTitle("Vibe lock");
        if (state == VibeLock.ON) {
            String code = VibeLock.safetyCode(account, dialogId);
            b.setMessage("Messages you write in this chat are locked. Only Vibgram on your phone and " + who + "'s phone can read them.\n\n"
                    + (code != null ? "Safety code: " + code + "\nIt should match the code on " + who + "'s phone.\n\n" : "")
                    + "Every message gets its own key and the keys change as you talk, like Signal, so a stolen key can't open earlier messages. Your identity key is kept in your phone's secure hardware (Android Keystore).\n\n"
                    + "Photos, files and voice messages are not locked. Locked messages show as scrambled text in other Telegram apps and on your other devices.");
            b.setPositiveButton("Turn off", (d, w) -> {
                sendOff(account, dialogId);
                changed(fragment, onChanged, "Vibe lock turned off");
            });
            b.setNegativeButton("Close", null);
        } else if (state == VibeLock.REQUESTED) {
            b.setMessage("Waiting for " + who + " to accept. They need Vibgram to accept. If they use another Telegram app, nothing changes and your messages stay normal.");
            b.setPositiveButton("Send again", (d, w) -> sendRequest(account, dialogId));
            b.setNegativeButton("Cancel request", (d, w) -> {
                VibeLock.cancelRequest(account, dialogId);
                changed(fragment, onChanged, null);
            });
        } else if (state == VibeLock.INCOMING) {
            showAccept(fragment, account, dialogId, onChanged);
            return;
        } else {
            b.setMessage("Lock this chat so only Vibgram on your phone and " + who + "'s phone can read your messages. Telegram's servers will only see scrambled text.\n\n"
                    + who + " needs Vibgram and has to accept. Until then, and with any other Telegram app, messages stay normal.\n\n"
                    + "A short request message will be sent to " + who + ".");
            b.setPositiveButton("Send request", (d, w) -> {
                sendRequest(account, dialogId);
                changed(fragment, onChanged, "Request sent to " + who);
            });
            b.setNegativeButton("Not now", null);
        }
        fragment.showDialog(b.create());
    }

    private static void showAccept(BaseFragment fragment, int account, long dialogId, Runnable onChanged) {
        if (fragment == null || fragment.getParentActivity() == null) {
            return;
        }
        final String who = name(account, dialogId);
        AlertDialog.Builder b = new AlertDialog.Builder(fragment.getParentActivity());
        b.setTitle("Turn on Vibe lock?");
        b.setMessage(who + " wants to lock this chat. Messages you both write here will only be readable in Vibgram on your two phones, and Telegram's servers will only see scrambled text.\n\n"
                + "Photos, files and voice messages are not locked. You can turn it off any time from the chat menu.");
        b.setPositiveButton("Turn on", (d, w) -> {
            if (sendAccept(account, dialogId)) {
                changed(fragment, onChanged, "Vibe lock is on");
            }
        });
        b.setNegativeButton("Not now", (d, w) -> {
            VibeLock.setState(account, dialogId, VibeLock.NONE);
            changed(fragment, onChanged, null);
        });
        fragment.showDialog(b.create());
    }

    private static void changed(BaseFragment fragment, Runnable onChanged, String toast) {
        if (onChanged != null) {
            onChanged.run();
        }
        if (toast != null) {
            try {
                BulletinFactory.of(fragment).createSimpleBulletin(org.telegram.messenger.R.raw.done, toast).show();
            } catch (Throwable ignore) {
            }
        }
    }

    /** Looks at the chat's messages for lock requests / answers from the other person. */
    public static void scan(BaseFragment fragment, int account, long dialogId, ArrayList<MessageObject> messages, Runnable onChanged) {
        if (messages == null || messages.isEmpty() || !canUse(account, dialogId)) {
            return;
        }
        final int last = VibeLock.lastProcessed(account, dialogId);
        ArrayList<MessageObject> found = null;
        for (int i = 0; i < messages.size(); i++) {
            MessageObject m = messages.get(i);
            if (m == null || m.messageOwner == null || m.getId() <= last || m.getDialogId() != dialogId) {
                continue;
            }
            String t = m.messageOwner.message;
            if (t != null && VibeLock.isHandshake(t)) {
                if (found == null) {
                    found = new ArrayList<>();
                }
                found.add(m);
            }
        }
        if (found == null) {
            return;
        }
        Collections.sort(found, (a, b) -> Integer.compare(a.getId(), b.getId()));
        boolean changed = false;
        boolean ask = false;
        int askId = 0;
        int maxId = last;
        for (MessageObject m : found) {
            maxId = Math.max(maxId, m.getId());
            if (m.isOut()) {
                continue; // our own request / answer: already handled when we sent it
            }
            String t = m.messageOwner.message;
            String req = VibeLock.handshakeValue(t, VibeLock.REQ);
            int state = VibeLock.state(account, dialogId);
            if (req != null) {
                String[] parts = req.split("\\.");
                if (parts.length < 2) {
                    continue;
                }
                if (state == VibeLock.REQUESTED) {
                    // both asked at the same time: one side answers, the other waits for that answer
                    if (VibeLock.shouldAnswerCrossedRequest(account, req)) {
                        VibeLock.rememberRequest(account, dialogId, req);
                        if (sendAccept(account, dialogId)) {
                            changed = true;
                        }
                    }
                } else {
                    // a new request (or a fresh one after they reinstalled Vibgram): ask before doing anything
                    VibeLock.rememberRequest(account, dialogId, req);
                    ask = true;
                    askId = m.getId();
                    changed = true;
                }
            } else if (VibeLock.handshakeValue(t, VibeLock.ACK) != null) {
                if (VibeLock.handleAck(t) != 0) {
                    changed = true;
                    ask = false;
                }
            } else if (VibeLock.isOffMessage(t)) {
                if (state != VibeLock.NONE) {
                    VibeLock.turnOff(account, dialogId);
                    changed = true;
                    ask = false;
                }
            }
        }
        VibeLock.setLastProcessed(account, dialogId, maxId);
        if (changed && onChanged != null) {
            onChanged.run();
        }
        if (ask && VibeLock.state(account, dialogId) == VibeLock.INCOMING && VibeLock.prompted(account, dialogId) != askId) {
            VibeLock.setPrompted(account, dialogId, askId);
            showAccept(fragment, account, dialogId, onChanged);
        } else if (changed && VibeLock.state(account, dialogId) == VibeLock.ON) {
            changed(fragment, null, "Vibe lock is on");
        }
    }
}
