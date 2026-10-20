package org.telegram.ui;

import android.view.View;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.VibeConfig;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.util.ArrayList;

/** Vibgram: Settings → Vibgram customizations. */
public class VibgramCustomizationsActivity extends UniversalFragment {

    private static final int PLAIN_CHAT = 1;
    private static final int FAST_DOWNLOAD = 2;
    private static final int STREAM_MKV = 3;
    private static final int LOADING_TITLE = 4;
    private static final int PREFER_FFMPEG = 5;
    private static final int KEEP_STREAMED = 6;
    private static final int STREAM_ALL = 7;
    private static final int MOVIES = 8;
    private static final int TMDB_KEY = 9;

    @Override
    protected CharSequence getTitle() {
        return "Vibgram customizations";
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asHeader("Chats"));
        items.add(UItem.asCheck(PLAIN_CHAT, "Plain chat background").setChecked(VibeConfig.plainChatBackground()));
        items.add(UItem.asShadow("One solid colour behind messages instead of the wallpaper. Keeps text easy to read in light mode."));

        items.add(UItem.asHeader("Downloads"));
        items.add(UItem.asCheck(FAST_DOWNLOAD, "High speed download").setChecked(VibeConfig.fastDownload()));
        items.add(UItem.asShadow("Downloads big files in larger parts with more parallel requests. Telegram may still limit speed on its side for non-Premium accounts."));

        items.add(UItem.asHeader("Video player"));
        items.add(UItem.asCheck(STREAM_MKV, "Stream video files while downloading").setChecked(SharedConfig.streamMkv));
        items.add(UItem.asCheck(STREAM_ALL, "Stream all videos").setChecked(SharedConfig.streamAllVideo));
        items.add(UItem.asCheck(KEEP_STREAMED, "Keep streamed videos").setChecked(SharedConfig.saveStreamMedia));
        items.add(UItem.asCheck(LOADING_TITLE, "Title while loading").setChecked(VibeConfig.loadingTitle()));
        items.add(UItem.asCheck(PREFER_FFMPEG, "Prefer built-in decoders").setChecked(VibeConfig.preferFfmpeg()));
        items.add(UItem.asShadow("Videos sent as files (MKV, MP4, MOV, WebM…) open in the player and start before the download finishes. Built-in decoders play audio some phones can't (AC3, DTS) — turn on if a video has no sound."));

        items.add(UItem.asHeader("Home"));
        items.add(UItem.asCheck(MOVIES, "Movies on the home rail").setChecked(VibeConfig.moviesSection()));
        items.add(UItem.asShadow("Shows 🎥 on the left rail: a home page with popular movies and series, search, downloads and videos you were watching."));
        items.add(UItem.asButton(TMDB_KEY, "TMDB key for where to watch", android.text.TextUtils.isEmpty(VibeConfig.tmdbKey()) ? "Not set" : "Set"));
        items.add(UItem.asShadow("Optional. A free key from themoviedb.org (Settings → API) lets movie pages show which streaming services have a title in your country."));
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        boolean value;
        switch (item.id) {
            case PLAIN_CHAT:
                value = !VibeConfig.plainChatBackground();
                VibeConfig.setPlainChatBackground(value);
                Theme.applyChatServiceMessageColor();
                AndroidUtilities.runOnUIThread(() -> NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.didSetNewTheme, false, false));
                break;
            case FAST_DOWNLOAD:
                value = !VibeConfig.fastDownload();
                VibeConfig.setFastDownload(value);
                break;
            case STREAM_MKV:
                SharedConfig.toggleStreamMkv();
                value = SharedConfig.streamMkv;
                break;
            case STREAM_ALL:
                SharedConfig.toggleStreamAllVideo();
                value = SharedConfig.streamAllVideo;
                break;
            case KEEP_STREAMED:
                SharedConfig.toggleSaveStreamMedia();
                value = SharedConfig.saveStreamMedia;
                break;
            case LOADING_TITLE:
                value = !VibeConfig.loadingTitle();
                VibeConfig.setLoadingTitle(value);
                break;
            case PREFER_FFMPEG:
                value = !VibeConfig.preferFfmpeg();
                VibeConfig.setPreferFfmpeg(value);
                break;
            case MOVIES:
                value = !VibeConfig.moviesSection();
                VibeConfig.setMoviesSection(value);
                NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.didSetNewTheme, false, false);
                break;
            case TMDB_KEY:
                askTmdbKey();
                return;
            default:
                return;
        }
        if (view instanceof TextCheckCell) {
            ((TextCheckCell) view).setChecked(value);
        }
    }

    private void askTmdbKey() {
        if (getParentActivity() == null) {
            return;
        }
        android.content.Context context = getParentActivity();
        android.widget.FrameLayout box = new android.widget.FrameLayout(context);
        org.telegram.ui.Components.EditTextBoldCursor field = new org.telegram.ui.Components.EditTextBoldCursor(context);
        field.setSingleLine(true);
        field.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 16);
        field.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        field.setHintTextColor(Theme.getColor(Theme.key_dialogTextHint));
        field.setHint("Paste API key or read token");
        field.setText(VibeConfig.tmdbKey());
        field.setCursorColor(Theme.getColor(Theme.key_dialogTextBlack));
        field.setLineColors(Theme.getColor(Theme.key_dialogInputField), Theme.getColor(Theme.key_dialogInputFieldActivated), Theme.getColor(Theme.key_text_RedRegular));
        box.addView(field, org.telegram.ui.Components.LayoutHelper.createFrame(org.telegram.ui.Components.LayoutHelper.MATCH_PARENT, 40, android.view.Gravity.TOP, 24, 6, 24, 0));
        new org.telegram.ui.ActionBar.AlertDialog.Builder(context)
                .setTitle("TMDB key")
                .setMessage("Get one free at themoviedb.org → Settings → API.")
                .setView(box)
                .setPositiveButton("Save", (dialog, which) -> {
                    VibeConfig.setTmdbKey(field.getText() == null ? "" : field.getText().toString());
                    if (listView != null && listView.adapter != null) {
                        listView.adapter.update(true);
                    }
                })
                .setNeutralButton("Remove", (dialog, which) -> {
                    VibeConfig.setTmdbKey("");
                    if (listView != null && listView.adapter != null) {
                        listView.adapter.update(true);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }
}
