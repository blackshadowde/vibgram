package org.telegram.messenger;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.TextPaint;

/**
 * Vibgram: switches for the Vibe / Discord style chat look.
 *
 * ON = every message sits on the left with a picture and name on top of a
 * group, no bubbles, the time next to the name and ticks after the text.
 */
public class VibeLayout {

    public static final boolean ON = true;

    /** Picture offset from the top of a message group, in dp. */
    public static final int AVATAR_TOP_DP = 4;

    private static Paint rowPaint;
    private static TextPaint nameTimePaint;

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
            nameTimePaint.setTypeface(Typeface.DEFAULT);
        }
        nameTimePaint.setTextSize(AndroidUtilities.dp(11.5f));
        nameTimePaint.setColor(color);
        nameTimePaint.setAlpha((int) (Math.min(1f, alpha) * 0xB0));
        Paint.FontMetrics fm = nameTimePaint.getFontMetrics();
        float baseline = centerY - (fm.ascent + fm.descent) / 2f;
        canvas.drawText(time, 0, time.length(), x, baseline, nameTimePaint);
    }
}
