package org.telegram.messenger;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.text.TextUtils;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/**
 * Vibgram: switches, palette, fonts and small drawing helpers for the
 * Vibe / Discord style look. Values match the Vibe Flutter app.
 */
public class VibeLayout {

    public static final boolean ON = true;

    // ------------------------------------------------------------ metrics (dp)
    /** Picture offset from the top of a message group. */
    public static final int AVATAR_TOP_DP = 8;
    /** Picture size in the timeline (Vibe: 40). */
    public static final int AVATAR_SIZE_DP = 40;
    /** Left padding before the picture (Vibe: 12). */
    public static final int AVATAR_LEFT_DP = 12;
    /** Space the picture takes before the message column (12 + 40 + 12 = text at 64dp). */
    public static final int AVATAR_SPACE_DP = 44;
    /** Height of the reply strip above a message (Vibe: 22). */
    public static final int REPLY_STRIP_DP = 22;

    /** Rounded-square corner for a picture of the given size (Discord style). */
    public static int avatarRadius(int sizePx) {
        return Math.round(sizePx * 0.3f);
    }

    // ------------------------------------------------------------ palette
    private static boolean dark() {
        return org.telegram.ui.ActionBar.Theme.isCurrentThemeDark();
    }

    /** Chat, header and pane background. */
    public static int bg() { return dark() ? 0xFF1A191E : 0xFFFFFFFF; }
    /** Rail and search field. */
    public static int rail() { return dark() ? 0xFF121214 : 0xFFE3E5E8; }
    /** Input pill, user bar, cards. */
    public static int card() { return dark() ? 0xFF252429 : 0xFFF2F3F5; }
    /** Buttons on cards. */
    public static int cardHigh() { return dark() ? 0xFF323136 : 0xFFE3E5E8; }
    public static int text() { return dark() ? 0xFFF2F3F5 : 0xFF060607; }
    public static int text2() { return dark() ? 0xFFB5BAC1 : 0xFF5C5E66; }
    public static int divider() { return dark() ? 0xFF323136 : 0xFFD8D9DC; }
    /** Round mic button next to the input bar. */
    public static int micBg() { return dark() ? 0xFFDDE4FF : 0xFFE0E3FF; }
    public static int micIcon() { return dark() ? 0xFF226DFE : 0xFF3C45A5; }
    /** Picture placeholder when someone has no photo: plain grey, no colours. */
    public static int avatarGrey() { return dark() ? 0xFF4E5058 : 0xFFA4A9B1; }

    // ------------------------------------------------------------ fonts (GG Sans, from Vibe)
    public static final String FONT_REGULAR = "fonts/ggsans_regular.ttf";
    public static final String FONT_SEMIBOLD = "fonts/ggsans_semibold.ttf";
    public static final String FONT_BOLD = "fonts/ggsans_bold.ttf";

    public static Typeface regular() {
        Typeface t = AndroidUtilities.getTypeface(FONT_REGULAR);
        return t != null ? t : Typeface.DEFAULT;
    }

    public static Typeface semibold() {
        return AndroidUtilities.getTypeface(FONT_SEMIBOLD);
    }

    public static Typeface boldFont() {
        Typeface t = AndroidUtilities.getTypeface(FONT_BOLD);
        return t != null ? t : AndroidUtilities.bold();
    }

    private static boolean globalFontApplied;

    /** Makes GG Sans the default font of the app (all plain text). Safe if it fails. */
    @SuppressWarnings("unchecked")
    public static void applyGlobalFont() {
        if (!ON || globalFontApplied) {
            return;
        }
        globalFontApplied = true;
        Typeface tf = AndroidUtilities.getTypeface(FONT_REGULAR);
        if (tf == null) {
            return;
        }
        try {
            Method setDefault = Typeface.class.getDeclaredMethod("setDefault", Typeface.class);
            setDefault.setAccessible(true);
            setDefault.invoke(null, tf);
        } catch (Throwable ignore) {
        }
        for (String name : new String[]{"DEFAULT", "SANS_SERIF"}) {
            try {
                Field f = Typeface.class.getDeclaredField(name);
                f.setAccessible(true);
                f.set(null, tf);
            } catch (Throwable ignore) {
            }
        }
        try {
            Field f = Typeface.class.getDeclaredField("sSystemFontMap");
            f.setAccessible(true);
            Object value = f.get(null);
            if (value instanceof Map) {
                Map<String, Typeface> map = new HashMap<>((Map<String, Typeface>) value);
                map.put("sans-serif", tf);
                f.set(null, map);
            }
        } catch (Throwable ignore) {
        }
    }

    /** GG Sans on the chat text paints (called when the chat theme is applied). */
    public static void applyChatFonts() {
        if (!ON) {
            return;
        }
        Typeface reg = regular();
        org.telegram.ui.ActionBar.Theme.setVibeChatTypefaces(reg, boldFont());
    }

    // ------------------------------------------------------------ drawing helpers
    private static Paint rowPaint;
    private static TextPaint nameTimePaint;
    private static Paint curvePaint;
    private static TextPaint replyNamePaint;
    private static TextPaint replyTextPaint;
    private static final Path curvePath = new Path();

    private static Paint cardPaint;
    private static final android.graphics.RectF cardRect = new android.graphics.RectF();

