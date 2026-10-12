package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.annotation.SuppressLint;
import android.content.Context;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.StyleSpan;
import android.util.TypedValue;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.ChatObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.VibeLayout;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

/**
 * Vibgram: the start of a conversation, like Discord / Vibe:
 * a big round picture, the name in large type, @username and one line
 * saying this is the beginning of your history.
 */
@SuppressLint("ViewConstructor")
public class VibeChatIntroView extends LinearLayout {

    public VibeChatIntroView(Context context, int account, TLObject peer, boolean secret) {
        super(context);
        setOrientation(VERTICAL);
        setPadding(dp(16), dp(16), dp(16), dp(24));

        BackupImageView avatar = new BackupImageView(context);
        avatar.setRoundRadius(dp(40));
        AvatarDrawable avatarDrawable = new AvatarDrawable();
        avatarDrawable.setInfo(account, peer);
        avatar.setForUserOrChat(peer, avatarDrawable);
        addView(avatar, LayoutHelper.createLinear(80, 80));

        String name;
        String username;
        if (peer instanceof TLRPC.User) {
            TLRPC.User u = (TLRPC.User) peer;
            name = UserObject.getUserName(u);
            username = UserObject.getPublicUsername(u);
        } else {
            TLRPC.Chat c = (TLRPC.Chat) peer;
            name = c.title == null ? "" : c.title;
            username = ChatObject.getPublicUsername(c);
        }

        TextView title = new TextView(context);
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 30);
        title.setTypeface(VibeLayout.boldFont());
        title.setTextColor(VibeLayout.text());
        title.setText(name);
        addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 14, 0, 0));

        if (!TextUtils.isEmpty(username)) {
            TextView user = new TextView(context);
            user.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
            user.setTextColor(VibeLayout.text());
            user.setText(username);
            addView(user, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));
        }

        String about = null;
        MessagesController mc = MessagesController.getInstance(account);
        if (peer instanceof TLRPC.User) {
            TLRPC.UserFull full = mc.getUserFull(((TLRPC.User) peer).id);
            about = full != null ? full.about : null;
        } else {
            TLRPC.ChatFull full = mc.getChatFull(((TLRPC.Chat) peer).id);
            about = full != null ? full.about : null;
        }

        SpannableStringBuilder line = new SpannableStringBuilder();
        if (!TextUtils.isEmpty(about)) {
            line.append(about);
        } else if (peer instanceof TLRPC.Chat) {
            line.append("Welcome to ");
            int s = line.length();
            line.append(name);
            line.setSpan(new StyleSpan(android.graphics.Typeface.BOLD), s, line.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            line.append(". This is the start of the conversation.");
        } else {
            line.append(secret ? "This is the beginning of your secret chat with " : "This is the beginning of your direct message history with ");
            int s = line.length();
            line.append(name);
            line.setSpan(new StyleSpan(android.graphics.Typeface.BOLD), s, line.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            line.append(".");
        }
        TextView text = new TextView(context);
        text.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        text.setTextColor(VibeLayout.text2());
        text.setLineSpacing(dp(2), 1f);
        text.setText(line);
        addView(text, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 10, 0, 0));
    }
}
