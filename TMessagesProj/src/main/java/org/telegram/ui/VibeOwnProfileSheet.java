package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.VibeLayout;
import org.telegram.messenger.VibePrivacy;
import org.telegram.messenger.VibePush;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.Switch;
import org.telegram.ui.Stories.recorder.StoryRecorder;

import java.util.Calendar;

/**
 * Vibgram: your own profile, like Vibe. Banner with close / camera / edit / settings,
 * a big picture with a thought bubble ("What are you currently obsessed with?"),
 * DISPLAY NAME and USERNAME boxes, Open settings, Share my ID, and privacy shields.
 */
@SuppressLint("ViewConstructor")
public class VibeOwnProfileSheet extends BottomSheet {

    private static final int BLURPLE = 0xFF5865F2;

    private final BaseFragment fragment;
    private final int account;
    private TextView bubbleText;

    public static void show(BaseFragment fragment) {
        if (fragment == null || fragment.getParentActivity() == null) {
            return;
        }
        new VibeOwnProfileSheet(fragment).show();
    }

    private VibeOwnProfileSheet(BaseFragment fragment) {
        super(fragment.getParentActivity(), false);
        this.fragment = fragment;
        this.account = fragment.getCurrentAccount();
        Context context = fragment.getParentActivity();
        setApplyTopPadding(false);
        setBackgroundColor(VibeLayout.bg());
        TLRPC.User me = UserConfig.getInstance(account).getCurrentUser();

        ScrollView scroll = new ScrollView(context);
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(root);

        // ---- banner with round buttons
        FrameLayout header = new FrameLayout(context);
        View banner = new View(context);
        GradientDrawable bannerBg = new GradientDrawable();
        bannerBg.setColor(org.telegram.ui.ActionBar.Theme.isCurrentThemeDark() ? 0xFF1F2624 : 0xFFD9DEE3);
        bannerBg.setCornerRadii(new float[]{dp(24), dp(24), dp(24), dp(24), 0, 0, 0, 0});
        banner.setBackground(bannerBg);
        header.addView(banner, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 100, Gravity.TOP));

        header.addView(roundButton(context, R.drawable.ic_close_white, v -> dismiss()),
                LayoutHelper.createFrame(38, 38, Gravity.LEFT | Gravity.TOP, 12, 12, 0, 0));
        header.addView(roundButton(context, R.drawable.msg_camera, v -> showCameraMenu()),
                LayoutHelper.createFrame(38, 38, Gravity.RIGHT | Gravity.TOP, 0, 12, 104, 0));
        header.addView(roundButton(context, R.drawable.msg_edit, v -> {
            dismiss();
            fragment.presentFragment(new ChangeNameActivity(null));
        }), LayoutHelper.createFrame(38, 38, Gravity.RIGHT | Gravity.TOP, 0, 12, 58, 0));
        header.addView(roundButton(context, R.drawable.msg_settings, v -> {
            dismiss();
            fragment.presentFragment(new SettingsActivity());
        }), LayoutHelper.createFrame(38, 38, Gravity.RIGHT | Gravity.TOP, 0, 12, 12, 0));

