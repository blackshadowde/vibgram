package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.VibeLayout;
import org.telegram.messenger.VibeMovies;
import org.telegram.messenger.browser.Browser;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.VibeLoadingTitleView;

import java.util.ArrayList;
import java.util.HashSet;

/**
 * Vibgram: a title's page — backdrop, logo/name, year, genres, plot, a trailer button,
 * and the videos with that title already in your chats.
 */
@SuppressLint("ViewConstructor")
public class VibeMovieSheet extends BottomSheet {

    private static final int BLURPLE = 0xFF5865F2;

    private final BaseFragment fragment;
    private final VibeMovies.Meta meta;
    private final int account;
    private final LinearLayout results;
    private final TextView resultsStatus;
    private int pending;
    private final ArrayList<MessageObject> found = new ArrayList<>();
    private final HashSet<String> seen = new HashSet<>();

    public static void show(BaseFragment fragment, VibeMovies.Meta meta) {
        if (fragment == null || fragment.getParentActivity() == null || meta == null) {
            return;
        }
        new VibeMovieSheet(fragment, meta).show();
    }

    private VibeMovieSheet(BaseFragment fragment, VibeMovies.Meta meta) {
        super(fragment.getParentActivity(), false);
        this.fragment = fragment;
        this.meta = meta;
        this.account = fragment.getCurrentAccount();
        Context context = fragment.getParentActivity();
        setApplyTopPadding(false);
        setBackgroundColor(VibeLayout.bg());

        ScrollView scroll = new ScrollView(context);
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(root);

        // ---- Backdrop with the logo (or name) over it
        int heroHeight = Math.min((int) (AndroidUtilities.displaySize.x * 0.62f), dp(280));
        FrameLayout hero = new FrameLayout(context);
        BackupImageView backdrop = new BackupImageView(context);
        backdrop.setRoundRadius(dp(24), dp(24), 0, 0);
        String back = !TextUtils.isEmpty(meta.background) ? meta.background : meta.poster;
        backdrop.setImage(back, "800_450", null);
        hero.addView(backdrop, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        View shade = new View(context);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x00000000, 0x33000000, 0xD0000000}));
        hero.addView(shade, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        TextView name = new TextView(context);
        name.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 28);
        name.setTypeface(VibeLayout.boldFont());
        name.setTextColor(0xFFFFFFFF);
        name.setShadowLayer(dp(4), 0, dp(1), 0x80000000);
        name.setMaxLines(2);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setText(meta.name);
        hero.addView(name, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM, 20, 0, 20, 16));

        if (!TextUtils.isEmpty(meta.logo)) {
            // The real title art, like Stremio. The plain name stays until it loads.
            BackupImageView logo = new BackupImageView(context);
            logo.getImageReceiver().setAspectFit(true);
            logo.getImageReceiver().setDelegate((imageReceiver, set, thumb, memCache) -> {
                if (set && !thumb && imageReceiver.getBitmap() != null) {
                    name.setVisibility(View.INVISIBLE);
                }
            });
            logo.setImage(meta.logo, null, null);
            hero.addView(logo, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 72, Gravity.BOTTOM | Gravity.LEFT, 20, 0, 80, 14));
        }
        root.addView(hero, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, (int) (heroHeight / AndroidUtilities.density)));

        // ---- Facts line
        StringBuilder facts = new StringBuilder();
        append(facts, meta.year);
        append(facts, meta.runtime);
        if (!TextUtils.isEmpty(meta.rating)) {
            append(facts, "★ " + meta.rating);
        }
        append(facts, meta.genres);
        if (facts.length() > 0) {
            TextView f = new TextView(context);
            f.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            f.setTextColor(VibeLayout.text2());
            f.setText(facts);
            root.addView(f, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 20, 14, 20, 0));
        }

        // ---- Buttons
        LinearLayout buttons = new LinearLayout(context);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        TextView findButton = button(context, "Find in my chats", true);
        findButton.setOnClickListener(v -> searchChats());
        buttons.addView(findButton, LayoutHelper.createLinear(0, 46, 1f));
        if (!TextUtils.isEmpty(meta.trailer)) {
            TextView trailer = button(context, "Trailer", false);
            trailer.setOnClickListener(v -> Browser.openUrl(context, "https://www.youtube.com/watch?v=" + meta.trailer));
            buttons.addView(trailer, LayoutHelper.createLinear(0, 46, 0.6f, 10, 0, 0, 0));
        }
        root.addView(buttons, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 20, 16, 20, 0));

        // ---- Plot
        if (!TextUtils.isEmpty(meta.description)) {
            TextView plot = new TextView(context);
            plot.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            plot.setTextColor(VibeLayout.text());
            plot.setLineSpacing(dp(2), 1f);
            plot.setText(meta.description);
            root.addView(plot, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 20, 16, 20, 0));
        }

        // ---- Where to watch (streaming services in your country)
        whereBox = new LinearLayout(context);
        whereBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(whereBox, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 20, 18, 20, 0));
        loadWhereToWatch();

        // ---- Videos in your chats
        results = new LinearLayout(context);
        results.setOrientation(LinearLayout.VERTICAL);
        root.addView(results, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 12, 12, 12, 0));
        resultsStatus = new TextView(context);
        resultsStatus.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        resultsStatus.setTextColor(VibeLayout.text2());
        resultsStatus.setVisibility(View.GONE);
        root.addView(resultsStatus, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 20, 8, 20, 24));

        setCustomView(scroll);
    }

    // ------------------------------------------------------------------ where to watch

    private LinearLayout whereBox;

    private TextView smallHeader(Context context, String text) {
        TextView h = new TextView(context);
        h.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        h.setTypeface(VibeLayout.boldFont());
        h.setTextColor(VibeLayout.text2());
        h.setText(text);
        return h;
    }

    private void loadWhereToWatch() {
        Context context = whereBox.getContext();
        whereBox.removeAllViews();
        whereBox.addView(smallHeader(context, "WHERE TO WATCH · " + VibeMovies.region()));
        TextView status = new TextView(context);
        status.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        status.setTextColor(VibeLayout.text2());
        status.setText("Checking streaming services…");
        whereBox.addView(status, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 8, 0, 0));
        VibeMovies.whereToWatch(meta, w -> {
            whereBox.removeView(status);
            if (w.error != null || w.isEmpty()) {
                TextView note = new TextView(context);
                note.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
                note.setTextColor(VibeLayout.text2());
                if ("no_key".equals(w.error)) {
                    note.setText("Add a free TMDB key in Settings → Vibgram customizations to see services here.");
                } else if ("bad_key".equals(w.error)) {
                    note.setText("Your TMDB key was refused. Check it in Settings → Vibgram customizations.");
                } else if (w.error != null) {
                    note.setText("Couldn't check services right now.");
                } else {
                    note.setText("Not on any streaming service here yet.");
                }
                whereBox.addView(note, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 8, 0, 0));
            } else {
                addProviderRow(context, "Stream", w.stream, w.link);
                addProviderRow(context, "Free", w.free, w.link);
                addProviderRow(context, "Rent", w.rent, w.link);
                addProviderRow(context, "Buy", w.buy, w.link);
                TextView credit = new TextView(context);
                credit.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
                credit.setTextColor(VibeLayout.text2());
                credit.setText("Streaming data from JustWatch, via TMDB");
                whereBox.addView(credit, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 8, 0, 0));
            }
            // Always works, no key needed.
            TextView jw = button(context, "Open in JustWatch", false);
            jw.setOnClickListener(v -> Browser.openUrl(context, VibeMovies.justWatchUrl(meta)));
            whereBox.addView(jw, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 42, 0, 10, 0, 0));
        });
    }

    private void addProviderRow(Context context, String label, ArrayList<VibeMovies.Provider> list, String link) {
        if (list.isEmpty()) {
            return;
        }
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView l = new TextView(context);
        l.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        l.setTypeface(VibeLayout.boldFont());
        l.setTextColor(VibeLayout.text());
        l.setText(label);
        row.addView(l, LayoutHelper.createLinear(52, LayoutHelper.WRAP_CONTENT));
        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(context);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout chips = new LinearLayout(context);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        hs.addView(chips);
        for (VibeMovies.Provider p : list) {
            LinearLayout chip = new LinearLayout(context);
            chip.setOrientation(LinearLayout.HORIZONTAL);
            chip.setGravity(Gravity.CENTER_VERTICAL);
            chip.setPadding(dp(4), dp(4), dp(10), dp(4));
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(10));
            bg.setColor(VibeLayout.card());
            chip.setBackground(bg);
            BackupImageView logo = new BackupImageView(context);
            logo.setRoundRadius(dp(7));
            if (p.logo != null) {
                logo.setImage(p.logo, "56_56", null);
            }
            chip.addView(logo, LayoutHelper.createLinear(28, 28));
            TextView n = new TextView(context);
            n.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            n.setTextColor(VibeLayout.text());
            n.setSingleLine(true);
            n.setText(p.name);
            chip.addView(n, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 8, 0, 0, 0));
            if (link != null) {
                chip.setOnClickListener(v -> Browser.openUrl(context, link));
            }
            chips.addView(chip, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 0, 8, 0));
        }
        row.addView(hs, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
        whereBox.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 8, 0, 0));
    }

    private static void append(StringBuilder sb, String s) {
        if (TextUtils.isEmpty(s)) {
            return;
        }
        if (sb.length() > 0) {
            sb.append("   ");
        }
        sb.append(s);
    }

    private TextView button(Context context, String text, boolean primary) {
        TextView b = new TextView(context);
        b.setGravity(Gravity.CENTER);
        b.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        b.setTypeface(VibeLayout.boldFont());
        b.setTextColor(primary ? 0xFFFFFFFF : VibeLayout.text());
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(12));
        bg.setColor(primary ? BLURPLE : VibeLayout.cardHigh());
        b.setBackground(bg);
        b.setText(text);
        return b;
    }

    // ------------------------------------------------------------------ search in chats

    /** Telegram's normal message search over your chats, groups and channels, videos and files only. */
    private void searchChats() {
        if (pending > 0) {
            return;
        }
        results.removeAllViews();
        found.clear();
        seen.clear();
        resultsStatus.setVisibility(View.VISIBLE);
        resultsStatus.setText("Looking in your chats…");
        pending = 2;
        search(new TLRPC.TL_inputMessagesFilterVideo());
        search(new TLRPC.TL_inputMessagesFilterDocument());
    }

    private void search(TLRPC.MessagesFilter filter) {
        TLRPC.TL_messages_searchGlobal req = new TLRPC.TL_messages_searchGlobal();
        req.q = meta.name;
        req.filter = filter;
        req.limit = 40;
        req.offset_rate = 0;
        req.offset_id = 0;
        req.offset_peer = new TLRPC.TL_inputPeerEmpty();
        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (response instanceof TLRPC.messages_Messages) {
                TLRPC.messages_Messages res = (TLRPC.messages_Messages) response;
                MessagesController mc = MessagesController.getInstance(account);
                mc.putUsers(res.users, false);
                mc.putChats(res.chats, false);
                for (TLRPC.Message msg : res.messages) {
                    MessageObject m = new MessageObject(account, msg, false, true);
                    String key = m.getDialogId() + "_" + m.getId();
                    if (m.isVideo() && seen.add(key)) {
                        found.add(m);
                    }
                }
            }
            if (--pending == 0) {
                showResults();
            }
        }));
    }

    private void showResults() {
        if (found.isEmpty()) {
            resultsStatus.setText("No videos named “" + meta.name + "” in your chats.");
            return;
        }
        resultsStatus.setVisibility(View.GONE);
        // Biggest files first: usually the best quality.
        java.util.Collections.sort(found, (a, b) -> Long.compare(b.getSize(), a.getSize()));
        Context context = results.getContext();
        TextView header = new TextView(context);
        header.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        header.setTypeface(VibeLayout.boldFont());
        header.setTextColor(VibeLayout.text2());
        header.setText("IN YOUR CHATS — " + found.size());
        results.addView(header, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 8, 4, 8, 6));
        for (MessageObject m : found) {
            results.addView(resultRow(context, m), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 6));
        }
    }

    private View resultRow(Context context, MessageObject m) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(10), dp(12), dp(10));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(12));
        bg.setColor(VibeLayout.card());
        row.setBackground(bg);

        ImageView play = new ImageView(context);
        play.setScaleType(ImageView.ScaleType.CENTER);
        play.setImageResource(R.drawable.ic_action_play);
        play.setColorFilter(new PorterDuffColorFilter(0xFFFFFFFF, PorterDuff.Mode.SRC_IN));
        GradientDrawable playBg = new GradientDrawable();
        playBg.setShape(GradientDrawable.OVAL);
        playBg.setColor(BLURPLE);
        play.setBackground(playBg);
        row.addView(play, LayoutHelper.createLinear(40, 40));

        LinearLayout text = new LinearLayout(context);
        text.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(context);
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        title.setTypeface(VibeLayout.boldFont());
        title.setTextColor(VibeLayout.text());
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        String file = m.getDocument() != null ? FileLoader.getDocumentFileName(m.getDocument()) : null;
        title.setText(!TextUtils.isEmpty(file) ? file : VibeLoadingTitleView.titleOf(m));
        text.addView(title);
        TextView sub = new TextView(context);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        sub.setTextColor(VibeLayout.text2());
        sub.setSingleLine(true);
        sub.setEllipsize(TextUtils.TruncateAt.END);
        StringBuilder s = new StringBuilder(AndroidUtilities.formatFileSize(m.getSize()));
        int duration = (int) m.getDuration();
        if (duration > 0) {
            s.append(" · ").append(AndroidUtilities.formatShortDuration(duration));
        }
        String where = chatName(m.getDialogId());
        if (where != null) {
            s.append(" · ").append(where);
        }
        sub.setText(s);
        text.addView(sub);
        row.addView(text, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 12, 0, 0, 0));

        row.setOnClickListener(v -> {
            VibeMovies.rememberPoster(m, meta.poster);
            dismiss();
            VibeMoviesActivity.play(fragment, m);
        });
        return row;
    }

    private String chatName(long dialogId) {
        MessagesController mc = MessagesController.getInstance(account);
        if (dialogId > 0) {
            TLRPC.User u = mc.getUser(dialogId);
            return u != null ? UserObject.getUserName(u) : null;
        }
        TLRPC.Chat c = mc.getChat(-dialogId);
        return c != null ? c.title : null;
    }
}