    /** Flat rounded card for files, polls, contacts (Discord embed look). */
    public static void drawCard(Canvas canvas, float left, float top, float right, float bottom, boolean selected, float alpha) {
        if (right <= left || bottom <= top) {
            return;
        }
        if (cardPaint == null) {
            cardPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        }
        cardPaint.setColor(selected ? cardHigh() : card());
        cardPaint.setAlpha((int) (0xFF * Math.max(0f, Math.min(1f, alpha))));
        cardRect.set(left, top, right, bottom);
        float r = AndroidUtilities.dp(12);
        canvas.drawRoundRect(cardRect, r, r, cardPaint);
    }

    /** Full-width row highlight (jump to message, search hit). */
    public static void drawRowHighlight(Canvas canvas, float width, float height, int color, float alpha) {
        if (alpha <= 0f) {
            return;
        }
        if (rowPaint == null) {
            rowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        }
        rowPaint.setColor(color);
        rowPaint.setAlpha((int) (Math.min(1f, alpha) * 0x40));
        canvas.drawRect(0, 0, width, height, rowPaint);
    }

    /** Small grey time drawn after the sender name, like "4:20 PM". */
    public static void drawNameTime(Canvas canvas, CharSequence time, float x, float centerY, int color, float alpha) {
        if (time == null || time.length() == 0 || alpha <= 0f) {
            return;
        }
        if (nameTimePaint == null) {
            nameTimePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        }
        nameTimePaint.setTypeface(regular());
        nameTimePaint.setTextSize(AndroidUtilities.dp(12));
        nameTimePaint.setColor(color);
        nameTimePaint.setAlpha((int) (Math.min(1f, alpha) * 0xFF));
        Paint.FontMetrics fm = nameTimePaint.getFontMetrics();
        float baseline = centerY - (fm.ascent + fm.descent) / 2f;
        canvas.drawText(time, 0, time.length(), x, baseline, nameTimePaint);
    }

    /**
     * The Vibe reply strip: a curved line from the picture up and to the right,
     * then a tiny picture, "@name" and a one-line preview.
     *
     * @param top      top of the strip in the cell
     * @param right    right edge available for the preview
     * @param avatarX  x where the mini picture should be drawn (returned layout start)
     * @return x where the mini picture goes
     */
    public static float drawReplyCurve(Canvas canvas, float top, float alpha) {
        if (curvePaint == null) {
            curvePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            curvePaint.setStyle(Paint.Style.STROKE);
            curvePaint.setStrokeCap(Paint.Cap.ROUND);
        }
        final float h = AndroidUtilities.dp(REPLY_STRIP_DP);
        final float left = AndroidUtilities.dp(AVATAR_LEFT_DP);
        final float w = AndroidUtilities.dp(AVATAR_SIZE_DP);
        final float cx = left + w / 2f;
        final float mid = top + h / 2f;
        final float r = AndroidUtilities.dp(8);
        curvePaint.setStrokeWidth(AndroidUtilities.dp(2));
        int c = text2();
        curvePaint.setColor(c);
        curvePaint.setAlpha((int) (0x73 * Math.min(1f, alpha)));
        curvePath.rewind();
        curvePath.moveTo(cx, top + h + AndroidUtilities.dp(2));
        curvePath.lineTo(cx, mid + r);
        curvePath.quadTo(cx, mid, cx + r, mid);
        curvePath.lineTo(left + w, mid);
        canvas.drawPath(curvePath, curvePaint);
        return left + w + AndroidUtilities.dp(6);
    }

    /** Draws "@name  preview" on one line, starting at x. */
    public static void drawReplyText(Canvas canvas, CharSequence name, CharSequence preview, float x, float top, float right, float alpha) {
        if (replyNamePaint == null) {
            replyNamePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
            replyTextPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        }
        replyNamePaint.setTypeface(semibold() != null ? semibold() : AndroidUtilities.bold());
        replyTextPaint.setTypeface(regular());
        replyNamePaint.setTextSize(AndroidUtilities.dp(13.5f));
        replyTextPaint.setTextSize(AndroidUtilities.dp(13.5f));
        int a = (int) (0xFF * Math.min(1f, alpha));
        replyNamePaint.setColor(text());
        replyNamePaint.setAlpha(a);
        replyTextPaint.setColor(text2());
        replyTextPaint.setAlpha(a);
        final float h = AndroidUtilities.dp(REPLY_STRIP_DP);
        Paint.FontMetrics fm = replyTextPaint.getFontMetrics();
        float baseline = top + h / 2f - (fm.ascent + fm.descent) / 2f;
        float avail = right - x;
        if (avail <= 0) {
            return;
        }
        CharSequence n = TextUtils.ellipsize("@" + (name == null ? "" : name.toString().replace('\n', ' ')), replyNamePaint, avail * 0.6f, TextUtils.TruncateAt.END);
        canvas.drawText(n, 0, n.length(), x, baseline, replyNamePaint);
        float nx = x + replyNamePaint.measureText(n, 0, n.length()) + AndroidUtilities.dp(6);
        if (preview != null && right - nx > AndroidUtilities.dp(16)) {
            CharSequence p = TextUtils.ellipsize(preview.toString().replace('\n', ' '), replyTextPaint, right - nx, TextUtils.TruncateAt.END);
            canvas.drawText(p, 0, p.length(), nx, baseline, replyTextPaint);
        }
    }
}
