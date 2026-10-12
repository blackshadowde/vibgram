package org.telegram.ui;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Vibgram updates from your own GitHub Releases.
 * The build workflow stamps each APK with its build number and, when you tick
 * "Publish release", uploads it as release "build-N". The app compares numbers.
 */
public class VibeUpdates {

    public static final String REPO = "blackshadowde/vibgram";
    public static final String REPO_URL = "https://github.com/" + REPO;
    private static final long CHECK_EVERY = 12L * 60 * 60 * 1000;

    public interface Callback {
        /** latest = 0 when there is no release yet; error != null when the check failed. */
        void onResult(int latest, String apkUrl, String pageUrl, String error);
    }

    public static int currentBuild() {
        try {
            return ApplicationLoader.applicationContext.getResources().getInteger(R.integer.vibe_build_number);
        } catch (Throwable e) {
            return 0;
        }
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("vibeupdates", Context.MODE_PRIVATE);
    }

    public static void fetchLatest(Callback cb) {
        Utilities.globalQueue.postRunnable(() -> {
            int latest = 0;
            String apk = null, page = null, error = null;
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL("https://api.github.com/repos/" + REPO + "/releases/latest").openConnection();
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);
                conn.setRequestProperty("Accept", "application/vnd.github+json");
                conn.setRequestProperty("User-Agent", "Vibgram");
                int code = conn.getResponseCode();
                if (code == 404) {
                    latest = 0; // no release published yet
                } else if (code != 200) {
                    error = "GitHub answered " + code;
                } else {
                    InputStream in = conn.getInputStream();
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                    }
                    in.close();
                    JSONObject json = new JSONObject(out.toString("UTF-8"));
                    String tag = json.optString("tag_name", "");
                    latest = parseBuild(tag);
                    page = json.optString("html_url", REPO_URL + "/releases");
                    JSONArray assets = json.optJSONArray("assets");
                    if (assets != null) {
                        for (int i = 0; i < assets.length(); i++) {
                            JSONObject a = assets.getJSONObject(i);
                            if (a.optString("name", "").endsWith(".apk")) {
                                apk = a.optString("browser_download_url", null);
                                break;
                            }
                        }
                    }
                }
            } catch (Throwable e) {
                FileLog.e(e);
                error = "No connection to GitHub";
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
            final int fLatest = latest;
            final String fApk = apk, fPage = page, fError = error;
            AndroidUtilities.runOnUIThread(() -> cb.onResult(fLatest, fApk, fPage, fError));
        });
    }

    /** "build-42" or "v42" -> 42. */
    static int parseBuild(String tag) {
        if (tag == null) {
            return 0;
        }
        String digits = tag.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) {
            return 0;
        }
        try {
            return Integer.parseInt(digits.length() > 9 ? digits.substring(digits.length() - 9) : digits);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Called on app open: at most every 12 hours, says so only when a newer build exists. */
    public static void checkQuietly(BaseFragment fragment) {
        int current = currentBuild();
        if (current <= 0) {
            return; // a local / test build without a number
        }
        long now = System.currentTimeMillis();
        if (now - prefs().getLong("last", 0) < CHECK_EVERY) {
            return;
        }
        prefs().edit().putLong("last", now).apply();
        fetchLatest((latest, apk, page, error) -> {
            if (error != null || latest <= current || prefs().getInt("skip", 0) == latest) {
                return;
            }
            showUpdateDialog(fragment, latest, apk, page);
        });
    }

    public static void showUpdateDialog(BaseFragment fragment, int latest, String apk, String page) {
        if (fragment == null || fragment.getParentActivity() == null) {
            return;
        }
        AlertDialog.Builder b = new AlertDialog.Builder(fragment.getParentActivity());
        b.setTitle("Update available");
        b.setMessage("Vibgram build " + latest + " is ready (you have build " + currentBuild() + ").\n\nDownload it, then open the file to install. Your chats stay as they are.");
        b.setPositiveButton("Download", (d, w) -> open(fragment.getParentActivity(), apk != null ? apk : page));
        b.setNegativeButton("Skip this one", (d, w) -> prefs().edit().putInt("skip", latest).apply());
        fragment.showDialog(b.create());
    }

    public static void open(Context context, String url) {
        if (context == null || url == null) {
            return;
        }
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(i);
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }
}