        FrameLayout ring = new FrameLayout(context);
        GradientDrawable ringBg = new GradientDrawable();
        ringBg.setShape(GradientDrawable.OVAL);
        ringBg.setColor(VibeLayout.bg());
        ring.setBackground(ringBg);
        BackupImageView avatar = new BackupImageView(context);
        avatar.setRoundRadius(dp(44));
        AvatarDrawable avatarDrawable = new AvatarDrawable();
        if (me != null) {
            avatarDrawable.setInfo(account, me);
            avatar.setForUserOrChat(me, avatarDrawable);
        }
        ring.addView(avatar, LayoutHelper.createFrame(88, 88, Gravity.CENTER));
        View dot = new View(context);
        GradientDrawable dotBg = new GradientDrawable();
        dotBg.setShape(GradientDrawable.OVAL);
        dotBg.setColor(0xFF23A55A);
        dotBg.setStroke(dp(4), VibeLayout.bg());
        dot.setBackground(dotBg);
        ring.addView(dot, LayoutHelper.createFrame(26, 26, Gravity.RIGHT | Gravity.BOTTOM, 0, 0, 2, 2));
        ring.setOnLongClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            openStoryRecorder();
            return true;
        });
        header.addView(ring, LayoutHelper.createFrame(98, 98, Gravity.LEFT | Gravity.TOP, 12, 54, 0, 0));

        // thought bubble with the status
        FrameLayout bubble = new FrameLayout(context) {
            private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

            @Override
            protected void dispatchDraw(Canvas canvas) {
                p.setColor(VibeLayout.card());
                canvas.drawCircle(dp(10), dp(12), dp(6), p);
                canvas.drawCircle(dp(26), dp(26), dp(8), p);
                super.dispatchDraw(canvas);
            }
        };
        bubble.setWillNotDraw(false);
        bubbleText = new TextView(context);
        bubbleText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        bubbleText.setTextColor(VibeLayout.text());
        bubbleText.setMaxLines(3);
        bubbleText.setEllipsize(TextUtils.TruncateAt.END);
        bubbleText.setPadding(dp(14), dp(10), dp(14), dp(10));
        GradientDrawable bubbleBg = new GradientDrawable();
        bubbleBg.setColor(VibeLayout.card());
        bubbleBg.setCornerRadius(dp(26));
        bubbleBg.setStroke(dp(1), VibeLayout.divider());
        bubbleText.setBackground(bubbleBg);
        updateBubble();
        bubble.addView(bubbleText, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP, 4, 30, 0, 0));
        bubble.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            showStatusEditor();
        });
        header.addView(bubble, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.TOP, 112, 76, 12, 0));
        root.addView(header, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 160));

        // ---- name
        TextView name = new TextView(context);
        name.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 24);
        name.setTypeface(VibeLayout.boldFont());
        name.setTextColor(VibeLayout.text());
        name.setText(me != null ? UserObject.getUserName(me) : "");
        root.addView(name, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 20, 12, 20, 0));
        String username = me != null ? UserObject.getPublicUsername(me) : null;
        TextView sub = new TextView(context);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        sub.setTextColor(VibeLayout.text2());
        sub.setText(!TextUtils.isEmpty(username) ? "@" + username : "No username yet");
        root.addView(sub, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 20, 2, 20, 0));

        // ---- fields
        root.addView(label(context, "DISPLAY NAME"), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 18, 16, 6));
        View nameBox = box(context, me != null ? UserObject.getUserName(me) : "", false);
        nameBox.setOnClickListener(v -> {
            dismiss();
            fragment.presentFragment(new ChangeNameActivity(null));
        });
        root.addView(nameBox, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 20, 0, 20, 0));
        root.addView(label(context, "USERNAME"), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 14, 16, 6));
        final String shareText = !TextUtils.isEmpty(username) ? "https://t.me/" + username : null;
        View userBox = box(context, !TextUtils.isEmpty(username) ? "@" + username : "Set a username in settings", !TextUtils.isEmpty(username));
        userBox.setOnClickListener(v -> {
            if (shareText != null) {
                AndroidUtilities.addToClipboard("@" + username);
                BulletinFactory.of(getContainer(), null).createCopyBulletin("Username copied").show();
            } else {
                dismiss();
                fragment.presentFragment(new ChangeUsernameActivity());
            }
        });
        root.addView(userBox, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 20, 0, 20, 0));

        View line = new View(context);
        line.setBackgroundColor(VibeLayout.divider());
        root.addView(line, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 1, 20, 24, 20, 0));

        // ---- buttons
        TextView settings = new TextView(context);
        settings.setGravity(Gravity.CENTER);
        settings.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        settings.setTypeface(VibeLayout.boldFont());
        settings.setTextColor(0xFFFFFFFF);
        settings.setText("Open settings");
        GradientDrawable settingsBg = new GradientDrawable();
        settingsBg.setColor(BLURPLE);
        settingsBg.setCornerRadius(dp(24));
        settings.setBackground(settingsBg);
        settings.setOnClickListener(v -> {
            dismiss();
            fragment.presentFragment(new SettingsActivity());
        });
        root.addView(settings, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 16, 16, 16, 0));

        TextView share = new TextView(context);
        share.setGravity(Gravity.CENTER);
        share.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        share.setTypeface(VibeLayout.boldFont());
        share.setTextColor(BLURPLE);
        share.setText("Share my ID");
        GradientDrawable shareBg = new GradientDrawable();
        shareBg.setColor(VibeLayout.bg());
        shareBg.setCornerRadius(dp(24));
        shareBg.setStroke(dp(1), VibeLayout.divider());
        share.setBackground(shareBg);
        share.setOnClickListener(v -> {
            if (shareText == null) {
                dismiss();
                fragment.presentFragment(new ChangeUsernameActivity());
                return;
            }
            // QR code + share / copy, like Telegram's QR but in Vibe style.
            VibeShareIdSheet.show(fragment, me, shareText);
        });
        root.addView(share, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 16, 10, 16, 0));

        // ---- privacy shields + notifications
        root.addView(label(context, "PRIVACY"), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 20, 16, 6));
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(VibeLayout.card());
        cardBg.setCornerRadius(dp(20));
        card.setBackground(cardBg);
        card.addView(toggleRow(context, "Hide \"joined Telegram\" alerts", "No pop-ups when a contact signs up",
                VibePrivacy.joinedAlertsOff(account), on -> VibePrivacy.setJoinedAlertsOff(account, on)));
        card.addView(toggleRow(context, "Ask before adding me to groups", "Only contacts can add you to groups and channels; others must send an invite",
                VibePrivacy.invitesContactsOnly(account), on -> VibePrivacy.setInvitesContactsOnly(account, on)));
        card.addView(toggleRow(context, "Hide my phone number", "Nobody can see your number",
                VibePrivacy.phoneHidden(account), on -> VibePrivacy.setPhoneHidden(account, on)));
        root.addView(card, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 20, 0, 20, 0));

        root.addView(label(context, "NOTIFICATIONS"), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 18, 16, 6));
        LinearLayout card2 = new LinearLayout(context);
        card2.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable card2Bg = new GradientDrawable();
        card2Bg.setColor(VibeLayout.card());
        card2Bg.setCornerRadius(dp(20));
        card2.setBackground(card2Bg);
        boolean hasNtfy = VibePush.hasDistributor();
        card2.addView(toggleRow(context, "Instant notifications",
                hasNtfy ? "Using the ntfy app (lowest battery use)" : "Built in: keeps a small connection open, shown as a quiet \"Vibgram is connected\" notice",
                VibePush.builtInEnabled() || hasNtfy, on -> VibePush.setBuiltInEnabled(getContext(), on)));
        root.addView(card2, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 20, 0, 20, 24));

        setCustomView(scroll);
    }

    private interface Toggle {
        void set(boolean on);
    }

    private View toggleRow(Context context, String title, String subtitle, boolean checked, Toggle toggle) {
        FrameLayout row = new FrameLayout(context);
        row.setPadding(dp(16), dp(12), dp(12), dp(12));
        LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(context);
        t.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        t.setTextColor(VibeLayout.text());
        t.setText(title);
        texts.addView(t);
        TextView s = new TextView(context);
        s.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        s.setTextColor(VibeLayout.text2());
        s.setText(subtitle);
        texts.addView(s);
        row.addView(texts, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.CENTER_VERTICAL, 0, 0, 60, 0));
        Switch sw = new Switch(context);
        sw.setChecked(checked, false);
        row.addView(sw, LayoutHelper.createFrame(37, 40, Gravity.RIGHT | Gravity.CENTER_VERTICAL));
        row.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            boolean on = !sw.isChecked();
            sw.setChecked(on, true);
            toggle.set(on);
        });
        return row;
    }

    private View roundButton(Context context, int icon, View.OnClickListener click) {
        ImageView b = new ImageView(context);
        b.setScaleType(ImageView.ScaleType.CENTER);
        b.setImageResource(icon);
        b.setColorFilter(new PorterDuffColorFilter(0xFFFFFFFF, PorterDuff.Mode.SRC_IN));
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(0xCC111214);
        bg.setStroke(dp(1), 0x33FFFFFF);
        b.setBackground(bg);
        b.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            click.onClick(v);
        });
        return b;
    }

    private TextView label(Context context, String text) {
        TextView tv = new TextView(context);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        tv.setTypeface(VibeLayout.boldFont());
        tv.setLetterSpacing(0.06f);
        tv.setTextColor(VibeLayout.text2());
        tv.setText(text);
        return tv;
    }

    private View box(Context context, String text, boolean copyIcon) {
        FrameLayout box = new FrameLayout(context);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(VibeLayout.bg());
        bg.setCornerRadius(dp(24));
        bg.setStroke(dp(1), VibeLayout.divider());
        box.setBackground(bg);
        TextView tv = new TextView(context);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        tv.setTextColor(copyIcon ? VibeLayout.text2() : VibeLayout.text());
        tv.setSingleLine(true);
        tv.setEllipsize(TextUtils.TruncateAt.END);
        tv.setText(text);
        box.addView(tv, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL | Gravity.LEFT, 16, 13, copyIcon ? 52 : 16, 13));
        if (copyIcon) {
            ImageView copy = new ImageView(context);
            copy.setScaleType(ImageView.ScaleType.CENTER);
            copy.setImageResource(R.drawable.msg_copy);
            copy.setColorFilter(new PorterDuffColorFilter(VibeLayout.text(), PorterDuff.Mode.SRC_IN));
            box.addView(copy, LayoutHelper.createFrame(40, 40, Gravity.RIGHT | Gravity.CENTER_VERTICAL, 0, 0, 6, 0));
        }
        return box;
    }

    private void updateBubble() {
        String status = VibePrivacy.currentStatus(account);
        if (TextUtils.isEmpty(status)) {
            bubbleText.setText("➕  What are you currently obsessed with?");
            bubbleText.setTextColor(VibeLayout.text2());
        } else {
            bubbleText.setText(status);
            bubbleText.setTextColor(VibeLayout.text());
        }
    }

    private void openStoryRecorder() {
        if (fragment.getParentActivity() == null) {
            return;
        }
        dismiss();
        StoryRecorder.getInstance(fragment.getParentActivity(), account).open(null);
    }

    private void showCameraMenu() {
        CharSequence[] items = {"Add photo or video to story", "Change profile picture"};
        int[] icons = {R.drawable.msg_stories_add, R.drawable.msg_camera};
        BottomSheet.Builder builder = new BottomSheet.Builder(fragment.getParentActivity());
        builder.setItems(items, icons, (d, which) -> {
            if (which == 0) {
                openStoryRecorder();
            } else {
                dismiss();
                Bundle args = new Bundle();
                args.putLong("user_id", UserConfig.getInstance(account).getClientUserId());
                args.putBoolean("my_profile", true);
                fragment.presentFragment(new ProfileActivity(args));
            }
        });
        fragment.showDialog(builder.create());
    }

    /** Vibe's "Set your status": a text field and a "Clear after" choice. */
    private void showStatusEditor() {
        Context context = fragment.getParentActivity();
        BottomSheet sheet = new BottomSheet(context, true);
        sheet.setApplyTopPadding(false);
        sheet.setBackgroundColor(VibeLayout.bg());
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(0, dp(16), 0, dp(20));

        TextView title = new TextView(context);
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        title.setTypeface(VibeLayout.boldFont());
        title.setTextColor(VibeLayout.text());
        title.setText("Set your status");
        root.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 20, 0, 20, 12));

        EditText field = new EditText(context);
        field.setHint("What are you currently obsessed with?");
        field.setHintTextColor(VibeLayout.text2());
        field.setTextColor(VibeLayout.text());
        field.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 17);
        field.setFilters(new InputFilter[]{new InputFilter.LengthFilter(70)});
        field.setImeOptions(EditorInfo.IME_ACTION_DONE);
        field.setSingleLine(true);
        field.setPadding(dp(18), 0, dp(18), 0);
        GradientDrawable fieldBg = new GradientDrawable();
        fieldBg.setColor(VibeLayout.card());
        fieldBg.setCornerRadius(dp(24));
        field.setBackground(fieldBg);
        String current = VibePrivacy.currentStatus(account);
        if (current != null) {
            field.setText(current);
            field.setSelection(current.length());
        }
        root.addView(field, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 54, 20, 0, 20, 8));

        TextView hint = new TextView(context);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        hint.setTextColor(VibeLayout.text2());
        hint.setText("Shown on your profile as your bio. Your old bio comes back when the status is cleared.");
        root.addView(hint, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 22, 0, 22, 12));

        TextView clearLabel = label(context, "CLEAR AFTER");
        root.addView(clearLabel, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 20, 4, 20, 8));
        final String[] options = {"Don't clear", "30 minutes", "1 hour", "4 hours", "Today", "This week"};
        final int[] selected = {0};
        LinearLayout chips = new LinearLayout(context);
        chips.setOrientation(LinearLayout.VERTICAL);
        final TextView[] chipViews = new TextView[options.length];
        for (int i = 0; i < options.length; i++) {
            TextView chip = new TextView(context);
            chip.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            chip.setPadding(dp(18), dp(12), dp(18), dp(12));
            chip.setText(options[i]);
            final int idx = i;
            chip.setOnClickListener(v -> {
                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                selected[0] = idx;
                styleChips(chipViews, selected[0]);
            });
            chipViews[i] = chip;
            chips.addView(chip, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 4));
        }
        styleChips(chipViews, 0);
        root.addView(chips, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 20, 0, 20, 12));

        LinearLayout buttons = new LinearLayout(context);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        TextView clear = new TextView(context);
        clear.setGravity(Gravity.CENTER);
        clear.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        clear.setTypeface(VibeLayout.boldFont());
        clear.setTextColor(0xFFF23F43);
        clear.setText("Clear status");
        GradientDrawable clearBg = new GradientDrawable();
        clearBg.setColor(VibeLayout.card());
        clearBg.setCornerRadius(dp(26));
        clear.setBackground(clearBg);
        clear.setOnClickListener(v -> {
            VibePrivacy.clearStatus(account);
            updateBubble();
            sheet.dismiss();
        });
        buttons.addView(clear, LayoutHelper.createLinear(0, 52, 1f));
        TextView save = new TextView(context);
        save.setGravity(Gravity.CENTER);
        save.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        save.setTypeface(VibeLayout.boldFont());
        save.setTextColor(0xFFFFFFFF);
        save.setText("Save");
        GradientDrawable saveBg = new GradientDrawable();
        saveBg.setColor(BLURPLE);
        saveBg.setCornerRadius(dp(26));
        save.setBackground(saveBg);
        save.setOnClickListener(v -> {
            String text = field.getText().toString().trim();
            if (text.isEmpty()) {
                VibePrivacy.clearStatus(account);
            } else {
                VibePrivacy.setStatus(account, text, clearAt(selected[0]));
            }
            updateBubble();
            AndroidUtilities.hideKeyboard(field);
            sheet.dismiss();
        });
        LinearLayout.LayoutParams saveLp = LayoutHelper.createLinear(0, 52, 1f);
        saveLp.leftMargin = dp(10);
        buttons.addView(save, saveLp);
        root.addView(buttons, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 52, 20, 4, 20, 0));

        sheet.setCustomView(root);
        fragment.showDialog(sheet);
    }

    private void styleChips(TextView[] chips, int selected) {
        for (int i = 0; i < chips.length; i++) {
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(16));
            bg.setColor(i == selected ? 0x335865F2 : VibeLayout.card());
            if (i == selected) {
                bg.setStroke(dp(1), BLURPLE);
            }
            chips[i].setBackground(bg);
            chips[i].setTextColor(i == selected ? VibeLayout.text() : VibeLayout.text2());
        }
    }

    private static long clearAt(int option) {
        long now = System.currentTimeMillis();
        switch (option) {
            case 1:
                return now + 30L * 60 * 1000;
            case 2:
                return now + 60L * 60 * 1000;
            case 3:
                return now + 4L * 60 * 60 * 1000;
            case 4: {
                Calendar c = Calendar.getInstance();
                c.set(Calendar.HOUR_OF_DAY, 23);
                c.set(Calendar.MINUTE, 59);
                return c.getTimeInMillis();
            }
            case 5:
                return now + 7L * 24 * 60 * 60 * 1000;
            default:
                return 0;
        }
    }
}
