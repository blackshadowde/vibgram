package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.tgnet.TLRPC;

/**
 * Vibgram: switches shown in Settings → Vibgram customizations.
 * Values are cached in static fields because some of them are read while drawing.
 */
public class VibeConfig {

    private static final String PREFS = "vibgram_config";

    private static boolean loaded;
    private static boolean plainChatBackground;
    private static boolean fastDownload;
    private static boolean loadingTitle;
    private static boolean preferFfmpeg;
    private static boolean moviesSection;

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static void load() {
        if (loaded) {
            return;
        }
        if (ApplicationLoader.applicationContext == null) {
            // Too early (class init). Use the defaults until the app is up.
            plainChatBackground = true;
            fastDownload = true;
            loadingTitle = true;
            moviesSection = true;
            return;
        }
        loaded = true;
        SharedPreferences p = prefs();
        plainChatBackground = p.getBoolean("plain_chat_bg", true);
        fastDownload = p.getBoolean("fast_download", true);
        loadingTitle = p.getBoolean("loading_title", true);
        preferFfmpeg = p.getBoolean("prefer_ffmpeg", false);
        moviesSection = p.getBoolean("movies_section", true);
    }

    /** Solid chat background (Vibe look). Keeps text readable in light mode. */
    public static boolean plainChatBackground() {
        load();
        return plainChatBackground;
    }

    public static void setPlainChatBackground(boolean value) {
        load();
        plainChatBackground = value;
        prefs().edit().putBoolean("plain_chat_bg", value).apply();
    }

    /** Bigger download parts and more parallel requests. */
    public static boolean fastDownload() {
        load();
        return fastDownload;
    }

    public static void setFastDownload(boolean value) {
        load();
        fastDownload = value;
        prefs().edit().putBoolean("fast_download", value).apply();
    }

    /** Pulsing title over long videos while they load. */
    public static boolean loadingTitle() {
        load();
        return loadingTitle;
    }

    public static void setLoadingTitle(boolean value) {
        load();
        loadingTitle = value;
        prefs().edit().putBoolean("loading_title", value).apply();
    }

    /** Use the built-in FFmpeg decoders first (AC3, DTS, EAC3 audio in MKV files). */
    public static boolean preferFfmpeg() {
        load();
        return preferFfmpeg;
    }

    public static void setPreferFfmpeg(boolean value) {
        load();
        preferFfmpeg = value;
        prefs().edit().putBoolean("prefer_ffmpeg", value).apply();
    }

    /** 🎥 Movies on the home rail. */
    public static boolean moviesSection() {
        load();
        return moviesSection;
    }

    public static void setMoviesSection(boolean value) {
        load();
        moviesSection = value;
        prefs().edit().putBoolean("movies_section", value).apply();
    }

    // ------------------------------------------------------------------ MKV

    /** True for Matroska / WebM files, also when a bot sent them as a plain file. */
    public static boolean isMkv(TLRPC.Document document) {
        if (document == null) {
            return false;
        }
        if (isMkvMime(document.mime_type)) {
            return true;
        }
        String name = FileLoader.getDocumentFileName(document);
        if (name == null) {
            return false;
        }
        name = name.toLowerCase();
        return name.endsWith(".mkv") || name.endsWith(".webm");
    }

    public static boolean isMkvMime(String mime) {
        return "video/x-matroska".equals(mime) || "video/webm".equals(mime);
    }
}
