package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DownloadController;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.VibeLayout;
import org.telegram.messenger.VibeMovies;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.VibeLoadingTitleView;

import java.util.ArrayList;

/**
 * Vibgram 🎥 Movies: a home page in the spirit of Stremio — search on top,
 * Continue Watching, Popular Movies and Popular Series. Tapping a poster opens
 * its page; from there you can watch the trailer or look for the title in your chats.
 */
public class VibeMoviesActivity extends BaseFragment implements NotificationCenter.NotificationCenterDelegate {

    private static final int BLURPLE = 0xFF5865F2;

    private LinearLayout homeColumn;
    private LinearLayout searchColumn;
    private ScrollView scroll;
    private EditText searchField;
    private LinearLayout continueSection;
    private final Runnable searchRunnable = this::runSearch;
    private String lastQuery = "";

    private LinearLayout downloadedSection;
    private DownloadedAdapter downloadedAdapter;
    private boolean downloadsRequested;

    // Redraws download progress while something is still downloading.
    private final Runnable progressTicker = new Runnable() {
        @Override
        public void run() {
            if (downloadedAdapter != null && downloadedAdapter.hasLoading()) {
                downloadedAdapter.notifyDataSetChanged();
                AndroidUtilities.runOnUIThread(this, 1500);
            }
        }
    };

