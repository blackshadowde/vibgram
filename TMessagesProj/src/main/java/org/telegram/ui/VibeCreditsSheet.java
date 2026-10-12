package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.HapticFeedbackConstants;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.VibeLayout;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.Components.LayoutHelper;

/** Vibgram: Credits - who made it and the work it is built on. */
@SuppressLint("ViewConstructor")
public class VibeCreditsSheet extends BottomSheet {

    private static final int BLURPLE = 0xFF5865F2;

    // name, what it is, licence / note, link (or null)
    private static final String[][] MADE_BY = {
            {"blackshadowde", "Creator of Vibgram and Vibe", "Design, features and builds", "https://github.com/blackshadowde"},
    };

    private static final String[][] BUILT_ON = {
            {"Telegram for Android", "The app Vibgram is built on", "GNU GPL v2 · © Telegram FZ-LLC", "https://github.com/DrKLO/Telegram"},
            {"Vibe", "The look, colours and layout", "Vibgram's sister app", "https://github.com/blackshadowde/Vibe-flutter"},
            {"GG Sans", "Typeface", "© Discord Inc.", null},
            {"ZXing", "QR codes", "Apache License 2.0", "https://github.com/zxing/zxing"},
            {"Material Icons", "Call icon", "Apache License 2.0 · Google", "https://fonts.google.com/icons"},
            {"UnifiedPush & ntfy", "Optional notifications without Google", "Apache License 2.0", "https://unifiedpush.org"},
    };

    public static void show(BaseFragment fragment) {
        if (fragment == null || fragment.getParentActivity() == null) {
            return;
        }
        fragment.showDialog(new VibeCreditsSheet(fragment));
    }

    private VibeCreditsSheet(BaseFragment fragment) {
        super(fragment.getParentActivity(), false);
        Context context = fragment.getParentActivity();
        setApplyTopPadding(false);
        setBackgroundColor(VibeLayout.bg());

        ScrollView scroll = new ScrollView(context);
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(20), dp(16), dp(20));
        scroll.addView(root);

        TextView title = new TextView(context);
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 26);
        title.setTypeface(VibeLayout.boldFont());
        title.setTextColor(VibeLayout.text());
        title.setText("Credits");
        root.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 4, 0, 4, 0));

        TextView sub = new TextView(context);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        sub.setTextColor(VibeLayout.text2());
        sub.setText("The people and projects behind Vibgram");
        root.addView(sub, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 4, 2, 4, 0));

        addSection(context, root, "MADE BY", MADE_BY);
        addSection(context, root, "BUILT ON", BUILT_ON);

        TextView note = new TextView(context);
        note.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        note.setTextColor(VibeLayout.text2());
        note.setText("Vibgram is free software under the GNU GPL v2, like Telegram for Android. It is not made by or affiliated with Telegram or Discord. Tap a card to open its page.");
        root.addView(note, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 4, 18, 4, 0));

        setCustomView(scroll);
    }

    private void addSection(Context context, LinearLayout root, String label, String[][] rows) {
        TextView l = new TextView(context);
        l.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        l.setTypeface(VibeLayout.boldFont());
        l.setTextColor(VibeLayout.text2());
        l.setLetterSpacing(0.04f);
        l.setText(label);
        root.addView(l, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 4, 20, 4, 6));
        for (String[] r : rows) {
            root.addView(card(context, r), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 8));
        }
    }

    private LinearLayout card(Context context, String[] r) {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(12), dp(16), dp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(VibeLayout.bg());
        bg.setCornerRadius(dp(20));
        bg.setStroke(dp(1), VibeLayout.divider());
        card.setBackground(bg);

        TextView name = new TextView(context);
        name.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        name.setTypeface(VibeLayout.boldFont());
        name.setTextColor(r[3] != null ? BLURPLE : VibeLayout.text());
        name.setText(r[0]);
        card.addView(name);

        TextView what = new TextView(context);
        what.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        what.setTextColor(VibeLayout.text());
        what.setText(r[1]);
        card.addView(what, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));

        if (!TextUtils.isEmpty(r[2])) {
            TextView lic = new TextView(context);
            lic.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            lic.setTextColor(VibeLayout.text2());
            lic.setText(r[2]);
            card.addView(lic, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));
        }
        if (r[3] != null) {
            final String url = r[3];
            card.setOnClickListener(v -> {
                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                VibeUpdates.open(context, url);
            });
        }
        return card;
    }
}
