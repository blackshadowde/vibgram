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

    /** Free TMDB key (v3 key or v4 read token) for "where to watch". Empty when not set. */
    public static String tmdbKey() {
        if (ApplicationLoader.applicationContext == null) {
            return "";
        }
        return prefs().getString("tmdb_key", "");
    }

    public static void setTmdbKey(String value) {
        prefs().edit().putString("tmdb_key", value == null ? "" : value.trim()).apply();
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

    // ------------------------------------------------------------------ any video sent as a file

    /** File extensions the built-in player (ExoPlayer) can open, mapped to their video MIME type. */
    private static final java.util.HashMap<String, String> VIDEO_EXT = new java.util.HashMap<>();

    static {
        VIDEO_EXT.put("mp4", "video/mp4");
        VIDEO_EXT.put("m4v", "video/mp4");
        VIDEO_EXT.put("mov", "video/quicktime");
        VIDEO_EXT.put("mkv", "video/x-matroska");
        VIDEO_EXT.put("webm", "video/webm");
        VIDEO_EXT.put("ts", "video/mp2t");
        VIDEO_EXT.put("m2ts", "video/mp2t");
        VIDEO_EXT.put("mts", "video/mp2t");
        VIDEO_EXT.put("3gp", "video/3gpp");
        VIDEO_EXT.put("flv", "video/x-flv");
        VIDEO_EXT.put("avi", "video/x-msvideo");
    }

    /**
     * True for a video a bot or person sent as a plain file (.mp4, .mkv, .mov…), with or without
     * a video MIME type. These play in the player and stream while downloading.
     */
    public static boolean isVideoFile(TLRPC.Document document) {
        if (document == null) {
            return false;
        }
        if (isMkv(document)) {
            return true;
        }
        String mime = document.mime_type;
        if (mime != null && mime.startsWith("video/") && !"video/x-ms-wmv".equals(mime) && !"video/x-ms-asf".equals(mime)) {
            return true;
        }
        return VIDEO_EXT.containsKey(extension(document));
    }

    /**
     * A real video MIME type for streaming. Bots often send movies as application/octet-stream;
     * the stream loader only treats a file as video when its MIME says so.
     */
    public static String videoMime(TLRPC.Document document) {
        if (document == null) {
            return "video/mp4";
        }
        String mime = document.mime_type;
        if (mime != null && mime.startsWith("video/")) {
            return mime;
        }
        String byExt = VIDEO_EXT.get(extension(document));
        return byExt != null ? byExt : "video/mp4";
    }

    private static String extension(TLRPC.Document document) {
        String name = FileLoader.getDocumentFileName(document);
        if (name == null) {
            return "";
        }
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1).toLowerCase() : "";
    }
}