    @Override
    public boolean onFragmentCreate() {
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.didSetNewTheme);
        getNotificationCenter().addObserver(this, NotificationCenter.onDownloadingFilesChanged);
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.didSetNewTheme);
        getNotificationCenter().removeObserver(this, NotificationCenter.onDownloadingFilesChanged);
        AndroidUtilities.cancelRunOnUIThread(searchRunnable);
        AndroidUtilities.cancelRunOnUIThread(progressTicker);
        super.onFragmentDestroy();
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.didSetNewTheme && fragmentView != null) {
            fragmentView.setBackgroundColor(VibeLayout.bg());
        } else if (id == NotificationCenter.onDownloadingFilesChanged) {
            fillDownloaded();
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        fillContinueWatching(); // progress changes after watching something
        fillDownloaded();
    }

    @Override
    public void onPause() {
        super.onPause();
        AndroidUtilities.cancelRunOnUIThread(progressTicker);
    }

    @Override
    public boolean isLightStatusBar() {
        return !Theme.isCurrentThemeDark();
    }

    @Override
    public View createView(Context context) {
        actionBar.setAddToContainer(false);

        FrameLayout root = new FrameLayout(context);
        root.setBackgroundColor(VibeLayout.bg());
        fragmentView = root;

        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(0, AndroidUtilities.statusBarHeight, 0, 0);
        root.addView(column, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        column.addView(createTopBar(context), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 64));

        scroll = new ScrollView(context);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        scroll.setPadding(0, 0, 0, dp(24));
        FrameLayout scrollContent = new FrameLayout(context);
        scroll.addView(scrollContent);
        column.addView(scroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f));

        homeColumn = new LinearLayout(context);
        homeColumn.setOrientation(LinearLayout.VERTICAL);
        scrollContent.addView(homeColumn, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        searchColumn = new LinearLayout(context);
        searchColumn.setOrientation(LinearLayout.VERTICAL);
        searchColumn.setVisibility(View.GONE);
        scrollContent.addView(searchColumn, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        continueSection = new LinearLayout(context);
        continueSection.setOrientation(LinearLayout.VERTICAL);
        homeColumn.addView(continueSection, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        fillContinueWatching();

        downloadedSection = new LinearLayout(context);
        downloadedSection.setOrientation(LinearLayout.VERTICAL);
        homeColumn.addView(downloadedSection, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        fillDownloaded();

        addCatalogRow(homeColumn, "Popular – Movie", "movie");
        addCatalogRow(homeColumn, "Popular – Series", "series");

        TextView credit = new TextView(context);
        credit.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        credit.setTextColor(VibeLayout.text2());
        credit.setGravity(Gravity.CENTER);
        credit.setText("Titles and posters from Cinemeta");
        homeColumn.addView(credit, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 20, 16, 0));
        return fragmentView;
    }

    // ------------------------------------------------------------------ top bar

    private View createTopBar(Context context) {
        LinearLayout bar = new LinearLayout(context);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(12), dp(8), dp(16), dp(8));

        FrameLayout badge = new FrameLayout(context);
        GradientDrawable badgeBg = new GradientDrawable();
        badgeBg.setCornerRadius(dp(14));
        badgeBg.setColor(BLURPLE);
        badge.setBackground(badgeBg);
        TextView emoji = new TextView(context);
        emoji.setText("🎥");
        emoji.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 22);
        emoji.setGravity(Gravity.CENTER);
        badge.addView(emoji, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        badge.setOnClickListener(v -> {
            if (searchColumn.getVisibility() == View.VISIBLE) {
                searchField.setText("");
            } else {
                finishFragment();
            }
        });
        bar.addView(badge, LayoutHelper.createLinear(48, 48));

        FrameLayout pill = new FrameLayout(context);
        GradientDrawable pillBg = new GradientDrawable();
        pillBg.setCornerRadius(dp(14));
        pillBg.setColor(VibeLayout.card());
        pill.setBackground(pillBg);

        searchField = new EditText(context);
        searchField.setBackground(null);
        searchField.setSingleLine(true);
        searchField.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        searchField.setTypeface(VibeLayout.regular());
        searchField.setTextColor(VibeLayout.text());
        searchField.setHintTextColor(VibeLayout.text2());
        searchField.setHint("You can search anything...");
        searchField.setImeOptions(EditorInfo.IME_ACTION_SEARCH | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        searchField.setPadding(dp(16), 0, dp(48), 0);
        searchField.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                AndroidUtilities.cancelRunOnUIThread(searchRunnable);
                if (s.toString().trim().isEmpty()) {
                    showHome();
                } else {
                    AndroidUtilities.runOnUIThread(searchRunnable, 450);
                }
            }
        });
        searchField.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH || event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER) {
                AndroidUtilities.cancelRunOnUIThread(searchRunnable);
                runSearch();
                AndroidUtilities.hideKeyboard(searchField);
                return true;
            }
            return false;
        });
        pill.addView(searchField, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        ImageView icon = new ImageView(context);
        icon.setScaleType(ImageView.ScaleType.CENTER);
        icon.setImageResource(R.drawable.vibe_ic_search);
        icon.setColorFilter(new PorterDuffColorFilter(VibeLayout.text(), PorterDuff.Mode.SRC_IN));
        pill.addView(icon, LayoutHelper.createFrame(48, LayoutHelper.MATCH_PARENT, Gravity.RIGHT));

        bar.addView(pill, LayoutHelper.createLinear(0, 48, 1f, 12, 0, 0, 0));

        // Bots: talk to any bot by username (video replies play here too).
        FrameLayout bots = new FrameLayout(context);
        GradientDrawable botsBg = new GradientDrawable();
        botsBg.setCornerRadius(dp(14));
        botsBg.setColor(VibeLayout.card());
        bots.setBackground(botsBg);
        TextView botsEmoji = new TextView(context);
        botsEmoji.setText("🤖");
        botsEmoji.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 22);
        botsEmoji.setGravity(Gravity.CENTER);
        bots.addView(botsEmoji, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        bots.setContentDescription("Bots");
        bots.setOnClickListener(v -> presentFragment(VibeBotChatActivity.newInstance(null)));
        bar.addView(bots, LayoutHelper.createLinear(48, 48, 8, 0, 0, 0));
        return bar;
    }

    // ------------------------------------------------------------------ rows

    private TextView rowHeader(Context context, String title) {
        TextView t = new TextView(context);
        t.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        t.setTypeface(VibeLayout.boldFont());
        t.setTextColor(VibeLayout.text());
        t.setText(title);
        return t;
    }

    private void addCatalogRow(LinearLayout parent, String title, String type) {
        Context context = parent.getContext();
        parent.addView(rowHeader(context, title), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 18, 16, 10));
        PosterAdapter adapter = new PosterAdapter();
        RecyclerListView list = posterList(context, adapter);
        parent.addView(list, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 196));
        TextView status = new TextView(context);
        status.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        status.setTextColor(VibeLayout.text2());
        status.setText("Loading…");
        parent.addView(status, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 0, 16, 0));
        VibeMovies.loadTop(type, result -> {
            if (result.isEmpty()) {
                status.setText("Couldn't load. Check your connection.");
                list.setVisibility(View.GONE);
                return;
            }
            status.setVisibility(View.GONE);
            adapter.set(result);
        });
    }

    private RecyclerListView posterList(Context context, PosterAdapter adapter) {
        RecyclerListView list = new RecyclerListView(context);
        list.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false));
        list.setClipToPadding(false);
        list.setPadding(dp(12), 0, dp(12), 0);
        list.setAdapter(adapter);
        list.setOnItemClickListener((view, position) -> {
            if (position >= 0 && position < adapter.items.size()) {
                VibeMovieSheet.show(this, adapter.items.get(position));
            }
        });
        return list;
    }

    private void fillContinueWatching() {
        if (continueSection == null) {
            return;
        }
        continueSection.removeAllViews();
        ArrayList<VibeMovies.Watched> watched = VibeMovies.watching();
        if (watched.isEmpty()) {
            return;
        }
        Context context = continueSection.getContext();
        continueSection.addView(rowHeader(context, "Continue Watching"), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 12, 16, 10));
        RecyclerListView list = new RecyclerListView(context);
        list.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false));
        list.setClipToPadding(false);
        list.setPadding(dp(12), 0, dp(12), 0);
        ContinueAdapter adapter = new ContinueAdapter(watched);
        list.setAdapter(adapter);
        list.setOnItemClickListener((view, position) -> resume(watched.get(position)));
        list.setOnItemLongClickListener((view, position) -> {
            VibeMovies.forget(watched.get(position));
            fillContinueWatching();
            return true;
        });
        continueSection.addView(list, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 132));
    }

    /** Movies and episodes in Telegram's downloads list (finished and still downloading). */
    private void fillDownloaded() {
        if (downloadedSection == null) {
            return;
        }
        DownloadController dc = getDownloadController();
        ArrayList<MessageObject> movies = new ArrayList<>();
        java.util.HashSet<String> seen = new java.util.HashSet<>();
        for (int pass = 0; pass < 2; pass++) {
            ArrayList<MessageObject> src = pass == 0 ? dc.downloadingFiles : dc.recentDownloadingFiles;
            for (int i = 0; i < src.size(); i++) {
                MessageObject m = src.get(i);
                if (m != null && VibeLoadingTitleView.isMovie(m) && seen.add(m.getDialogId() + "_" + m.getId())) {
                    movies.add(m);
                }
            }
        }
        if (movies.isEmpty() && !downloadsRequested && dc.recentDownloadingFiles.isEmpty() && dc.downloadingFiles.isEmpty()) {
            downloadsRequested = true;
            dc.loadDownloadingFiles(); // posts onDownloadingFilesChanged when ready
        }
        downloadedSection.removeAllViews();
        downloadedAdapter = null;
        if (movies.isEmpty()) {
            return;
        }
        Context context = downloadedSection.getContext();
        downloadedSection.addView(rowHeader(context, "Downloaded"), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 18, 16, 10));
        RecyclerListView list = new RecyclerListView(context);
        list.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false));
        list.setClipToPadding(false);
        list.setPadding(dp(12), 0, dp(12), 0);
        downloadedAdapter = new DownloadedAdapter(movies);
        list.setAdapter(downloadedAdapter);
        list.setOnItemClickListener((view, position) -> {
            if (position >= 0 && position < movies.size()) {
                MessageObject m = movies.get(position);
                VibeMovies.Meta meta = view instanceof DownloadedCell ? ((DownloadedCell) view).meta : null;
                if (meta != null) {
                    VibeMovies.rememberPoster(m, meta.poster);
                }
                play(this, m, meta);
            }
        });
        downloadedSection.addView(list, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 216));
        AndroidUtilities.cancelRunOnUIThread(progressTicker);
        if (downloadedAdapter.hasLoading()) {
            AndroidUtilities.runOnUIThread(progressTicker, 1500);
        }
    }

    private void resume(VibeMovies.Watched w) {
        VibeMovies.fetchMessage(w.account, w.dialogId, w.messageId, m -> {
            if (m == null || getParentActivity() == null) {
                BulletinFactory.of(this).createSimpleBulletin(R.raw.error, "That video isn't available anymore").show();
                VibeMovies.forget(w);
                fillContinueWatching();
                return;
            }
            play(this, m);
        });
    }

    /** Opens a video message in Vibgram's movie player, full screen. */
    static void play(BaseFragment fragment, MessageObject m) {
        play(fragment, m, null);
    }

    /** [meta], when known, gives the player the title's backdrop and logo for its loading screen. */
    static void play(BaseFragment fragment, MessageObject m, VibeMovies.Meta meta) {
        if (fragment == null || fragment.getParentActivity() == null || m == null) {
            return;
        }
        TLRPC.Document doc = m.getDocument();
        if (doc != null && (m.isVideo() || org.telegram.messenger.VibeConfig.isVideoFile(doc))) {
            VibePlayerActivity.open(fragment, m, meta);
            return;
        }
        PhotoViewer.getInstance().setParentActivity(fragment);
        PhotoViewer.getInstance().openPhoto(m, m.getDialogId(), 0, 0, new PhotoViewer.EmptyPhotoViewerProvider(), true);
    }

    // ------------------------------------------------------------------ search

    private void showHome() {
        lastQuery = "";
        searchColumn.setVisibility(View.GONE);
        homeColumn.setVisibility(View.VISIBLE);
    }

    private void runSearch() {
        String q = searchField.getText().toString().trim();
        if (q.isEmpty() || q.equals(lastQuery)) {
            return;
        }
        lastQuery = q;
        homeColumn.setVisibility(View.GONE);
        searchColumn.setVisibility(View.VISIBLE);
        searchColumn.removeAllViews();
        scroll.scrollTo(0, 0);
        addVideosRow("In your bots", q, true);
        addSearchRow("Movies", "movie", q);
        addSearchRow("Series", "series", q);
        addVideosRow("In your chats", q, false);
    }

    /** Videos you already have: sent by your bots, or in your chats and channels. */
    private void addVideosRow(String title, String q, boolean bots) {
        Context context = searchColumn.getContext();
        LinearLayout section = new LinearLayout(context);
        section.setOrientation(LinearLayout.VERTICAL);
        searchColumn.addView(section, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        section.addView(rowHeader(context, title), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 14, 16, 10));
        TextView status = new TextView(context);
        status.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        status.setTextColor(VibeLayout.text2());
        status.setText("Searching…");
        section.addView(status, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 0, 16, 0));
        VibeMovies.Callback<ArrayList<MessageObject>> show = found -> {
            if (!q.equals(lastQuery) || getParentActivity() == null) {
                return;
            }
            if (found.isEmpty()) {
                status.setText(bots ? "No videos from your bots match this." : "No videos in your chats match this.");
                return;
            }
            status.setVisibility(View.GONE);
            section.addView(videoList(context, found), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 216));
        };
        if (bots) {
            VibeMovies.searchVideosInBots(currentAccount, q, show);
        } else {
            VibeMovies.searchVideosInChats(currentAccount, q, show);
        }
    }

    /** Horizontal row of video cards (thumbnail, then the catalog poster once the title matches). */
    private RecyclerListView videoList(Context context, ArrayList<MessageObject> videos) {
        RecyclerListView list = new RecyclerListView(context);
        list.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false));
        list.setClipToPadding(false);
        list.setPadding(dp(12), 0, dp(12), 0);
        list.setAdapter(new DownloadedAdapter(videos));
        list.setOnItemClickListener((view, position) -> {
            if (position >= 0 && position < videos.size()) {
                MessageObject m = videos.get(position);
                VibeMovies.Meta meta = view instanceof DownloadedCell ? ((DownloadedCell) view).meta : null;
                if (meta != null) {
                    VibeMovies.rememberPoster(m, meta.poster);
                }
                play(this, m, meta);
            }
        });
        return list;
    }

    private void addSearchRow(String title, String type, String q) {
        Context context = searchColumn.getContext();
        LinearLayout section = new LinearLayout(context);
        section.setOrientation(LinearLayout.VERTICAL);
        searchColumn.addView(section, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        section.addView(rowHeader(context, title), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 14, 16, 10));
        PosterAdapter adapter = new PosterAdapter();
        RecyclerListView list = posterList(context, adapter);
        section.addView(list, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 196));
        TextView status = new TextView(context);
        status.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        status.setTextColor(VibeLayout.text2());
        status.setText("Searching…");
        section.addView(status, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 0, 16, 0));
        VibeMovies.search(type, q, result -> {
            if (!q.equals(lastQuery)) {
                return;
            }
            if (result.isEmpty()) {
                status.setText("Nothing found");
                list.setVisibility(View.GONE);
                return;
            }
            status.setVisibility(View.GONE);
            adapter.set(result);
        });
    }

    // ------------------------------------------------------------------ adapters

    private static class PosterAdapter extends RecyclerListView.SelectionAdapter {
        final ArrayList<VibeMovies.Meta> items = new ArrayList<>();

        void set(ArrayList<VibeMovies.Meta> list) {
            items.clear();
            items.addAll(list);
            notifyDataSetChanged();
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return true;
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            PosterCell cell = new PosterCell(parent.getContext());
            cell.setLayoutParams(new RecyclerView.LayoutParams(dp(128), dp(196)));
            return new RecyclerListView.Holder(cell);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            ((PosterCell) holder.itemView).bind(items.get(position));
        }
    }

    /** 2:3 poster with rounded corners; the title shows until the picture loads. */
    private static class PosterCell extends FrameLayout {
        final BackupImageView image;
        final TextView fallback;

        PosterCell(Context context) {
            super(context);
            setPadding(dp(4), 0, dp(4), 0);
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(14));
            bg.setColor(VibeLayout.card());
            fallback = new TextView(context);
            fallback.setBackground(bg);
            fallback.setGravity(Gravity.CENTER);
            fallback.setPadding(dp(8), 0, dp(8), 0);
            fallback.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            fallback.setTypeface(VibeLayout.boldFont());
            fallback.setTextColor(VibeLayout.text2());
            fallback.setMaxLines(4);
            fallback.setEllipsize(TextUtils.TruncateAt.END);
            addView(fallback, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
            image = new BackupImageView(context);
            image.setRoundRadius(dp(14));
            addView(image, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        }

        void bind(VibeMovies.Meta m) {
            fallback.setText(m.name);
            image.setImage(m.poster, "240_360", null);
        }
    }

    private class DownloadedAdapter extends RecyclerListView.SelectionAdapter {
        final ArrayList<MessageObject> items;

        DownloadedAdapter(ArrayList<MessageObject> items) {
            this.items = items;
        }

        boolean hasLoading() {
            for (MessageObject m : items) {
                if (getFileLoader().isLoadingFile(m.getFileName())) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return true;
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            DownloadedCell cell = new DownloadedCell(parent.getContext());
            cell.setLayoutParams(new RecyclerView.LayoutParams(dp(128), dp(216)));
            return new RecyclerListView.Holder(cell);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            MessageObject m = items.get(position);
            Float p = getFileLoader().isLoadingFile(m.getFileName()) ? org.telegram.messenger.ImageLoader.getInstance().getFileProgress(m.getFileName()) : null;
            ((DownloadedCell) holder.itemView).bind(m, p == null ? -1f : p);
        }
    }

    /**
     * Poster-shaped card for a downloaded video: the video's own thumbnail first,
     * swapped for the real poster once the title is matched in the catalog.
     * Shows a progress bar while it is still downloading.
     */
    private static class DownloadedCell extends FrameLayout {
        final BackupImageView image;
        final TextView title;
        final TextView size;
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        final RectF rect = new RectF();
        MessageObject message;
        VibeMovies.Meta meta;
        float progress = -1f;

        DownloadedCell(Context context) {
            super(context);
            setWillNotDraw(false);
            setPadding(dp(4), 0, dp(4), 0);
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(14));
            bg.setColor(VibeLayout.card());
            View back = new View(context);
            back.setBackground(bg);
            addView(back, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 180, Gravity.TOP));
            image = new BackupImageView(context);
            image.setRoundRadius(dp(14));
            addView(image, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 180, Gravity.TOP));
            title = new TextView(context);
            title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            title.setTypeface(VibeLayout.boldFont());
            title.setTextColor(VibeLayout.text());
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.END);
            addView(title, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM, 2, 0, 2, 16));
            size = new TextView(context);
            size.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
            size.setTextColor(VibeLayout.text2());
            size.setSingleLine(true);
            addView(size, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM, 2, 0, 2, 0));
        }

        void bind(MessageObject m, float loadProgress) {
            progress = loadProgress;
            String clean = VibeLoadingTitleView.titleOf(m);
            title.setText(clean);
            size.setText(loadProgress >= 0
                    ? (int) (loadProgress * 100) + "% · " + AndroidUtilities.formatFileSize(m.getSize())
                    : AndroidUtilities.formatFileSize(m.getSize()));
            invalidate();
            if (message == m) {
                return; // keep the picture; only progress changed
            }
            message = m;
            meta = null;
            setThumb(m);
            VibeMovies.matchTitle(clean, found -> {
                if (message != m || found == null || TextUtils.isEmpty(found.poster)) {
                    return;
                }
                meta = found;
                image.setImage(found.poster, "240_360", null);
            });
        }

        private void setThumb(MessageObject m) {
            TLRPC.Document doc = m.getDocument();
            if (doc == null || doc.thumbs == null || doc.thumbs.isEmpty()) {
                image.setImageDrawable(null);
                return;
            }
            TLRPC.PhotoSize big = FileLoader.getClosestPhotoSizeWithSize(doc.thumbs, 320);
            TLRPC.PhotoSize stripped = null;
            for (TLRPC.PhotoSize s : doc.thumbs) {
                if (s instanceof TLRPC.TL_photoStrippedSize) {
                    stripped = s;
                    break;
                }
            }
            image.setImage(ImageLocation.getForDocument(big, doc), "240_360", stripped != null ? ImageLocation.getForDocument(stripped, doc) : null, "b", 0, m);
        }

        @Override
        protected void dispatchDraw(Canvas canvas) {
            super.dispatchDraw(canvas);
            if (progress < 0) {
                return;
            }
            float l = getPaddingLeft() + dp(10);
            float r = getWidth() - getPaddingRight() - dp(10);
            float y = dp(180) - dp(10);
            paint.setColor(0x80000000);
            rect.set(l, y, r, y + dp(4));
            canvas.drawRoundRect(rect, dp(2), dp(2), paint);
            paint.setColor(BLURPLE);
            rect.set(l, y, l + (r - l) * Math.max(0f, Math.min(1f, progress)), y + dp(4));
            canvas.drawRoundRect(rect, dp(2), dp(2), paint);
        }
    }

    private static class ContinueAdapter extends RecyclerListView.SelectionAdapter {
        final ArrayList<VibeMovies.Watched> items;

        ContinueAdapter(ArrayList<VibeMovies.Watched> items) {
            this.items = items;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return true;
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            ContinueCell cell = new ContinueCell(parent.getContext());
            cell.setLayoutParams(new RecyclerView.LayoutParams(dp(208), dp(132)));
            return new RecyclerListView.Holder(cell);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            ((ContinueCell) holder.itemView).bind(items.get(position));
        }
    }

    /** Wide card: poster (if known) behind the title, with a progress bar along the bottom. */
    private static class ContinueCell extends FrameLayout {
        final BackupImageView image;
        final TextView title;
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        final RectF rect = new RectF();
        float progress;

        ContinueCell(Context context) {
            super(context);
            setWillNotDraw(false);
            setPadding(dp(4), 0, dp(4), 0);
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(14));
            bg.setColor(VibeLayout.card());
            View back = new View(context);
            back.setBackground(bg);
            addView(back, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
            image = new BackupImageView(context);
            image.setRoundRadius(dp(14));
            addView(image, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
            View shade = new View(context);
            GradientDrawable sh = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x00000000, 0xC0000000});
            sh.setCornerRadius(dp(14));
            shade.setBackground(sh);
            addView(shade, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
            title = new TextView(context);
            title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            title.setTypeface(VibeLayout.boldFont());
            title.setTextColor(0xFFFFFFFF);
            title.setMaxLines(2);
            title.setEllipsize(TextUtils.TruncateAt.END);
            addView(title, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM, 12, 0, 12, 18));
        }

        void bind(VibeMovies.Watched w) {
            title.setText(w.title);
            progress = w.progress;
            if (w.poster != null) {
                image.setVisibility(VISIBLE);
                image.setImage(w.poster, "420_260", null);
            } else {
                image.setVisibility(GONE);
            }
            invalidate();
        }

        @Override
        protected void dispatchDraw(Canvas canvas) {
            super.dispatchDraw(canvas);
            float l = getPaddingLeft() + dp(12);
            float r = getWidth() - getPaddingRight() - dp(12);
            float y = getHeight() - dp(9);
            paint.setColor(0x59FFFFFF);
            rect.set(l, y, r, y + dp(3));
            canvas.drawRoundRect(rect, dp(2), dp(2), paint);
            paint.setColor(0xFFFFFFFF);
            rect.set(l, y, l + (r - l) * progress, y + dp(3));
            canvas.drawRoundRect(rect, dp(2), dp(2), paint);
        }
    }
}
