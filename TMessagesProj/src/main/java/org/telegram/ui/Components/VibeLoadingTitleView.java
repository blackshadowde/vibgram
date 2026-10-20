package org.telegram.ui.Components;

import android.animation.ValueAnimator;
import android.content.Context;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.VibeConfig;
import org.telegram.messenger.VibeLayout;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Vibgram: the movie title over the player while it loads.
 * Loading: the title breathes between translucent and opaque.
 * Ready: it fades away. Buffering again later: it comes back.
 */
public class VibeLoadingTitleView extends TextView {

    private static final float LOW = 0.25f;
    private static final float HIGH = 1f;

    private ValueAnimator pulse;
    private boolean loading;
    private MessageObject boundMessage;

    public VibeLoadingTitleView(Context context) {
        super(context);
        setGravity(Gravity.CENTER);
        setTextColor(0xFFFFFFFF);
        setTextSize(TypedValue.COMPLEX_UNIT_DIP, 30);
        setTypeface(VibeLayout.boldFont());
        setMaxLines(2);
        setEllipsize(TextUtils.TruncateAt.END);
        setShadowLayer(AndroidUtilities.dp(6), 0, AndroidUtilities.dp(1), 0x99000000);
        setPadding(AndroidUtilities.dp(32), 0, AndroidUtilities.dp(32), 0);
        setAlpha(0f);
        setVisibility(GONE);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    /** Long videos and MKV files: movies and episodes. */
    public static boolean isMovie(MessageObject m) {
        if (m == null || !m.isVideo()) {
            return false;
        }
        return m.getDuration() >= 10 * 60 || VibeConfig.isMkv(m.getDocument()) || VibeConfig.isVideoFile(m.getDocument()) && !hasVideoAttribute(m);
    }

    private static boolean hasVideoAttribute(MessageObject m) {
        if (m.getDocument() == null) {
            return false;
        }
        for (org.telegram.tgnet.TLRPC.DocumentAttribute a : m.getDocument().attributes) {
            if (a instanceof org.telegram.tgnet.TLRPC.TL_documentAttributeVideo) {
                return true;
            }
        }
        return false;
    }

    /** Only movies get the title, and only when the setting is on. */
    public static boolean wants(MessageObject m) {
        return VibeConfig.loadingTitle() && isMovie(m);
    }

    /** Call when the viewer switches to another item. */
    public void bind(MessageObject m) {
        if (m == boundMessage) {
            return;
        }
        boundMessage = m;
        if (!wants(m)) {
            setLoading(false, false);
            return;
        }
        setText(titleOf(m));
    }

    public void setLoading(boolean value, boolean animated) {
        if (boundMessage == null || !wants(boundMessage)) {
            value = false;
        }
        if (loading == value && (value || getVisibility() == GONE)) {
            return;
        }
        loading = value;
        animate().cancel();
        if (value) {
            setVisibility(VISIBLE);
            startPulse();
        } else {
            stopPulse();
            if (animated && getVisibility() == VISIBLE) {
                animate().alpha(0f).setDuration(350).withEndAction(() -> {
                    if (!loading) {
                        setVisibility(GONE);
                    }
                }).start();
            } else {
                setAlpha(0f);
                setVisibility(GONE);
            }
        }
    }

    private void startPulse() {
        if (pulse != null) {
            return;
        }
        pulse = ValueAnimator.ofFloat(0f, 1f);
        pulse.setDuration(1600);
        pulse.setRepeatCount(ValueAnimator.INFINITE);
        pulse.setInterpolator(new LinearInterpolator());
        final float start = getAlpha();
        pulse.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            // Smooth breathe: translucent → opaque → translucent.
            float wave = (float) (0.5 - 0.5 * Math.cos(t * Math.PI * 2));
            float target = LOW + (HIGH - LOW) * wave;
            // First fade in from wherever we were, then follow the wave.
            if (a.getCurrentPlayTime() < 400 && start < target) {
                float k = a.getCurrentPlayTime() / 400f;
                target = start + (target - start) * k;
            }
            setAlpha(target);
        });
        pulse.start();
    }

    private void stopPulse() {
        if (pulse != null) {
            pulse.cancel();
            pulse = null;
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        stopPulse();
    }

    // ------------------------------------------------------------------ title

    private static final Pattern CUT = Pattern.compile(
            "(?i)[\\s(\\[]((19|20)\\d{2}|\\d{3,4}p|s\\d{1,2}e\\d{1,3}|s\\d{1,2}|web[- ]?(dl|rip)?|bluray|brrip|bdrip|hdrip|dvdrip|hdtv|x26[45]|h ?26[45]|hevc|10bit|hdr|uhd|4k|aac|ddp?5|dual|multi|esub|hindi|tamil|telugu)\\b");
    private static final Pattern EPISODE = Pattern.compile("(?i)\\b(s\\d{1,2}e\\d{1,3})\\b");
    private static final Pattern YEAR = Pattern.compile("\\b((19|20)\\d{2})\\b");

    /** "The.Furious.2025.1080p.WEB-DL.mkv" → "The Furious (2025)". */
    public static String titleOf(MessageObject m) {
        String name = m.getDocument() != null ? FileLoader.getDocumentFileName(m.getDocument()) : null;
        if (TextUtils.isEmpty(name) && m.caption != null) {
            name = m.caption.toString().split("\n")[0];
        }
        if (TextUtils.isEmpty(name)) {
            return "";
        }
        return cleanTitle(name);
    }

    public static String cleanTitle(String raw) {
        String s = raw;
        int dot = s.lastIndexOf('.');
        if (dot > 0 && s.length() - dot <= 5) {
            s = s.substring(0, dot);
        }
        s = s.replaceAll("^\\s*[\\[(][^\\])]*[\\])]\\s*", ""); // leading [Group] tags
        s = s.replaceAll("^@\\S+\\s*[-_]?\\s*", "");
        s = s.replace('.', ' ').replace('_', ' ').replaceAll("\\s+", " ").trim();
        String year = null;
        Matcher y = YEAR.matcher(s);
        if (y.find() && y.start() > 0) {
            year = y.group(1);
        }
        String episode = null;
        Matcher e = EPISODE.matcher(s);
        if (e.find()) {
            episode = e.group(1).toUpperCase();
        }
        Matcher cut = CUT.matcher(" " + s);
        if (cut.find() && cut.start() > 0) {
            s = (" " + s).substring(0, cut.start()).trim();
        }
        s = s.replaceAll("[\\s\\-–(\\[]+$", "").trim();
        if (s.isEmpty()) {
            s = raw;
        }
        if (episode != null) {
            s = s + " · " + episode;
        } else if (year != null) {
            s = s + " (" + year + ")";
        }
        return s;
    }
}
