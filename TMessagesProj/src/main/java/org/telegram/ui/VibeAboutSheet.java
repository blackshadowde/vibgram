package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.BuildVars;
import org.telegram.messenger.R;
import org.telegram.messenger.VibeLayout;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.Components.LayoutHelper;

/** Vibgram: About page - logo, build number, update check, source code and licence. */
@SuppressLint("ViewConstructor")
public class VibeAboutSheet extends BottomSheet {

    private static final int BLURPLE = 0xFF5865F2;

    public static void show(BaseFragment fragment) {
        if (fragment == null || fragment.getParentActivity() == null) {
            return;
        }
        fragment.showDialog(new VibeAboutSheet(fragment));
    }

    private VibeAboutSheet(BaseFragment fragment) {
        super(fragment.getParentActivity(), false);
        Context context = fragment.getParentActivity();
        setApplyTopPadding(false);
        setBackgroundColor(VibeLayout.bg());

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(20), dp(8), dp(20), dp(18));

        ImageView logo = new ImageView(context);
        logo.setScaleType(ImageView.ScaleType.CENTER_CROP);
        logo.setImageResource(R.drawable.vibe_splash_icon);
        root.addView(logo, LayoutHelper.createLinear(180, 140, Gravity.CENTER_HORIZONTAL));

        TextView name = new TextView(context);
        name.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 28);
        name.setTypeface(VibeLayout.boldFont());
        name.setTextColor(VibeLayout.text());
        name.setGravity(Gravity.CENTER);
        name.setText("Vibgram");
        root.addView(name, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        TextView tag = new TextView(context);
        tag.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        tag.setTextColor(VibeLayout.text2());
        tag.setGravity(Gravity.CENTER);
        tag.setText("A Telegram fork with better privacy and security");
        root.addView(tag, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));

        int build = VibeUpdates.currentBuild();
        TextView version = new TextView(context);
        version.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        version.setTextColor(VibeLayout.text2());
        version.setGravity(Gravity.CENTER);
        version.setPadding(dp(12), dp(5), dp(12), dp(5));
        version.setText((build > 0 ? "Build " + build : "Test build") + "  ·  Telegram " + BuildVars.BUILD_VERSION_STRING);
        GradientDrawable vBg = new GradientDrawable();
        vBg.setColor(VibeLayout.card());
        vBg.setCornerRadius(dp(14));
        version.setBackground(vBg);
        root.addView(version, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 12, 0, 0));

        TextView status = new TextView(context);
        status.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        status.setTextColor(VibeLayout.text2());
        status.setGravity(Gravity.CENTER);
        root.addView(status, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 14, 0, 0));

        TextView check = button(context, "Check for updates", 0xFFFFFFFF, BLURPLE, 0);
        check.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            status.setText("Checking…");
            VibeUpdates.fetchLatest((latest, apk, page, error) -> {
                if (error != null) {
                    status.setText(error);
                } else if (latest == 0) {
                    status.setText("No release published yet");
                } else if (latest > build) {
                    status.setText("Build " + latest + " is available");
                    dismiss();
                    VibeUpdates.showUpdateDialog(fragment, latest, apk, page);
                } else {
                    status.setText("You're on the latest build");
                }
            });
        });
        root.addView(check, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 0, 10, 0, 0));

        TextView source = button(context, "Source code on GitHub", VibeLayout.text(), 0, VibeLayout.divider());
        source.setOnClickListener(v -> VibeUpdates.open(context, VibeUpdates.REPO_URL));
        root.addView(source, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 0, 10, 0, 0));

        TextView licence = new TextView(context);
        licence.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        licence.setTextColor(VibeLayout.text2());
        licence.setGravity(Gravity.CENTER);
        licence.setText("Free software based on Telegram for Android (GNU GPL v2).\nNot made by or affiliated with Telegram.");
        root.addView(licence, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 16, 0, 0));

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
