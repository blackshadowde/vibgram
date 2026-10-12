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
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.LocaleController;
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
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.LayoutHelper;

/**
 * Vibgram: profile card that slides up from the bottom, like Vibe / Discord.
 * Banner, round picture, name, @username, a big action button
 * (Message / Join / Open) and an About card.
 */
@SuppressLint("ViewConstructor")
public class VibeProfileSheet extends BottomSheet implements NotificationCenter.NotificationCenterDelegate {

    private static final int BLURPLE = 0xFF5865F2;

    private final BaseFragment fragment;
    private final int account;
    private final TLObject peer;
    private final long dialogId;
    private final TextView aboutText;
    private final LinearLayout aboutCard;
    private final int classGuid = ConnectionsManager.generateClassGuid();

    public static void show(BaseFragment fragment, TLObject peer) {
        if (fragment == null || fragment.getParentActivity() == null || peer == null) {
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

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);

        // Banner + picture
        FrameLayout header = new FrameLayout(context);
        View banner = new View(context);
        GradientDrawable bannerBg = new GradientDrawable();
        bannerBg.setColor(Theme.isCurrentThemeDark() ? 0xFF1F2624 : 0xFFD9DEE3);
        bannerBg.setCornerRadii(new float[]{dp(12), dp(12), dp(12), dp(12), 0, 0, 0, 0});
        banner.setBackground(bannerBg);
        header.addView(banner, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 120, Gravity.TOP));
        View handle = new View(context);
        GradientDrawable handleBg = new GradientDrawable();
        handleBg.setColor(0x99FFFFFF);
        handleBg.setCornerRadius(dp(2));
        handle.setBackground(handleBg);
        header.addView(handle, LayoutHelper.createFrame(36, 4, Gravity.TOP | Gravity.CENTER_HORIZONTAL, 0, 8, 0, 0));

