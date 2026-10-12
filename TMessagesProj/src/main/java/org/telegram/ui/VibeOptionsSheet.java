package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
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
 * Vibgram: Discord-style options sheet that slides up from the bottom.
 * A header (picture + name) and rounded groups of options with thin dividers.
 * Also holds the shared pieces used by the message menu.
 */
@SuppressLint("ViewConstructor")
public class VibeOptionsSheet extends BottomSheet {

    public static final int DANGER = 0xFFF87171;

    public static class Opt {
        public final int icon;
        public final CharSequence label;
        public final boolean danger;
        public final Runnable run;

        public Opt(int icon, CharSequence label, boolean danger, Runnable run) {
            this.icon = icon;
            this.label = label;
            this.danger = danger;
            this.run = run;
        }
    }

    public static int sheetBg() {
        return Theme.isCurrentThemeDark() ? 0xFF1E1D22 : 0xFFF2F3F5;
    }

    public static int cardBg() {
        return Theme.isCurrentThemeDark() ? 0xFF29282D : 0xFFFFFFFF;
    }

    public static void show(BaseFragment fragment, int account, TLObject peer, CharSequence title, List<List<Opt>> groups) {
        if (fragment == null || fragment.getParentActivity() == null) {
            return;
        }
        fragment.showDialog(new VibeOptionsSheet(fragment, account, peer, title, groups));
    }

    private VibeOptionsSheet(BaseFragment fragment, int account, TLObject peer, CharSequence title, List<List<Opt>> groups) {
        super(fragment.getParentActivity(), false);
        Context context = fragment.getParentActivity();
        setApplyTopPadding(false);
        setBackgroundColor(sheetBg());

        ScrollView scroll = new ScrollView(context);
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(10), dp(16), dp(16));
        scroll.addView(root);

        root.addView(handle(context), LayoutHelper.createLinear(36, 4, Gravity.CENTER_HORIZONTAL, 0, 0, 0, 14));

        // ---- header: picture + name
        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        if (peer != null) {
            BackupImageView avatar = new BackupImageView(context);
            boolean square = peer instanceof TLRPC.Chat;
            avatar.setRoundRadius(square ? dp(14) : dp(24));
            AvatarDrawable ad = new AvatarDrawable();
            ad.setInfo(account, peer);
            if (square) {
                ad.setRoundRadius(dp(14));
            }
            avatar.setForUserOrChat(peer, ad);
            header.addView(avatar, LayoutHelper.createLinear(48, 48));
        }
        TextView name = new TextView(context);
        name.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        name.setTypeface(VibeLayout.boldFont());
        name.setTextColor(VibeLayout.text());
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setText(title);
        header.addView(name, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, peer != null ? 14 : 4, 0, 0, 0));
        root.addView(header, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 16));

        for (List<Opt> g : groups) {
            if (g == null || g.isEmpty()) {
                continue;
            }
            root.addView(group(context, g, this::dismiss), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 12));
        }
        setCustomView(scroll);
    }

    public static View handle(Context context) {
        View handle = new View(context);
        GradientDrawable handleBg = new GradientDrawable();
        handleBg.setColor(VibeLayout.text2());
        handleBg.setCornerRadius(dp(2));
        handle.setBackground(handleBg);
        handle.setAlpha(0.5f);
        return handle;
    }

    /** A rounded card of option rows with dividers between them. */
    public static LinearLayout group(Context context, List<Opt> rows, Runnable dismiss) {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(cardBg());
        bg.setCornerRadius(dp(20));
        card.setBackground(bg);
        card.setClipToOutline(true);
        for (int i = 0; i < rows.size(); i++) {
            if (i > 0) {
                View line = new View(context);
                line.setBackgroundColor(VibeLayout.divider());
                card.addView(line, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 1, 58, 0, 0, 0));
            }
            card.addView(row(context, rows.get(i), dismiss), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 56));
        }
        return card;
    }

    public static View row(Context context, Opt o, Runnable dismiss) {
        FrameLayout row = new FrameLayout(context);
        int color = o.danger ? DANGER : VibeLayout.text();
        ImageView icon = new ImageView(context);
        icon.setScaleType(ImageView.ScaleType.CENTER);
        icon.setImageResource(o.icon);
        icon.setColorFilter(new PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN));
        row.addView(icon, LayoutHelper.createFrame(24, 24, Gravity.LEFT | Gravity.CENTER_VERTICAL, 18, 0, 0, 0));
        TextView label = new TextView(context);
        label.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        label.setTextColor(color);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setText(o.label);
        row.addView(label, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.CENTER_VERTICAL, 58, 0, 16, 0));
        row.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), Theme.RIPPLE_MASK_ALL));
        row.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            if (dismiss != null) {
                dismiss.run();
            }
            if (o.run != null) {
                o.run.run();
            }
        });
        return row;
    }

    public static List<Opt> list(Opt... opts) {
        ArrayList<Opt> out = new ArrayList<>();
        for (Opt o : opts) {
            if (o != null) {
                out.add(o);
            }
        }
        return out;
    }
}
