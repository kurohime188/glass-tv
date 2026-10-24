package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Pattern;

/**
 * Today's prayer times from JAKIM's e-Solat service, for one zone (JHR02: Johor Bahru, Kota Tinggi,
 * Mersing). Fetched once a day on a background thread and kept in SharedPreferences, so Home shows
 * them instantly and keeps showing the last ones when the network is down. Every failure just
 * leaves the previous times in place.
 */
final class PrayerTimes {
    private static final String TAG = "TvHome.Prayer";
    /** JAKIM zone code. Other zones: https://www.e-solat.gov.my */
    static final String ZONE = "JHR02";
    private static final String URL_TODAY =
            "https://www.e-solat.gov.my/index.php?r=esolatApi/TakwimSolat&period=today&zone=" + ZONE;
    private static final String[] NAMES = {"Subuh", "Zohor", "Asar", "Maghrib", "Isyak"};
    private static final String[] KEYS = {"fajr", "dhuhr", "asr", "maghrib", "isha"};
    private static final Pattern HHMM = Pattern.compile("\\d{2}:\\d{2}");
    private static final long RETRY_MS = 10L * 60 * 1000;
    /** Times older than this are hidden rather than shown wrong. */
    private static final long MAX_AGE_MS = 3L * 24 * 60 * 60 * 1000;
    private static final int NEXT_COLOR = 0xFFFFD166;
    private static final TimeZone KL = TimeZone.getTimeZone("Asia/Kuala_Lumpur");

    private static volatile boolean running;
    private static volatile long lastTry;

    private PrayerTimes() {
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences("prayer", Context.MODE_PRIVATE);
    }

    private static String today() {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT);
        f.setTimeZone(KL);
        return f.format(new Date());
    }

    /** Fetches today's times in the background when the saved ones are not from today; then runs onDone. */
    static void refreshIfDue(Context ctx, Runnable onDone) {
        final Context app = ctx.getApplicationContext();
        if (running) return;
        if (today().equals(prefs(app).getString("date", ""))) return;
        long now = System.currentTimeMillis();
        if (now - lastTry < RETRY_MS) return;
        lastTry = now;
        running = true;
        new Thread(() -> {
            boolean ok = false;
            try {
                ok = fetch(app);
            } catch (Exception e) {
                Log.w(TAG, "refresh failed: " + e);
            } finally {
                running = false;
            }
            if (ok && onDone != null) onDone.run();
        }, "prayer-times").start();
    }

    private static boolean fetch(Context ctx) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(URL_TODAY).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10; TV) AppleWebKit/537.36 "
                + "(KHTML, like Gecko) Chrome/120.0 Safari/537.36");
        byte[] body;
        try {
            if (c.getResponseCode() != 200) return false;
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    if (out.size() > 64 * 1024) return false;
                }
                body = out.toByteArray();
            }
        } finally {
            c.disconnect();
        }
        JSONObject json = new JSONObject(new String(body, StandardCharsets.UTF_8));
        JSONArray days = json.optJSONArray("prayerTime");
        JSONObject day = days != null ? days.optJSONObject(0) : null;
        if (day == null) return false;
        StringBuilder times = new StringBuilder();
        for (int i = 0; i < KEYS.length; i++) {
            String t = day.optString(KEYS[i], "");
            if (t.length() < 5 || !HHMM.matcher(t.substring(0, 5)).matches()) return false;
            if (i > 0) times.append(',');
            times.append(t, 0, 5);
        }
        prefs(ctx).edit().putString("times", times.toString()).putString("date", today())
                .putLong("at", System.currentTimeMillis()).apply();
        Log.i(TAG, "times for " + ZONE + ": " + times);
        return true;
    }

    /** "5:39" for "05:39", "4:09" for "16:09": 12-hour like the clock, no am/pm (the names say which). */
    private static String twelveHour(String hhmm) {
        int h = Integer.parseInt(hhmm.substring(0, 2));
        int m = Integer.parseInt(hhmm.substring(3, 5));
        int h12 = h % 12 == 0 ? 12 : h % 12;
        return String.format(Locale.ROOT, "%d:%02d", h12, m);
    }

    /** The two lines for the screen with the next prayer highlighted, or null when there are no recent times. */
    static CharSequence format(Context ctx) {
        SharedPreferences p = prefs(ctx);
        String saved = p.getString("times", "");
        if (saved.isEmpty() || System.currentTimeMillis() - p.getLong("at", 0) > MAX_AGE_MS) return null;
        String[] t = saved.split(",");
        if (t.length != NAMES.length) return null;
        Calendar now = Calendar.getInstance(KL);
        int nowMin = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
        int next = -1;
        for (int i = 0; i < t.length; i++) {
            int min = Integer.parseInt(t[i].substring(0, 2)) * 60 + Integer.parseInt(t[i].substring(3, 5));
            if (min > nowMin) {
                next = i;
                break;
            }
        }
        // After Isyak nothing is highlighted until the new day's times arrive.
        SpannableStringBuilder s = new SpannableStringBuilder();
        for (int i = 0; i < t.length; i++) {
            if (i == 3) s.append('\n');
            else if (i > 0) s.append("   ");
            int start = s.length();
            s.append(NAMES[i]).append(' ').append(twelveHour(t[i]));
            if (i == next) {
                s.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), start, s.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                s.setSpan(new android.text.style.ForegroundColorSpan(NEXT_COLOR), start, s.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        return s;
    }
}