        FrameLayout ring = new FrameLayout(context);
        GradientDrawable ringBg = new GradientDrawable();
        ringBg.setShape(GradientDrawable.OVAL);
        ringBg.setColor(VibeLayout.bg());
        ring.setBackground(ringBg);
        BackupImageView avatar = new BackupImageView(context);
        avatar.setRoundRadius(dp(48));
        AvatarDrawable avatarDrawable = new AvatarDrawable();
        avatarDrawable.setInfo(account, peer);
        avatar.setForUserOrChat(peer, avatarDrawable);
        ring.addView(avatar, LayoutHelper.createFrame(96, 96, Gravity.CENTER));
        if (peer instanceof TLRPC.User) {
            TLRPC.User u = (TLRPC.User) peer;
            boolean online = u.status != null && u.status.expires > ConnectionsManager.getInstance(account).getCurrentTime();
            View dot = new View(context);
            GradientDrawable dotBg = new GradientDrawable();
            dotBg.setShape(GradientDrawable.OVAL);
            dotBg.setColor(online ? 0xFF23A55A : 0xFF80848E);
            dotBg.setStroke(dp(4), VibeLayout.bg());
            dot.setBackground(dotBg);
            ring.addView(dot, LayoutHelper.createFrame(26, 26, Gravity.RIGHT | Gravity.BOTTOM, 0, 0, 4, 4));
        }
        header.addView(ring, LayoutHelper.createFrame(108, 108, Gravity.LEFT | Gravity.TOP, 14, 66, 0, 0));
        root.addView(header, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 178));

        // Name and @username
        TextView name = new TextView(context);
        name.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 24);
        name.setTypeface(VibeLayout.boldFont());
        name.setTextColor(VibeLayout.text());
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setText(peer instanceof TLRPC.User ? UserObject.getUserName((TLRPC.User) peer) : ((TLRPC.Chat) peer).title);
        root.addView(name, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 4, 16, 0));

        String username = peer instanceof TLRPC.User ? UserObject.getPublicUsername((TLRPC.User) peer) : ChatObject.getPublicUsername((TLRPC.Chat) peer);
        TextView sub = new TextView(context);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        sub.setTextColor(VibeLayout.text2());
        sub.setSingleLine(true);
        sub.setEllipsize(TextUtils.TruncateAt.END);
        if (peer instanceof TLRPC.User) {
            TLRPC.User u = (TLRPC.User) peer;
            String status = LocaleController.formatUserStatus(account, u);
            sub.setText(!TextUtils.isEmpty(username) ? "@" + username + "  ·  " + status : status);
        } else {
            TLRPC.Chat c = (TLRPC.Chat) peer;
            String members = c.participants_count > 0 ? LocaleController.formatPluralString(ChatObject.isChannelAndNotMegaGroup(c) ? "Subscribers" : "Members", c.participants_count) : "";
            sub.setText(!TextUtils.isEmpty(username) ? "@" + username + (members.isEmpty() ? "" : "  ·  " + members) : members);
        }
        root.addView(sub, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 2, 16, 0));

        // Buttons: big action + info square
        LinearLayout buttons = new LinearLayout(context);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        TextView primary = new TextView(context);
        primary.setGravity(Gravity.CENTER);
        primary.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        primary.setTypeface(VibeLayout.boldFont());
        primary.setTextColor(0xFFFFFFFF);
        GradientDrawable primaryBg = new GradientDrawable();
        primaryBg.setColor(BLURPLE);
        primaryBg.setCornerRadius(dp(10));
        primary.setBackground(primaryBg);
        boolean joined = true;
        if (peer instanceof TLRPC.Chat) {
            TLRPC.Chat c = (TLRPC.Chat) peer;
            joined = !ChatObject.isNotInChat(c);
            primary.setText(joined ? "Open" : (ChatObject.isChannelAndNotMegaGroup(c) ? "Join channel" : "Join group"));
        } else {
            TLRPC.User u = (TLRPC.User) peer;
            primary.setText(u.bot ? "Start chat" : "Message");
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
        buttons.addView(primary, LayoutHelper.createLinear(0, 48, 1f));

        ImageView info = new ImageView(context);
        info.setScaleType(ImageView.ScaleType.CENTER);
        info.setImageResource(R.drawable.msg_info);
        info.setColorFilter(new PorterDuffColorFilter(VibeLayout.text(), PorterDuff.Mode.SRC_IN));
        GradientDrawable infoBg = new GradientDrawable();
        infoBg.setColor(VibeLayout.card());
        infoBg.setCornerRadius(dp(10));
        info.setBackground(infoBg);
        info.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            dismiss();
            Bundle args = new Bundle();
            if (dialogId > 0) {
                args.putLong("user_id", dialogId);
            } else {
                args.putLong("chat_id", -dialogId);
            }
            fragment.presentFragment(new ProfileActivity(args));
        });
        buttons.addView(info, LayoutHelper.createLinear(56, 48, 10, 0, 0, 0));
        root.addView(buttons, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 16, 18, 16, 0));

        // About card
        aboutCard = new LinearLayout(context);
        aboutCard.setOrientation(LinearLayout.VERTICAL);
        aboutCard.setPadding(dp(16), dp(14), dp(16), dp(14));
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(VibeLayout.card());
        cardBg.setCornerRadius(dp(14));
        aboutCard.setBackground(cardBg);
        TextView aboutTitle = new TextView(context);
        aboutTitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        aboutTitle.setTypeface(VibeLayout.boldFont());
        aboutTitle.setTextColor(VibeLayout.text());
        aboutTitle.setText(peer instanceof TLRPC.User ? "About me" : "About");
        aboutCard.addView(aboutTitle);
        aboutText = new TextView(context);
        aboutText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        aboutText.setTextColor(VibeLayout.text2());
        aboutText.setLineSpacing(dp(2), 1f);
        aboutCard.addView(aboutText, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 6, 0, 0));
        aboutCard.setVisibility(View.GONE);
        root.addView(aboutCard, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 18, 16, 0));

        View bottomSpace = new View(context);
        root.addView(bottomSpace, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 24));

        setCustomView(root);
        loadAbout();
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
            aboutCard.setVisibility(View.GONE);
        } else {
            aboutText.setText(about);
            aboutCard.setVisibility(View.VISIBLE);
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
            return; // already in this chat
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
