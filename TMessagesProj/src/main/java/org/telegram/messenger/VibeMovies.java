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
        for (int hop = 0; hop < 4; hop++) {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(15000);
            c.setInstanceFollowRedirects(false);
            c.setRequestProperty("Accept", "application/json");
            int code = c.getResponseCode();
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
