package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;

/**
 * Vibgram 🎥 Movies: catalog data from Cinemeta (Stremio's public metadata service —
 * titles, posters and descriptions only, no video), plus the "Continue Watching" list
 * of long videos you played in Vibgram.
 */
public class VibeMovies {

    private static final String BASE = "https://v3-cinemeta.strem.io";

    public static class Meta {
        public String id;
        public String type;
        public String name;
        public String poster;
        public String background;
        public String logo;
        public String year;
        public String rating;
        public String description;
        public String runtime;
        public String genres;
        public String trailer; // YouTube id

        static Meta from(JSONObject o) {
            Meta m = new Meta();
            m.id = o.optString("id");
            m.type = o.optString("type");
            m.name = o.optString("name");
            m.poster = o.optString("poster", null);
            m.background = o.optString("background", null);
            m.logo = o.optString("logo", null);
            m.year = o.optString("releaseInfo", o.optString("year", ""));
            m.rating = o.optString("imdbRating", "");
            m.description = o.optString("description", "");
            m.runtime = o.optString("runtime", "");
            JSONArray g = o.optJSONArray("genres");
            if (g != null) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < g.length() && i < 3; i++) {
                    if (sb.length() > 0) {
                        sb.append(" · ");
                    }
                    sb.append(g.optString(i));
                }
                m.genres = sb.toString();
            }
            JSONArray t = o.optJSONArray("trailerStreams");
            if (t != null && t.length() > 0) {
                m.trailer = t.optJSONObject(0).optString("ytId", null);
            }
            if (m.trailer == null) {
                JSONArray tr = o.optJSONArray("trailers");
                if (tr != null && tr.length() > 0) {
                    m.trailer = tr.optJSONObject(0).optString("source", null);
                }
            }
            if (TextUtils.isEmpty(m.poster) && !TextUtils.isEmpty(m.id) && m.id.startsWith("tt")) {
                m.poster = "https://images.metahub.space/poster/small/" + m.id + "/img";
            }
            return m;
        }
    }

    public interface Callback<T> {
        void run(T result);
    }

    private static final HashMap<String, ArrayList<Meta>> cache = new HashMap<>();

    /** type: "movie" or "series". Result on the UI thread; empty list on error. */
    public static void loadTop(String type, Callback<ArrayList<Meta>> done) {
        load(BASE + "/catalog/" + type + "/top.json", done);
    }

    /**
     * Any Cinemeta catalog. catalog: "top" (popular), "imdbRating" (top rated) or "year" (new;
     * genre must be a year). genre: null, a genre like "Action", or a year like "2026".
     */
    public static void loadCatalog(String type, String catalog, String genre, Callback<ArrayList<Meta>> done) {
        String url = BASE + "/catalog/" + type + "/" + catalog;
        if (!TextUtils.isEmpty(genre)) {
            try {
                url += "/genre=" + URLEncoder.encode(genre, "UTF-8").replace("+", "%20");
            } catch (Exception e) {
                url += "/genre=" + genre;
            }
        }
        load(url + ".json", done);
    }

    public static void search(String type, String query, Callback<ArrayList<Meta>> done) {
        String q;
        try {
            q = URLEncoder.encode(query.trim(), "UTF-8").replace("+", "%20");
        } catch (Exception e) {
            q = query.trim();
        }
        load(BASE + "/catalog/" + type + "/top/search=" + q + ".json", done);
    }

    private static void load(String url, Callback<ArrayList<Meta>> done) {
        synchronized (cache) {
            ArrayList<Meta> hit = cache.get(url);
            if (hit != null) {
                done.run(hit);
                return;
            }
        }
        Utilities.globalQueue.postRunnable(() -> {
            ArrayList<Meta> list = new ArrayList<>();
            try {
                JSONObject json = new JSONObject(get(url));
                JSONArray metas = json.optJSONArray("metas");
                if (metas != null) {
                    for (int i = 0; i < metas.length(); i++) {
                        JSONObject o = metas.optJSONObject(i);
                        if (o != null && !TextUtils.isEmpty(o.optString("name"))) {
                            list.add(Meta.from(o));
                        }
                    }
                }
                if (!list.isEmpty()) {
                    synchronized (cache) {
                        cache.put(url, list);
                    }
                }
            } catch (Throwable e) {
                FileLog.e(e);
            }
            AndroidUtilities.runOnUIThread(() -> done.run(list));
        });
    }

    private static String get(String url) throws Exception {
        return get(url, null);
    }

    private static String get(String url, String bearer) throws Exception {
        for (int hop = 0; hop < 4; hop++) {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(15000);
            c.setInstanceFollowRedirects(false);
            c.setRequestProperty("Accept", "application/json");
            if (bearer != null) {
                c.setRequestProperty("Authorization", "Bearer " + bearer);
            }
            int code = c.getResponseCode();
            if (code == 401) {
                c.disconnect();
                throw new HttpError(401);
            }
            if (code >= 400) {
                c.disconnect();
                throw new HttpError(code);
            }
            if (code >= 300 && code < 400) {
                String next = c.getHeaderField("Location");
                c.disconnect();
                if (next == null) {
                    break;
                }
                url = new URL(new URL(url), next).toString();
                continue;
            }
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
                return out.toString("UTF-8");
            } finally {
                c.disconnect();
            }
        }
        throw new Exception("too many redirects");
    }

    static class HttpError extends Exception {
        final int code;

        HttpError(int code) {
            super("HTTP " + code);
            this.code = code;
        }
    }

    // ------------------------------------------------------------------ where to watch (TMDB, data by JustWatch)

    public static class Provider {
        public String name;
        public String logo;
    }

    public static class WhereToWatch {
        public String region;
        /** TMDB's watch page for the title; it links on to each service. */
        public String link;
        public final ArrayList<Provider> stream = new ArrayList<>();
        public final ArrayList<Provider> free = new ArrayList<>();
        public final ArrayList<Provider> rent = new ArrayList<>();
        public final ArrayList<Provider> buy = new ArrayList<>();
        /** Set when nothing could be loaded: "no_key", "bad_key" or "error". */
        public String error;

        public boolean isEmpty() {
            return stream.isEmpty() && free.isEmpty() && rent.isEmpty() && buy.isEmpty();
        }
    }

    /** Country for "where to watch": the phone's region, India if unknown. */
    public static String region() {
        String c = java.util.Locale.getDefault().getCountry();
        return TextUtils.isEmpty(c) || c.length() != 2 ? "IN" : c.toUpperCase();
    }

    public static void whereToWatch(Meta meta, Callback<WhereToWatch> done) {
        WhereToWatch result = new WhereToWatch();
        result.region = region();
        String key = VibeConfig.tmdbKey();
        if (TextUtils.isEmpty(key)) {
            result.error = "no_key";
            done.run(result);
            return;
        }
        if (meta == null || TextUtils.isEmpty(meta.id) || !meta.id.startsWith("tt")) {
            result.error = "error";
            done.run(result);
            return;
        }
        // A v4 read token is long (a JWT); a v3 key is 32 characters and goes in the URL.
        final boolean bearer = key.length() > 40;
        final String auth = bearer ? "" : "api_key=" + key + "&";
        Utilities.globalQueue.postRunnable(() -> {
            try {
                JSONObject find = new JSONObject(get("https://api.themoviedb.org/3/find/" + meta.id + "?" + auth + "external_source=imdb_id", bearer ? key : null));
                String kind = "movie";
                JSONArray hits = find.optJSONArray("movie_results");
                if (hits == null || hits.length() == 0) {
                    hits = find.optJSONArray("tv_results");
                    kind = "tv";
                }
                if (hits != null && hits.length() > 0) {
                    long tmdbId = hits.getJSONObject(0).optLong("id");
                    JSONObject all = new JSONObject(get("https://api.themoviedb.org/3/" + kind + "/" + tmdbId + "/watch/providers?" + auth, bearer ? key : null));
                    JSONObject here = all.optJSONObject("results") != null ? all.optJSONObject("results").optJSONObject(result.region) : null;
                    if (here != null) {
                        result.link = here.optString("link", null);
                        readProviders(here.optJSONArray("flatrate"), result.stream);
                        readProviders(here.optJSONArray("free"), result.free);
                        readProviders(here.optJSONArray("ads"), result.free);
                        readProviders(here.optJSONArray("rent"), result.rent);
                        readProviders(here.optJSONArray("buy"), result.buy);
                    }
                }
            } catch (HttpError e) {
                result.error = e.code == 401 ? "bad_key" : "error";
            } catch (Throwable e) {
                FileLog.e(e);
                result.error = "error";
            }
            AndroidUtilities.runOnUIThread(() -> done.run(result));
        });
    }

    private static void readProviders(JSONArray a, ArrayList<Provider> into) {
        if (a == null) {
            return;
        }
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) {
                continue;
            }
            Provider p = new Provider();
            p.name = o.optString("provider_name");
            String logo = o.optString("logo_path", null);
            p.logo = logo != null && !"null".equals(logo) ? "https://image.tmdb.org/t/p/w92" + logo : null;
            boolean dup = false;
            for (Provider q : into) {
                if (q.name.equals(p.name)) {
                    dup = true;
                    break;
                }
            }
            if (!dup) {
                into.add(p);
            }
        }
    }

    /** JustWatch's own search page; works without any key. */
    public static String justWatchUrl(Meta meta) {
        String q;
        try {
            q = URLEncoder.encode(meta.name, "UTF-8");
        } catch (Exception e) {
            q = meta.name;
        }
        return "https://www.justwatch.com/" + region().toLowerCase() + "/search?q=" + q;
    }

    // ------------------------------------------------------------------ posters for downloaded videos

    private static final HashMap<String, Meta> posterMatches = new HashMap<>();

    private static String norm(String s) {
        return s == null ? "" : s.toLowerCase().replaceAll("[^a-z0-9]+", " ").trim();
    }

    /**
     * Finds the catalog entry for a downloaded file's cleaned title, e.g. "The Furious (2025)"
     * or "Paradise · S01E03". Gives null when there is no confident match.
     */
    public static void matchTitle(String cleanTitle, Callback<Meta> done) {
        if (TextUtils.isEmpty(cleanTitle)) {
            done.run(null);
            return;
        }
        synchronized (posterMatches) {
            if (posterMatches.containsKey(cleanTitle)) {
                done.run(posterMatches.get(cleanTitle));
                return;
            }
        }
        String name = cleanTitle;
        String year = null;
        boolean series = false;
        int dot = name.indexOf(" · ");
        if (dot > 0) {
            name = name.substring(0, dot);
            series = true;
        }
        java.util.regex.Matcher y = java.util.regex.Pattern.compile("\\s*\\(((19|20)\\d{2})\\)$").matcher(name);
        if (y.find()) {
            year = y.group(1);
            name = name.substring(0, y.start());
        }
        final String wanted = norm(name);
        final String wantedYear = year;
        search(series ? "series" : "movie", name, list -> {
            Meta best = null;
            for (Meta m : list) {
                if (!norm(m.name).equals(wanted)) {
                    continue;
                }
                if (wantedYear == null || m.year == null || m.year.startsWith(wantedYear)) {
                    best = m;
                    break;
                }
            }
            synchronized (posterMatches) {
                posterMatches.put(cleanTitle, best);
            }
            done.run(best);
        });
    }

    // ------------------------------------------------------------------ continue watching

    public static class Watched {
        public int account;
        public long dialogId;
        public int messageId;
        public String title;
        public float progress;
        public long updated;
        public String poster;

        String key() {
            return account + "_" + dialogId + "_" + messageId;
        }
    }

    private static final String PREFS = "vibgram_watching";
    private static final int MAX_WATCHED = 20;

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static ArrayList<Watched> watching() {
        ArrayList<Watched> list = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs().getString("list", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Watched w = new Watched();
                w.account = o.optInt("a");
                w.dialogId = o.optLong("d");
                w.messageId = o.optInt("m");
                w.title = o.optString("t");
                w.progress = (float) o.optDouble("p", 0);
                w.updated = o.optLong("u");
                w.poster = o.optString("i", null);
                list.add(w);
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
        return list;
    }

    private static void store(ArrayList<Watched> list) {
        try {
            JSONArray a = new JSONArray();
            for (int i = 0; i < list.size() && i < MAX_WATCHED; i++) {
                Watched w = list.get(i);
                JSONObject o = new JSONObject();
                o.put("a", w.account);
                o.put("d", w.dialogId);
                o.put("m", w.messageId);
                o.put("t", w.title);
                o.put("p", w.progress);
                o.put("u", w.updated);
                if (w.poster != null) {
                    o.put("i", w.poster);
                }
                a.put(o);
            }
            prefs().edit().putString("list", a.toString()).apply();
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    /** Posters remembered for files opened from a movie page, keyed like Watched. */
    private static final HashMap<String, String> pendingPosters = new HashMap<>();

    public static void rememberPoster(MessageObject m, String poster) {
        if (m == null || poster == null) {
            return;
        }
        pendingPosters.put(m.currentAccount + "_" + m.getDialogId() + "_" + m.getId(), poster);
    }

    /** Called by the player when a long video closes. Finished videos drop off the list. */
    public static void saveProgress(MessageObject m, String title, long positionMs, long durationMs) {
        if (m == null || durationMs <= 0 || m.getId() <= 0) {
            return;
        }
        float p = Math.max(0f, Math.min(1f, positionMs / (float) durationMs));
        Watched w = new Watched();
        w.account = m.currentAccount;
        w.dialogId = m.getDialogId();
        w.messageId = m.getId();
        w.title = title;
        w.progress = p;
        w.updated = System.currentTimeMillis();
        ArrayList<Watched> list = watching();
        for (int i = list.size() - 1; i >= 0; i--) {
            if (list.get(i).key().equals(w.key())) {
                if (w.poster == null) {
                    w.poster = list.get(i).poster;
                }
                list.remove(i);
            }
        }
        String pending = pendingPosters.get(w.key());
        if (pending != null) {
            w.poster = pending;
        }
        if (p < 0.95f && positionMs > 30_000) {
            list.add(0, w);
        }
        store(list);
    }

    public static void forget(Watched w) {
        ArrayList<Watched> list = watching();
        for (int i = list.size() - 1; i >= 0; i--) {
            if (list.get(i).key().equals(w.key())) {
                list.remove(i);
            }
        }
        store(list);
    }

    // ------------------------------------------------------------------ videos in your bots and chats

    private static final int MAX_BOTS_SEARCHED = 25;

    /**
     * Finds videos matching [query] that bots already sent you: searches each bot chat in your chat
     * list (plus bots opened from the Bots screen), videos and video files alike.
     * Biggest files first. Result on the UI thread; empty on error.
     */
    public static void searchVideosInBots(int account, String query, Callback<ArrayList<MessageObject>> done) {
        MessagesController mc = MessagesController.getInstance(account);
        ArrayList<TLRPC.InputPeer> peers = new ArrayList<>();
        for (TLRPC.User bot : myBots(account)) {
            if (peers.size() >= MAX_BOTS_SEARCHED) {
                break;
            }
            peers.add(mc.getInputPeer(bot.id));
        }
        if (peers.isEmpty() || TextUtils.isEmpty(query)) {
            done.run(new ArrayList<>());
            return;
        }
        ArrayList<TLObject> requests = new ArrayList<>();
        for (TLRPC.InputPeer peer : peers) {
            TLRPC.TL_messages_search req = new TLRPC.TL_messages_search();
            req.peer = peer;
            req.q = query;
            req.filter = new TLRPC.TL_inputMessagesFilterEmpty();
            req.limit = 50;
            requests.add(req);
        }
        collectVideos(account, requests, done);
    }

    /**
     * Your bots: the ones opened from the Bots screen first (most recent first), then the bots in
     * your chat list. Only bots already known on this device (no network).
     */
    public static ArrayList<TLRPC.User> myBots(int account) {
        MessagesController mc = MessagesController.getInstance(account);
        java.util.LinkedHashMap<Long, TLRPC.User> bots = new java.util.LinkedHashMap<>();
        for (String name : MessagesController.getGlobalMainSettings().getString("vibgram_bot_recents", "").split(",")) {
            TLObject o = name.trim().isEmpty() ? null : mc.getUserOrChat(name.trim());
            if (o instanceof TLRPC.User && ((TLRPC.User) o).bot) {
                bots.put(((TLRPC.User) o).id, (TLRPC.User) o);
            }
        }
        for (TLRPC.Dialog d : mc.getAllDialogs()) {
            if (d != null && d.id > 0 && !bots.containsKey(d.id)) {
                TLRPC.User u = mc.getUser(d.id);
                if (u != null && u.bot) {
                    bots.put(d.id, u);
                }
            }
        }
        return new ArrayList<>(bots.values());
    }

    /**
     * Telegram's global search (chats, groups, channels) for videos and video files matching [query].
     * Bot chats aren't covered by it; use [searchVideosInBots] for those.
     */
    public static void searchVideosInChats(int account, String query, Callback<ArrayList<MessageObject>> done) {
        if (TextUtils.isEmpty(query)) {
            done.run(new ArrayList<>());
            return;
        }
        ArrayList<TLObject> requests = new ArrayList<>();
        TLRPC.MessagesFilter[] filters = {new TLRPC.TL_inputMessagesFilterVideo(), new TLRPC.TL_inputMessagesFilterDocument()};
        for (TLRPC.MessagesFilter filter : filters) {
            TLRPC.TL_messages_searchGlobal req = new TLRPC.TL_messages_searchGlobal();
            req.q = query;
            req.filter = filter;
            req.limit = 40;
            req.offset_peer = new TLRPC.TL_inputPeerEmpty();
            requests.add(req);
        }
        collectVideos(account, requests, done);
    }

    /** Runs the searches in parallel and keeps the video messages, deduplicated, biggest first. */
    private static void collectVideos(int account, ArrayList<TLObject> requests, Callback<ArrayList<MessageObject>> done) {
        ArrayList<MessageObject> found = new ArrayList<>();
        java.util.HashSet<String> seen = new java.util.HashSet<>();
        int[] pending = {requests.size()};
        MessagesController mc = MessagesController.getInstance(account);
        for (TLObject req : requests) {
            ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
                if (response instanceof TLRPC.messages_Messages) {
                    TLRPC.messages_Messages res = (TLRPC.messages_Messages) response;
                    mc.putUsers(res.users, false);
                    mc.putChats(res.chats, false);
                    for (TLRPC.Message msg : res.messages) {
                        if (msg == null || msg instanceof TLRPC.TL_messageEmpty || msg.media == null) {
                            continue;
                        }
                        MessageObject m = new MessageObject(account, msg, false, true);
                        TLRPC.Document doc = m.getDocument();
                        boolean video = doc != null && (m.isVideo() || VibeConfig.isVideoFile(doc)) && !m.isRoundVideo() && !m.isGif();
                        if (video && seen.add(m.getDialogId() + "_" + m.getId())) {
                            found.add(m);
                        }
                    }
                }
                if (--pending[0] == 0) {
                    java.util.Collections.sort(found, (a, b) -> Long.compare(b.getSize(), a.getSize()));
                    done.run(found);
                }
            }));
        }
    }

    /** Loads one message from the server so it can be played again. */
    public static void fetchMessage(int account, long dialogId, int messageId, Callback<MessageObject> done) {
        MessagesController mc = MessagesController.getInstance(account);
        TLObject req;
        TLRPC.Chat chat = dialogId < 0 ? mc.getChat(-dialogId) : null;
        if (chat != null && ChatObject.isChannel(chat)) {
            TLRPC.TL_channels_getMessages r = new TLRPC.TL_channels_getMessages();
            r.channel = mc.getInputChannel(-dialogId);
            r.id.add(messageId);
            req = r;
        } else {
            TLRPC.TL_messages_getMessages r = new TLRPC.TL_messages_getMessages();
            r.id.add(messageId);
            req = r;
        }
        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            MessageObject result = null;
            if (response instanceof TLRPC.messages_Messages) {
                TLRPC.messages_Messages res = (TLRPC.messages_Messages) response;
                mc.putUsers(res.users, false);
                mc.putChats(res.chats, false);
                for (TLRPC.Message msg : res.messages) {
                    if (msg != null && msg.id == messageId && !(msg instanceof TLRPC.TL_messageEmpty)) {
                        result = new MessageObject(account, msg, false, true);
                        break;
                    }
                }
            }
            done.run(result);
        }));
    }
}
