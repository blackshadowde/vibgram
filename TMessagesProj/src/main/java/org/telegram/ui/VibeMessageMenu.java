package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
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

import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MediaDataController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.VibeLayout;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.LayoutHelper;

import java.util.ArrayList;
import java.util.List;

/**
 * Vibgram: Vibe's long-press menu. A row of animated Telegram reactions,
 * a preview of the message, then the actions (Reply, Edit, Forward, Copy, Pin,
 * Save, Delete). "More" opens Telegram's full reaction picker.
 */
@SuppressLint("ViewConstructor")
public class VibeMessageMenu extends BottomSheet {

    public static class Action {
        public final int option;
        public final int icon;
        public final String label;
        public final boolean danger;

        public Action(int option, int icon, String label, boolean danger) {
            this.option = option;
            this.icon = icon;
            this.label = label;
            this.danger = danger;
        }
    }

    public interface Delegate {
        void onAction(int option);

        void onReaction(String emoticon);

        void onMoreReactions();
    }

    private static final String[] PREFERRED = {"❤", "👍", "🔥", "😂", "🎉", "😁", "😮"};

    private final Delegate delegate;

    public static void show(BaseFragment fragment, MessageObject message, TLObject sender, boolean canReact, List<Action> actions, Delegate delegate) {
        if (fragment == null || fragment.getParentActivity() == null) {
            return;
        }
        fragment.showDialog(new VibeMessageMenu(fragment, message, sender, canReact, actions, delegate));
    }

