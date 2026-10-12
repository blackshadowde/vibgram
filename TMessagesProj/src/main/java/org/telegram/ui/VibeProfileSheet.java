package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.VibeLayout;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;

/**
 * Vibgram: profile of a friend, bot, channel or group, like Vibe:
 * the picture fills the top with the name over it, then DISPLAY NAME and
 * USERNAME boxes (copy), an About box and a big Message / Join button.
 */
@SuppressLint("ViewConstructor")
public class VibeProfileSheet extends BottomSheet implements NotificationCenter.NotificationCenterDelegate {

    private static final int BLURPLE = 0xFF5865F2;

    private final BaseFragment fragment;
    private final int account;
    private final TLObject peer;
    private final long dialogId;
    private final LinearLayout aboutBox;
    private final TextView aboutText;
    private final int classGuid = ConnectionsManager.generateClassGuid();

    public static void show(BaseFragment fragment, TLObject peer) {
        if (fragment == null || fragment.getParentActivity() == null || peer == null) {
            return;
        }
        if (peer instanceof TLRPC.User && UserObject.isUserSelf((TLRPC.User) peer)) {
            VibeOwnProfileSheet.show(fragment);
            return;
        }
        new VibeProfileSheet(fragment, peer).show();
    }

    private VibeProfileSheet(BaseFragment fragment, TLObject peer) {
        super(fragment.getParentActivity(), false);
        this.fragment = fragment;
        this.account = fragment.getCurrentAccount();
        this.peer = peer;
        this.dialogId = peer instanceof TLRPC.User ? ((TLRPC.User) peer).id : -((TLRPC.Chat) peer).id;
        Context context = fragment.getParentActivity();
        setApplyTopPadding(false);
        setBackgroundColor(VibeLayout.bg());

        boolean isUser = peer instanceof TLRPC.User;
        String title = isUser ? UserObject.getUserName((TLRPC.User) peer) : ((TLRPC.Chat) peer).title;
        String username = isUser ? UserObject.getPublicUsername((TLRPC.User) peer) : ChatObject.getPublicUsername((TLRPC.Chat) peer);

        ScrollView scroll = new ScrollView(context);
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(root);

        // ---- Hero: the picture fills the top, name and @username over it
        int heroHeight = Math.min((int) (AndroidUtilities.displaySize.x * 0.72f), dp(300));
        FrameLayout hero = new FrameLayout(context);
        BackupImageView photo = new BackupImageView(context);
        photo.setRoundRadius(dp(24), dp(24), 0, 0);
        AvatarDrawable placeholder = new AvatarDrawable();
        placeholder.setInfo(account, peer);
        placeholder.setTextSize(dp(64));
        ImageLocation big = ImageLocation.getForUserOrChat(peer, ImageLocation.TYPE_BIG);
        if (big != null) {
            photo.setImage(big, "800_800", ImageLocation.getForUserOrChat(peer, ImageLocation.TYPE_SMALL), "50_50", placeholder, null, null, 0, peer);
        } else {
            photo.setForUserOrChat(peer, placeholder);
        }
        hero.addView(photo, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        View shade = new View(context);
        GradientDrawable shadeBg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x00000000, 0x00000000, 0xB0000000});
        shade.setBackground(shadeBg);
        hero.addView(shade, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        ImageView back = new ImageView(context);
        back.setScaleType(ImageView.ScaleType.CENTER);
        back.setImageResource(R.drawable.ic_ab_back);
        back.setColorFilter(new PorterDuffColorFilter(0xFFFFFFFF, PorterDuff.Mode.SRC_IN));
        GradientDrawable backBg = new GradientDrawable();
        backBg.setShape(GradientDrawable.OVAL);
        backBg.setColor(0x99202225);
        back.setBackground(backBg);
        back.setOnClickListener(v -> dismiss());
        hero.addView(back, LayoutHelper.createFrame(40, 40, Gravity.LEFT | Gravity.TOP, 12, 14, 0, 0));

        LinearLayout heroText = new LinearLayout(context);
        heroText.setOrientation(LinearLayout.VERTICAL);
        TextView name = new TextView(context);
        name.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 26);
        name.setTypeface(VibeLayout.boldFont());
        name.setTextColor(0xFFFFFFFF);
        name.setShadowLayer(dp(4), 0, dp(1), 0x80000000);
        name.setMaxLines(2);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setText(title);
        heroText.addView(name);
        if (!TextUtils.isEmpty(username)) {
            TextView user = new TextView(context);
            user.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            user.setTextColor(0xE6FFFFFF);
            user.setShadowLayer(dp(4), 0, dp(1), 0x80000000);
            user.setText("@" + username);
            heroText.addView(user);
        }
        hero.addView(heroText, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.BOTTOM, 16, 0, 16, 12));
        root.addView(hero, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, heroHeight));

        // ---- Fields
        root.addView(label(context, isUser ? "DISPLAY NAME" : (ChatObject.isChannelAndNotMegaGroup((TLRPC.Chat) peer) ? "CHANNEL NAME" : "GROUP NAME")),
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 14, 16, 6));
        root.addView(box(context, title, null), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 0, 16, 0));

        if (!TextUtils.isEmpty(username)) {
            root.addView(label(context, isUser ? "USERNAME" : "LINK"), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 14, 16, 6));
            final String copyText = isUser ? "@" + username : "https://t.me/" + username;
            root.addView(box(context, copyText, copyText), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 0, 16, 0));
        }

        TextView aboutLabel = label(context, isUser ? "ABOUT ME" : "DESCRIPTION");
        aboutBox = new LinearLayout(context);
        aboutBox.setOrientation(LinearLayout.VERTICAL);
        aboutBox.addView(aboutLabel, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 14, 0, 6));
        aboutText = new TextView(context);
        aboutText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        aboutText.setTextColor(VibeLayout.text());
        aboutText.setLineSpacing(dp(2), 1f);
        aboutText.setPadding(dp(16), dp(12), dp(16), dp(12));
        aboutText.setBackground(boxBg());
        aboutText.setTextIsSelectable(true);
        aboutBox.addView(aboutText);
        aboutBox.setVisibility(View.GONE);
        root.addView(aboutBox, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 0, 16, 0));

        // ---- Big action button
        TextView primary = new TextView(context);
        primary.setGravity(Gravity.CENTER);
        primary.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        primary.setTypeface(VibeLayout.boldFont());
        primary.setTextColor(0xFFFFFFFF);
        GradientDrawable primaryBg = new GradientDrawable();
        primaryBg.setColor(BLURPLE);
        primaryBg.setCornerRadius(dp(24));
        primary.setBackground(primaryBg);
        boolean joined = true;
        if (!isUser) {
            TLRPC.Chat c = (TLRPC.Chat) peer;
            joined = !ChatObject.isNotInChat(c);
            primary.setText(joined ? "Open" : (ChatObject.isChannelAndNotMegaGroup(c) ? "Join channel" : "Join group"));
        } else {
            primary.setText(((TLRPC.User) peer).bot ? "Start chat" : "Message");
        }
        final boolean isJoined = joined;
        primary.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            if (!isJoined && peer instanceof TLRPC.Chat) {
                TLRPC.User me = UserConfig.getInstance(account).getCurrentUser();
                MessagesController.getInstance(account).addUserToChat(((TLRPC.Chat) peer).id, me, 0, null, fragment, () -> AndroidUtilities.runOnUIThread(this::openChat));
                dismiss();
            } else {
                openChat();
            }
        });
        root.addView(primary, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 16, 20, 16, 0));

        TextView more = new TextView(context);
        more.setGravity(Gravity.CENTER);
        more.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        more.setTypeface(VibeLayout.boldFont());
        more.setTextColor(VibeLayout.text2());
        more.setText("# more-info");
        more.setOnClickListener(v -> {
            dismiss();
            Bundle args = new Bundle();
            if (dialogId > 0) {
                args.putLong("user_id", dialogId);
            } else {
                args.putLong("chat_id", -dialogId);
            }
            fragment.presentFragment(new ProfileActivity(args));
        });
        root.addView(more, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 44, 16, 4, 16, 12));

        setCustomView(scroll);
        loadAbout();
    }

    private GradientDrawable boxBg() {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(VibeLayout.bg());
        bg.setCornerRadius(dp(18));
        bg.setStroke(dp(1), VibeLayout.divider());
        return bg;
    }

    private TextView label(Context context, String text) {
        TextView tv = new TextView(context);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        tv.setTypeface(VibeLayout.boldFont());
        tv.setLetterSpacing(0.06f);
        tv.setTextColor(VibeLayout.text2());
        tv.setText(text);
        return tv;
    }

    /** Rounded outlined box with text; tapping copies when copyText is set. */
    private View box(Context context, String text, String copyText) {
        FrameLayout box = new FrameLayout(context);
        box.setBackground(boxBg());
        TextView tv = new TextView(context);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        tv.setTextColor(copyText != null ? VibeLayout.text2() : VibeLayout.text());
        tv.setSingleLine(true);
        tv.setEllipsize(TextUtils.TruncateAt.END);
        tv.setText(text);
        box.addView(tv, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL | Gravity.LEFT, 16, 13, copyText != null ? 52 : 16, 13));
        if (copyText != null) {
            ImageView copy = new ImageView(context);
            copy.setScaleType(ImageView.ScaleType.CENTER);
            copy.setImageResource(R.drawable.msg_copy);
            copy.setColorFilter(new PorterDuffColorFilter(VibeLayout.text(), PorterDuff.Mode.SRC_IN));
            box.addView(copy, LayoutHelper.createFrame(40, 40, Gravity.RIGHT | Gravity.CENTER_VERTICAL, 0, 0, 6, 0));
            box.setOnClickListener(v -> {
                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                AndroidUtilities.addToClipboard(copyText);
                BulletinFactory.of(getContainer(), null).createCopyBulletin("Copied").show();
            });
        }
        return box;
    }

    private void loadAbout() {
        MessagesController mc = MessagesController.getInstance(account);
        if (peer instanceof TLRPC.User) {
            TLRPC.UserFull full = mc.getUserFull(((TLRPC.User) peer).id);
            if (full != null) {
                setAbout(full.about);
            } else {
                mc.loadFullUser((TLRPC.User) peer, classGuid, false, uf -> AndroidUtilities.runOnUIThread(() -> {
                    if (uf != null) {
                        setAbout(uf.about);
                    }
                }));
            }
        } else {
            TLRPC.Chat c = (TLRPC.Chat) peer;
            TLRPC.ChatFull full = mc.getChatFull(c.id);
            if (full != null) {
                setAbout(full.about);
            } else {
                NotificationCenter.getInstance(account).addObserver(this, NotificationCenter.chatInfoDidLoad);
                mc.loadFullChat(c.id, classGuid, false);
            }
        }
    }

    private void setAbout(String about) {
        if (TextUtils.isEmpty(about)) {
            aboutBox.setVisibility(View.GONE);
        } else {
            aboutText.setText(about);
            aboutBox.setVisibility(View.VISIBLE);
        }
    }

    @Override
    public void didReceivedNotification(int id, int acc, Object... args) {
        if (id == NotificationCenter.chatInfoDidLoad && args.length > 0 && args[0] instanceof TLRPC.ChatFull) {
            TLRPC.ChatFull full = (TLRPC.ChatFull) args[0];
            if (peer instanceof TLRPC.Chat && full.id == ((TLRPC.Chat) peer).id) {
                setAbout(full.about);
            }
        }
    }

    @Override
    public void dismiss() {
        NotificationCenter.getInstance(account).removeObserver(this, NotificationCenter.chatInfoDidLoad);
        super.dismiss();
    }

    private void openChat() {
        dismiss();
        if (fragment instanceof ChatActivity && ((ChatActivity) fragment).getDialogId() == dialogId) {
            return;
        }
        Bundle args = new Bundle();
        if (DialogObject.isUserDialog(dialogId)) {
            args.putLong("user_id", dialogId);
        } else {
            args.putLong("chat_id", -dialogId);
        }
        fragment.presentFragment(new ChatActivity(args));
    }
}
