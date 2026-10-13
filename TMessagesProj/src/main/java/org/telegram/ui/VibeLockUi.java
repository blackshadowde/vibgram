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

/** Vibgram: the screens for Vibe lock (request, accept, devices, linking, turn off). */
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

    private static long self(int account) {
        return UserConfig.getInstance(account).getClientUserId();
    }

    private static boolean sendRequest(BaseFragment fragment, int account, long dialogId) {
        if (VibeLock.isWaitingForLink(account)) {
            toast(fragment, "Finish linking this device first");
            return false;
        }
        String text = VibeLock.makeRequest(account, dialogId);
        if (text == null) {
            return false;
        }
        send(account, dialogId, text);
        return true;
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

    private static final String NEW_PHONE_NOTE = "\n\nIf Vibe lock is already set up on another of your phones, link this phone first (Settings > Vibe lock) instead of starting here.";

    /** Shown every time Vibe lock is opened: it is an experimental feature, still in development. */
    private static void experimental(BaseFragment fragment, Runnable then) {
        if (fragment == null || fragment.getParentActivity() == null) {
            return;
        }
        AlertDialog.Builder b = new AlertDialog.Builder(fragment.getParentActivity());
        b.setTitle("Experimental feature");
        b.setMessage("Vibe lock is still in development. It hasn't been tested much on real phones yet, so it may not always work as expected.\n\n"
                + "Don't rely on it for anything sensitive yet. For private conversations, Telegram's secret chats are the safer choice for now.");
        b.setPositiveButton("Continue", (d, w) -> then.run());
        b.setNegativeButton("Cancel", null);
        fragment.showDialog(b.create());
    }

    /** The lock button / menu entry in a chat. */
    public static void showMenu(BaseFragment fragment, int account, long dialogId, Runnable onChanged) {
        if (fragment == null || fragment.getParentActivity() == null || !canUse(account, dialogId)) {
            return;
        }
        experimental(fragment, () -> showMenuNow(fragment, account, dialogId, onChanged));
    }

    private static void showMenuNow(BaseFragment fragment, int account, long dialogId, Runnable onChanged) {
        final String who = name(account, dialogId);
        final int state = VibeLock.state(account, dialogId);
        AlertDialog.Builder b = new AlertDialog.Builder(fragment.getParentActivity());
        b.setTitle("Vibe lock (experimental)");
        if (state == VibeLock.ON && VibeLock.isOn(account, dialogId)) {
            String code = VibeLock.safetyCode(account, dialogId);
            b.setMessage("Messages you write in this chat are locked. Only Vibgram on your phones and " + who + "'s phones can read them.\n\n"
                    + (code != null ? "Safety code: " + code + "\nIt should match the code on " + who + "'s phone. It stays the same when either of you adds a device.\n\n" : "")
                    + "Every message gets its own key and the keys change as you talk, like Signal. Keys are kept in your phones' secure hardware.\n\n"
                    + "Photos, files and voice messages are not locked. Telegram Desktop and other Telegram apps show locked messages as scrambled text.");
            b.setPositiveButton("Turn off", (d, w) -> {
                sendOff(account, dialogId);
                changed(fragment, onChanged, "Vibe lock turned off");
            });
            b.setNeutralButton("Devices", (d, w) -> showDevicesNow(fragment, account));
            b.setNegativeButton("Close", null);
        } else if (state == VibeLock.REQUESTED) {
            b.setMessage("Waiting for " + who + " to accept. They need Vibgram to accept. If they use another Telegram app, nothing changes and your messages stay normal.");
            b.setPositiveButton("Send again", (d, w) -> sendRequest(fragment, account, dialogId));
            b.setNegativeButton("Cancel request", (d, w) -> {
                VibeLock.cancelRequest(account, dialogId);
                changed(fragment, onChanged, null);
            });
        } else if (state == VibeLock.INCOMING) {
            showAccept(fragment, account, dialogId, onChanged);
            return;
        } else {
            b.setMessage("Lock this chat so only Vibgram on your phones and " + who + "'s phones can read your messages. Telegram's servers will only see scrambled text.\n\n"
                    + who + " needs Vibgram and has to accept. Until then, and with any other Telegram app, messages stay normal.\n\n"
                    + "A short request message will be sent to " + who + "."
                    + (VibeLock.isSetUp(account) ? "" : NEW_PHONE_NOTE));
            b.setPositiveButton("Send request", (d, w) -> {
                if (sendRequest(fragment, account, dialogId)) {
                    changed(fragment, onChanged, "Request sent to " + who);
                }
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
        String value = VibeLock.pendingRequest(account, dialogId);
        boolean changedIdentity = value != null && VibeLock.requestChangesIdentity(account, dialogId, value);
        AlertDialog.Builder b = new AlertDialog.Builder(fragment.getParentActivity());
        b.setTitle("Turn on Vibe lock?");
        b.setMessage((changedIdentity ? "⚠ " + who + "'s Vibe lock identity is new (they may have reset Vibgram). Compare the safety code with them after accepting.\n\n" : "")
                + who + " wants to lock this chat. Messages you both write here will only be readable in Vibgram on your phones, and Telegram's servers will only see scrambled text.\n\n"
                + "Photos, files and voice messages are not locked. You can turn it off any time from the chat menu."
                + (VibeLock.isSetUp(account) ? "" : NEW_PHONE_NOTE));
        b.setPositiveButton("Turn on", (d, w) -> {
            if (VibeLock.isWaitingForLink(account)) {
                toast(fragment, "Finish linking this device first");
            } else if (sendAccept(account, dialogId)) {
                changed(fragment, onChanged, "Vibe lock is on");
            }
        });
        b.setNegativeButton("Not now", (d, w) -> {
            VibeLock.setState(account, dialogId, VibeLock.NONE);
            changed(fragment, onChanged, null);
        });
        fragment.showDialog(b.create());
    }

    // ---------------------------------------------------------------- devices

    /** Settings > Vibe lock: this device, linked devices, linking. */
    public static void showDevices(BaseFragment fragment, int account) {
        experimental(fragment, () -> showDevicesNow(fragment, account));
    }

    private static void showDevicesNow(BaseFragment fragment, int account) {
        if (fragment == null || fragment.getParentActivity() == null) {
            return;
        }
        AlertDialog.Builder b = new AlertDialog.Builder(fragment.getParentActivity());
        b.setTitle("Vibe lock devices (experimental)");
        if (!VibeLock.supported()) {
            b.setMessage("Vibe lock needs Android 6 or newer.");
            b.setPositiveButton("OK", null);
        } else if (VibeLock.isWaitingForLink(account)) {
            b.setMessage("Waiting for your main phone to approve this device.\n\nOn your main phone, open Saved Messages in Vibgram and approve the request.\n\nCode on this phone: " + VibeLock.myLinkCode(account) + "\nCheck that your main phone shows the same code.");
            b.setPositiveButton("OK", null);
            b.setNegativeButton("Cancel linking", (d, w) -> VibeLock.cancelLink(account));
        } else if (!VibeLock.isSetUp(account)) {
            b.setMessage("Vibe lock isn't set up on this phone yet.\n\n• If you already use Vibe lock on another phone, link this one to it.\n• Otherwise it will be set up here the first time you lock a chat, and this becomes your main phone.");
            b.setPositiveButton("Link to my main phone", (d, w) -> {
                String text = VibeLock.makeLinkRequest(account);
                if (text != null) {
                    send(account, self(account), text);
                    showDevicesNow(fragment, account);
                }
            });
            b.setNegativeButton("Close", null);
        } else {
            ArrayList<String[]> devices = VibeLock.myDevices(account);
            StringBuilder sb = new StringBuilder();
            boolean main = VibeLock.isMainDevice(account);
            sb.append(main ? "This is your main phone. It approves new devices.\n\n" : "This phone is linked to your main phone.\n\n");
            for (int i = 0; i < devices.size(); i++) {
                String[] d = devices.get(i);
                sb.append("• Device ").append(d[0].substring(0, 4)).append(' ').append(d[0].substring(4, 8));
                if ("1".equals(d[2])) sb.append(" — main");
                if ("1".equals(d[1])) sb.append(" — this phone");
                sb.append('\n');
            }
            sb.append(main
                    ? "\nTo add a phone or tablet: install Vibgram there, sign in, open Settings > Vibe lock > Link to my main phone, then open Saved Messages here to approve it."
                    : "\nNew devices are approved on your main phone.");
            b.setMessage(sb.toString());
            b.setPositiveButton("Close", null);
            if (main && devices.size() > 1) {
                b.setNegativeButton("Remove a device", (d, w) -> showRemove(fragment, account, devices));
            }
        }
        fragment.showDialog(b.create());
    }

    private static void showRemove(BaseFragment fragment, int account, ArrayList<String[]> devices) {
        ArrayList<String> ids = new ArrayList<>();
        ArrayList<CharSequence> labels = new ArrayList<>();
        for (String[] d : devices) {
            if (!"1".equals(d[1])) {
                ids.add(d[0]);
                labels.add("Device " + d[0].substring(0, 4) + " " + d[0].substring(4, 8));
            }
        }
        AlertDialog.Builder b = new AlertDialog.Builder(fragment.getParentActivity());
        b.setTitle("Remove a device");
        b.setItems(labels.toArray(new CharSequence[0]), (d, which) -> {
            if (VibeLock.removeDevice(account, ids.get(which))) {
                toast(fragment, "Removed. Your next locked messages won't reach it.");
            }
        });
        b.setNegativeButton("Cancel", null);
        fragment.showDialog(b.create());
    }

    private static void showApproveLink(BaseFragment fragment, int account, String linkValue, Runnable onChanged) {
        AlertDialog.Builder b = new AlertDialog.Builder(fragment.getParentActivity());
        b.setTitle("Link a new device?");
        b.setMessage("A device signed in to your account wants to join Vibe lock.\n\nCode: " + VibeLock.linkCode(linkValue)
                + "\n\nOnly approve if your new device shows exactly this code. It will be able to read and send your locked messages from now on.");
        b.setPositiveButton("Approve", (d, w) -> {
            ArrayList<String> parts = VibeLock.approveLink(account, linkValue);
            if (parts != null) {
                for (String p : parts) {
                    send(account, self(account), p);
                }
                changed(fragment, onChanged, "Device linked");
            }
        });
        b.setNegativeButton("Not mine", null);
        fragment.showDialog(b.create());
    }

    // ---------------------------------------------------------------- helpers

    private static void toast(BaseFragment fragment, String text) {
        try {
            BulletinFactory.of(fragment).createSimpleBulletin(org.telegram.messenger.R.raw.info, text).show();
        } catch (Throwable ignore) {
        }
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

    // ---------------------------------------------------------------- reading the chat

    /** Looks at the chat's messages for Vibe lock requests, answers and device links. */
    public static void scan(BaseFragment fragment, int account, long dialogId, ArrayList<MessageObject> messages, Runnable onChanged) {
        if (messages == null || messages.isEmpty() || !VibeLock.supported()) {
            return;
        }
        boolean saved = dialogId == self(account);
        if (!saved && !canUse(account, dialogId)) {
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
        int maxId = last;
        if (saved) {
            // Saved Messages: a new device asking to be linked (approved on the main phone)
            String pending = null;
            for (MessageObject m : found) {
                maxId = Math.max(maxId, m.getId());
                String v = VibeLock.handshakeValue(m.messageOwner.message, VibeLock.LINK);
                if (v != null && VibeLock.isMainDevice(account) && VibeLock.isNewDeviceLink(account, v)) {
                    pending = v;
                }
            }
            VibeLock.setLastProcessed(account, dialogId, maxId);
            if (pending != null) {
                showApproveLink(fragment, account, pending, onChanged);
            }
            return;
        }

        boolean changed = false, ask = false;
        int askId = 0;
        for (MessageObject m : found) {
            maxId = Math.max(maxId, m.getId());
            String t = m.messageOwner.message;
            String req = VibeLock.handshakeValue(t, VibeLock.REQ);
            String ack = VibeLock.handshakeValue(t, VibeLock.ACK);
            int state = VibeLock.state(account, dialogId);
            if (m.isOut()) {
                // sent from this phone or another of our phones
                if (req != null && state != VibeLock.ON) {
                    VibeLock.setState(account, dialogId, VibeLock.REQUESTED);
                    changed = true;
                } else if (ack != null && state != VibeLock.ON) {
                    changed |= VibeLock.ourOtherDeviceAccepted(account, dialogId);
                    ask = false;
                } else if (VibeLock.isOffMessage(t) && state != VibeLock.NONE) {
                    VibeLock.turnOff(account, dialogId);
                    changed = true;
                    ask = false;
                }
                continue;
            }
            if (req != null) {
                if (state == VibeLock.REQUESTED && VibeLock.isSetUp(account)) {
                    // both asked at the same time: one side answers, the other waits for that answer
                    if (VibeLock.shouldAnswerCrossedRequest(account, req) && VibeLock.rememberRequest(account, dialogId, req)) {
                        changed |= sendAccept(account, dialogId);
                    }
                } else if (VibeLock.rememberRequest(account, dialogId, req)) {
                    ask = true;
                    askId = m.getId();
                    changed = true;
                }
            } else if (ack != null) {
                if (VibeLock.handleAck(t) != 0 || (state == VibeLock.REQUESTED && VibeLock.acceptAnswer(account, dialogId, ack))) {
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
        } else if (changed && VibeLock.isOn(account, dialogId)) {
            changed(fragment, null, "Vibe lock is on");
        }
    }
}
