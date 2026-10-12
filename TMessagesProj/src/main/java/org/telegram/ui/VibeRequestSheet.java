package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.VibeLayout;
import org.telegram.messenger.VibeRequests;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;

/**
 * Vibgram: a message request from someone outside your contacts.
 * You see who it is and a preview without opening the chat (so they do not
 * get a "read" tick), then Accept, Ignore or Block.
 */
@SuppressLint("ViewConstructor")
public class VibeRequestSheet extends BottomSheet {

    private static final int BLURPLE = 0xFF5865F2;
    private static final int DANGER = 0xFFF87171;

    public static void show(BaseFragment fragment, long did, Runnable onChanged) {
        if (fragment == null || fragment.getParentActivity() == null) {
            return;
        }
        TLRPC.User user = MessagesController.getInstance(fragment.getCurrentAccount()).getUser(did);
        if (user == null) {
            return;
        }
        fragment.showDialog(new VibeRequestSheet(fragment, user, onChanged));
    }

    private VibeRequestSheet(BaseFragment fragment, TLRPC.User user, Runnable onChanged) {
        super(fragment.getParentActivity(), false);
        Context context = fragment.getParentActivity();
        int account = fragment.getCurrentAccount();
        long did = user.id;
        setApplyTopPadding(false);
        setBackgroundColor(VibeLayout.bg());

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(20), dp(16), dp(16));

        BackupImageView avatar = new BackupImageView(context);
        avatar.setRoundRadius(dp(36));
        AvatarDrawable ad = new AvatarDrawable();
        ad.setInfo(account, user);
        avatar.setForUserOrChat(user, ad);
        root.addView(avatar, LayoutHelper.createLinear(72, 72, Gravity.LEFT));

        TextView name = new TextView(context);
        name.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 22);
        name.setTypeface(VibeLayout.boldFont());
        name.setTextColor(VibeLayout.text());
        name.setText(UserObject.getUserName(user));
        root.addView(name, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 12, 0, 0));

        String username = UserObject.getPublicUsername(user);
        TextView sub = new TextView(context);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        sub.setTextColor(VibeLayout.text2());
        sub.setText((!TextUtils.isEmpty(username) ? "@" + username + " · " : "") + "Not in your contacts");
        root.addView(sub, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));

        TextView label = new TextView(context);
        label.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        label.setTypeface(VibeLayout.boldFont());
        label.setTextColor(VibeLayout.text2());
        label.setLetterSpacing(0.04f);
        label.setText("WANTS TO MESSAGE YOU");
        root.addView(label, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 18, 0, 6));

        TextView preview = new TextView(context);
        preview.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        preview.setTextColor(VibeLayout.text());
        preview.setMaxLines(4);
        preview.setEllipsize(TextUtils.TruncateAt.END);
        preview.setPadding(dp(14), dp(12), dp(14), dp(12));
        preview.setText(VibeRequests.preview(account, did));
        GradientDrawable previewBg = new GradientDrawable();
        previewBg.setColor(VibeLayout.card());
        previewBg.setCornerRadius(dp(18));
        preview.setBackground(previewBg);
        root.addView(preview, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        TextView note = new TextView(context);
        note.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        note.setTextColor(VibeLayout.text2());
        note.setText("They won't know you've seen this unless you accept.");
        root.addView(note, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 2, 8, 2, 0));

        LinearLayout buttons = new LinearLayout(context);
        buttons.setOrientation(LinearLayout.HORIZONTAL);

        TextView ignore = button(context, "Ignore", VibeLayout.text(), 0, VibeLayout.divider());
        ignore.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            MessagesController.getInstance(account).deleteDialog(did, 0);
            dismiss();
            if (onChanged != null) {
                onChanged.run();
            }
        });
        buttons.addView(ignore, LayoutHelper.createLinear(0, 48, 1f, 0, 0, 5, 0));

        TextView accept = button(context, "Accept", 0xFFFFFFFF, BLURPLE, 0);
        accept.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            VibeRequests.accept(account, did);
            dismiss();
            if (onChanged != null) {
                onChanged.run();
            }
            Bundle args = new Bundle();
            args.putLong("user_id", did);
            fragment.presentFragment(new ChatActivity(args));
        });
        buttons.addView(accept, LayoutHelper.createLinear(0, 48, 1f, 5, 0, 0, 0));
        root.addView(buttons, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 0, 20, 0, 0));

        TextView block = button(context, "Block and delete", DANGER, 0, 0);
        block.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            MessagesController mc = MessagesController.getInstance(account);
            mc.blockPeer(did);
            mc.deleteDialog(did, 0);
            dismiss();
            if (onChanged != null) {
                onChanged.run();
            }
            BulletinFactory.of(fragment).createSimpleBulletin(org.telegram.messenger.R.raw.ic_ban, UserObject.getFirstName(user) + " is blocked").show();
        });
        root.addView(block, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 44, 0, 6, 0, 0));

        setCustomView(root);
    }

    private static TextView button(Context context, String text, int textColor, int fill, int stroke) {
        TextView b = new TextView(context);
        b.setGravity(Gravity.CENTER);
        b.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        b.setTypeface(VibeLayout.boldFont());
        b.setTextColor(textColor);
        b.setText(text);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(fill);
        bg.setCornerRadius(dp(24));
        if (stroke != 0) {
            bg.setStroke(dp(1), stroke);
        }
        b.setBackground(bg);
        return b;
    }
}
