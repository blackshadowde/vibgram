package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.zxing.EncodeHintType;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.TelegramQRCodeWriter;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.VibeLayout;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;

import java.util.HashMap;

/**
 * Vibgram: Share my ID. A QR code of your t.me link with your picture in the
 * middle (anyone can scan it with Vibgram or Telegram), plus Share and Copy.
 */
@SuppressLint("ViewConstructor")
public class VibeShareIdSheet extends BottomSheet {

    private static final int BLURPLE = 0xFF5865F2;
    private static final int QR_PX = 768;

    public static void show(BaseFragment fragment, TLRPC.User me, String link) {
        if (fragment == null || fragment.getParentActivity() == null || link == null) {
            return;
        }
        fragment.showDialog(new VibeShareIdSheet(fragment, me, link));
    }

    private VibeShareIdSheet(BaseFragment fragment, TLRPC.User me, String link) {
        super(fragment.getParentActivity(), false);
        Context context = fragment.getParentActivity();
        int account = fragment.getCurrentAccount();
        setApplyTopPadding(false);
        setBackgroundColor(VibeLayout.bg());

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(16), dp(20), dp(16), dp(16));

        TextView title = new TextView(context);
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 22);
        title.setTypeface(VibeLayout.boldFont());
        title.setTextColor(VibeLayout.text());
        title.setText("Share my ID");
        root.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        TextView sub = new TextView(context);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        sub.setTextColor(VibeLayout.text2());
        sub.setText("Scan with Vibgram or Telegram to start a chat with you");
        root.addView(sub, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 16));

        // ---- QR card (white so every camera can read it)
        int[] center = new int[2];
        Bitmap qr = makeQr(link, center);
        FrameLayout card = new FrameLayout(context);
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(0xFFFFFFFF);
        cardBg.setCornerRadius(dp(24));
        card.setBackground(cardBg);
        int qrDp = 220;
        if (qr != null) {
            ImageView qrView = new ImageView(context);
            qrView.setImageBitmap(qr);
            card.addView(qrView, LayoutHelper.createFrame(qrDp, qrDp, Gravity.CENTER));
            if (me != null && center[0] > 0) {
                float holeDp = qrDp * center[0] / (float) Math.max(1, center[1]);
                BackupImageView avatar = new BackupImageView(context);
                avatar.setRoundRadius(dp(holeDp / 2f));
                AvatarDrawable ad = new AvatarDrawable();
                ad.setInfo(account, me);
                avatar.setForUserOrChat(me, ad);
                card.addView(avatar, LayoutHelper.createFrame((int) holeDp, (int) holeDp, Gravity.CENTER));
            }
        }
        root.addView(card, LayoutHelper.createLinear(qrDp + 36, qrDp + 36, Gravity.CENTER_HORIZONTAL));

        TextView handle = new TextView(context);
        handle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 17);
        handle.setTypeface(VibeLayout.boldFont());
        handle.setTextColor(VibeLayout.text());
        handle.setGravity(Gravity.CENTER);
        String username = me != null ? UserObject.getPublicUsername(me) : null;
        handle.setText(username != null ? "@" + username : link);
        root.addView(handle, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 14, 0, 0));

        TextView linkView = new TextView(context);
        linkView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        linkView.setTextColor(VibeLayout.text2());
        linkView.setGravity(Gravity.CENTER);
        linkView.setText(link);
        root.addView(linkView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));

        LinearLayout buttons = new LinearLayout(context);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        TextView copy = button(context, "Copy link", VibeLayout.text(), 0, VibeLayout.divider());
        copy.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            AndroidUtilities.addToClipboard(link);
            BulletinFactory.of(getContainer(), null).createCopyLinkBulletin().show();
        });
        buttons.addView(copy, LayoutHelper.createLinear(0, 48, 1f, 0, 0, 5, 0));
        TextView share = button(context, "Share", 0xFFFFFFFF, BLURPLE, 0);
        share.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("text/plain");
            intent.putExtra(Intent.EXTRA_TEXT, "Chat with me on Vibgram: " + link);
            try {
                fragment.getParentActivity().startActivity(Intent.createChooser(intent, "Share my ID"));
            } catch (Throwable ignore) {
            }
        });
        buttons.addView(share, LayoutHelper.createLinear(0, 48, 1f, 5, 0, 0, 0));
        root.addView(buttons, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 0, 20, 0, 0));

        setCustomView(root);
    }

    /** QR in Vibe blurple with a white hole in the middle for your picture. */
    private static Bitmap makeQr(String link, int[] centerOut) {
        try {
            HashMap<EncodeHintType, Object> hints = new HashMap<>();
            hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
            hints.put(EncodeHintType.MARGIN, 0);
            TelegramQRCodeWriter writer = new TelegramQRCodeWriter();
            Bitmap bitmap = writer.encode(link, QR_PX, QR_PX, hints, null, 1.0f, 0xFFFFFFFF, 0xFF1A191E);
            int hole = writer.getImageSize();
            centerOut[0] = hole;
            centerOut[1] = bitmap != null ? bitmap.getWidth() : QR_PX;
            if (bitmap != null && hole > 0) {
                // Cover Telegram's logo in the middle; the user's picture sits on top.
                Canvas c = new Canvas(bitmap);
                Paint white = new Paint(Paint.ANTI_ALIAS_FLAG);
                white.setColor(0xFFFFFFFF);
                float cx = bitmap.getWidth() / 2f;
                float cy = bitmap.getHeight() / 2f;
                c.drawRect(cx - hole / 2f - 2, cy - hole / 2f - 2, cx + hole / 2f + 2, cy + hole / 2f + 2, white);
            }
            return bitmap;
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        }
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