    private VibeMessageMenu(BaseFragment fragment, MessageObject message, TLObject sender, boolean canReact, List<Action> actions, Delegate delegate) {
        super(fragment.getParentActivity(), false);
        this.delegate = delegate;
        Context context = fragment.getParentActivity();
        int account = fragment.getCurrentAccount();
        setApplyTopPadding(false);
        setBackgroundColor(Theme.isCurrentThemeDark() ? 0xFF29282D : 0xFFEBEDEF);

        ScrollView scroll = new ScrollView(context);
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(0, dp(10), 0, dp(12));
        scroll.addView(root);

        View handle = new View(context);
        GradientDrawable handleBg = new GradientDrawable();
        handleBg.setColor(VibeLayout.text2());
        handleBg.setCornerRadius(dp(2));
        handle.setBackground(handleBg);
        handle.setAlpha(0.5f);
        root.addView(handle, LayoutHelper.createLinear(36, 4, Gravity.CENTER_HORIZONTAL, 0, 0, 0, 12));

        // ---- animated reactions
        if (canReact) {
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(8), dp(8), dp(8), dp(8));
            row.setBackground(innerBg());
            List<TLRPC.TL_availableReaction> all = MediaDataController.getInstance(account).getReactionsList();
            ArrayList<TLRPC.TL_availableReaction> pick = new ArrayList<>();
            for (String want : PREFERRED) {
                for (TLRPC.TL_availableReaction r : all) {
                    if (r != null && !r.inactive && want.equals(r.reaction) && !pick.contains(r)) {
                        pick.add(r);
                        break;
                    }
                }
                if (pick.size() >= 5) {
                    break;
                }
            }
            for (int i = 0; i < all.size() && pick.size() < 5; i++) {
                TLRPC.TL_availableReaction r = all.get(i);
                if (r != null && !r.inactive && !pick.contains(r)) {
                    pick.add(r);
                }
            }
            for (TLRPC.TL_availableReaction r : pick) {
                FrameLayout cell = new FrameLayout(context);
                GradientDrawable circle = new GradientDrawable();
                circle.setShape(GradientDrawable.OVAL);
                circle.setColor(Theme.isCurrentThemeDark() ? 0xFF29282D : 0xFFFFFFFF);
                cell.setBackground(circle);
                if (r.select_animation != null) {
                    BackupImageView img = new BackupImageView(context);
                    img.getImageReceiver().setAutoRepeat(1);
                    img.getImageReceiver().setImage(ImageLocation.getForDocument(r.select_animation), "60_60_pcache",
                            (ImageLocation) null, (String) null, (Drawable) null, 0, "tgs", r, 0);
                    cell.addView(img, LayoutHelper.createFrame(34, 34, Gravity.CENTER));
                } else {
                    TextView t = new TextView(context);
                    t.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 23);
                    t.setText(r.reaction);
                    t.setGravity(Gravity.CENTER);
                    cell.addView(t, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
                }
                final String emoticon = r.reaction;
                cell.setOnClickListener(v -> {
                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                    dismiss();
                    delegate.onReaction(emoticon);
                });
                row.addView(cell, LayoutHelper.createLinear(0, 48, 1f, 4, 0, 4, 0));
            }
            FrameLayout more = new FrameLayout(context);
            GradientDrawable moreBg = new GradientDrawable();
            moreBg.setShape(GradientDrawable.OVAL);
            moreBg.setColor(Theme.isCurrentThemeDark() ? 0xFF29282D : 0xFFFFFFFF);
            more.setBackground(moreBg);
            ImageView moreIcon = new ImageView(context);
            moreIcon.setScaleType(ImageView.ScaleType.CENTER);
            moreIcon.setImageResource(R.drawable.msg_emoji_smiles);
            moreIcon.setColorFilter(new PorterDuffColorFilter(VibeLayout.text2(), PorterDuff.Mode.SRC_IN));
            more.addView(moreIcon, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
            more.setOnClickListener(v -> {
                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                dismiss();
                delegate.onMoreReactions();
            });
            row.addView(more, LayoutHelper.createLinear(0, 48, 1f, 4, 0, 4, 0));
            root.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 0, 16, 12));
        }

        // ---- message preview
        LinearLayout preview = new LinearLayout(context);
        preview.setOrientation(LinearLayout.HORIZONTAL);
        preview.setPadding(dp(14), dp(10), dp(14), dp(10));
        preview.setBackground(innerBg());
        BackupImageView avatar = new BackupImageView(context);
        avatar.setRoundRadius(dp(19));
        if (sender != null) {
            AvatarDrawable ad = new AvatarDrawable();
            ad.setInfo(account, sender);
            avatar.setForUserOrChat(sender, ad);
        }
        preview.addView(avatar, LayoutHelper.createLinear(38, 38, Gravity.TOP));
        LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView name = new TextView(context);
        name.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        name.setTypeface(VibeLayout.boldFont());
        name.setTextColor(VibeLayout.text());
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        if (sender instanceof TLRPC.User) {
            name.setText(UserObject.getUserName((TLRPC.User) sender));
        } else if (sender instanceof TLRPC.Chat) {
            name.setText(((TLRPC.Chat) sender).title);
        }
        texts.addView(name);
        TextView body = new TextView(context);
        body.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        body.setTextColor(VibeLayout.text());
        body.setMaxLines(2);
        body.setEllipsize(TextUtils.TruncateAt.END);
        body.setText(previewText(message));
        texts.addView(body);
        preview.addView(texts, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 14, 0, 8, 0));
        TextView time = new TextView(context);
        time.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        time.setTextColor(VibeLayout.text2());
        time.setText(LocaleController.getInstance().getFormatterDay().format((long) message.messageOwner.date * 1000));
        preview.addView(time, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));
        root.addView(preview, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 0, 16, 8));

        // ---- actions
        boolean dividerAdded = false;
        for (Action a : actions) {
            if (a.danger && !dividerAdded) {
                View line = new View(context);
                line.setBackgroundColor(VibeLayout.divider());
                root.addView(line, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 1, 16, 4, 16, 4));
                dividerAdded = true;
            }
            root.addView(actionRow(context, a), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48));
        }
        setCustomView(scroll);
    }

    private static CharSequence previewText(MessageObject m) {
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
        if (m.getDocument() != null && m.type == MessageObject.TYPE_FILE) {
            String fn = m.getDocumentName();
            return TextUtils.isEmpty(fn) ? "File" : fn;
        }
        return m.messageText != null ? m.messageText : "";
    }

    private GradientDrawable innerBg() {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(VibeLayout.rail());
        bg.setCornerRadius(dp(24));
        bg.setStroke(dp(1), VibeLayout.divider());
        return bg;
    }

    private View actionRow(Context context, Action a) {
        FrameLayout row = new FrameLayout(context);
        int color = a.danger ? 0xFFF87171 : VibeLayout.text();
        ImageView icon = new ImageView(context);
        icon.setScaleType(ImageView.ScaleType.CENTER);
        icon.setImageResource(a.icon);
        icon.setColorFilter(new PorterDuffColorFilter(a.danger ? color : VibeLayout.text2(), PorterDuff.Mode.SRC_IN));
        row.addView(icon, LayoutHelper.createFrame(24, 24, Gravity.LEFT | Gravity.CENTER_VERTICAL, 20, 0, 0, 0));
        TextView label = new TextView(context);
        label.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        label.setTextColor(color);
        label.setText(a.label);
        row.addView(label, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.CENTER_VERTICAL, 60, 0, 16, 0));
        row.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), Theme.RIPPLE_MASK_ALL));
        row.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            dismiss();
            delegate.onAction(a.option);
        });
        return row;
    }
}
