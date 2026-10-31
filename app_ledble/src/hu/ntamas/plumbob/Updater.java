package hu.ntamas.plumbob;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Self-update from GitHub releases. Release tag = "v<versionCode>", asset = PlumbobLED.apk,
 * signed with the same key as the installed app. All methods block: call them off the UI thread.
 */
final class Updater {
    private Updater() { }

    static final String REPO = "ntamas94/plumbob-led";
    static final String API = "https://api.github.com/repos/" + REPO + "/releases/latest";
    static final String APK_URL = "https://github.com/" + REPO + "/releases/latest/download/PlumbobLED.apk";

    /** versionCode of the latest release, or -1 when there is none. */
    static int latestCode() throws IOException {
        HttpURLConnection c = open(API);
        if (c.getResponseCode() == 404) return -1;
        Matcher m = Pattern.compile("\"tag_name\"\\s*:\\s*\"v(\\d+)\"").matcher(readAll(c));
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    /** Download the APK into a PackageInstaller session and commit it; the system then asks the user to confirm. */
    static void install(Context ctx) throws IOException {
        PackageInstaller pi = ctx.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams p = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        // Silent update once this app is the installer of record (true from the first self-update on), Android 12+.
        if (android.os.Build.VERSION.SDK_INT >= 31) p.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        int id = pi.createSession(p);
        try (PackageInstaller.Session s = pi.openSession(id)) {
            HttpURLConnection c = open(APK_URL);
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream(); OutputStream out = s.openWrite("PlumbobLED.apk", 0, -1)) {
                byte[] b = new byte[16384];
                for (int n; (n = in.read(b)) > 0; ) out.write(b, 0, n);
                s.fsync(out);
            }
            Intent i = new Intent(ctx, UpdateReceiver.class);
            PendingIntent pend = PendingIntent.getBroadcast(ctx, 0, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
            s.commit(pend.getIntentSender());
        } catch (IOException | RuntimeException e) {
            pi.abandonSession(id);   // a failed download must not leave a staged half-APK behind on every launch
            throw e;
        }
    }

    // GitHub answers the download URL with a redirect to objects.githubusercontent.com; HttpURLConnection follows https->https itself.
    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setInstanceFollowRedirects(true);
        c.setConnectTimeout(10000);
        c.setReadTimeout(30000);
        c.setRequestProperty("User-Agent", "PlumbobLED");
        return c;
    }

    private static String readAll(HttpURLConnection c) throws IOException {
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            for (int n; (n = in.read(b)) > 0; ) o.write(b, 0, n);
            return o.toString("UTF-8");
        }
    }
}
