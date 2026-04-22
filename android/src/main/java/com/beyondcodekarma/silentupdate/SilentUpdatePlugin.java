package com.beyondcodekarma.silentupdate;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.json.JSONObject;

/**
 * Silent live-update plugin for Capacitor Android.
 *
 * Contract:
 *   - Consumer MUST call {@link #prepareBoot(Context)} inside
 *     MainActivity.onCreate BEFORE super.onCreate(...). That call handles
 *     the cold-boot state machine: promote DOWNLOADED -> TRIAL, or
 *     rollback TRIAL -> FACTORY on unconfirmed boot (crash before
 *     notifyReady on the previous launch).
 *   - JS MUST call notifyReady() on every successful boot. Failing to do
 *     so within one boot is interpreted as a crash and triggers a
 *     rollback on the subsequent cold start.
 *   - Integrity is enforced via SHA-256 of the bundle ZIP. The optional
 *     `signature` parameter is currently accepted and ignored; future
 *     minor versions will verify ed25519 signatures against a pinned
 *     public key. The JS surface already carries the field so enabling
 *     signing is a server-side change.
 */
@CapacitorPlugin(name = "SilentUpdate")
public class SilentUpdatePlugin extends Plugin {

    private static final String OTA_PREFS = "silentupdate_prefs";
    private static final String WEBVIEW_PREFS = "CapWebViewSettings";
    private static final String CAP_SERVER_PATH = "serverBasePath";

    private static final String KEY_CURRENT = "silentupdate_current_version";
    private static final String KEY_PENDING = "silentupdate_pending_version";
    private static final String KEY_PENDING_PATH = "silentupdate_pending_path";
    private static final String KEY_CONFIRMED = "silentupdate_confirmed";
    private static final String KEY_LAST_CHECK = "silentupdate_last_check_ts";

    // Legacy Tithimala prefs layout. Only read during one-time migration.
    // Safe to remove in a future major once no v0.x-migrated devices remain
    // in the wild.
    private static final String LEGACY_OTA_PREFS = "ota_prefs";
    private static final String LEGACY_KEY_CURRENT = "ota_current_version";
    private static final String LEGACY_KEY_PENDING = "ota_pending_version";
    private static final String LEGACY_KEY_PENDING_PATH = "ota_pending_path";
    private static final String LEGACY_KEY_CONFIRMED = "ota_confirmed";
    private static final String LEGACY_KEY_LAST_CHECK = "ota_last_check_ts";

    private static final int PROGRESS_MIN_INTERVAL_MS = 200;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final AtomicBoolean downloading = new AtomicBoolean(false);

    @PluginMethod()
    public void getState(PluginCall call) {
        SharedPreferences prefs = getContext().getSharedPreferences(OTA_PREFS, Context.MODE_PRIVATE);
        JSObject ret = new JSObject();
        ret.put("currentVersion", prefs.getString(KEY_CURRENT, "factory"));
        ret.put("pendingVersion", prefs.getString(KEY_PENDING, ""));
        ret.put("lastCheckTs", prefs.getLong(KEY_LAST_CHECK, 0));
        ret.put("confirmed", prefs.getBoolean(KEY_CONFIRMED, true));
        call.resolve(ret);
    }

    @PluginMethod()
    public void setLastCheckTs(PluginCall call) {
        Double ts = call.getDouble("ts");
        if (ts == null) { call.reject("ts required"); return; }
        getContext().getSharedPreferences(OTA_PREFS, Context.MODE_PRIVATE)
            .edit().putLong(KEY_LAST_CHECK, ts.longValue()).apply();
        call.resolve();
    }

    @PluginMethod()
    public void checkManifest(PluginCall call) {
        String manifestUrl = call.getString("url");
        if (manifestUrl == null) { call.reject("url required"); return; }

        final String fUrl = manifestUrl;

        new Thread(() -> {
            try {
                String json = fetchString(fUrl);
                JSONObject manifest = new JSONObject(json);

                SharedPreferences prefs = getContext().getSharedPreferences(OTA_PREFS, Context.MODE_PRIVATE);
                prefs.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply();

                JSObject ret = new JSObject();
                ret.put("version", manifest.optString("version", ""));
                ret.put("url", manifest.optString("url", ""));
                ret.put("checksum", manifest.optString("checksum", ""));
                ret.put("force", manifest.optBoolean("force", false));
                ret.put("min_native_version", manifest.optString("min_native_version", ""));
                if (manifest.has("signature")) {
                    ret.put("signature", manifest.optString("signature", ""));
                }
                MAIN.post(() -> call.resolve(ret));
            } catch (Exception e) {
                String msg = e.getMessage();
                MAIN.post(() -> call.reject("Manifest fetch failed: " + msg));
            }
        }).start();
    }

    @PluginMethod()
    public void downloadUpdate(PluginCall call) {
        if (!downloading.compareAndSet(false, true)) {
            call.reject("Download already in progress");
            return;
        }

        String url = call.getString("url");
        String version = call.getString("version");
        String checksum = call.getString("checksum");
        // Accepted but currently ignored. Reserved for ed25519 signature
        // verification in a future minor; see class Javadoc.
        String signature = call.getString("signature");

        if (url == null || version == null || checksum == null) {
            downloading.set(false);
            call.reject("url, version, checksum required");
            return;
        }

        final String fUrl = url;
        final String fVersion = version;
        final String fChecksum = checksum;

        new Thread(() -> {
            try {
                File otaDir = new File(getContext().getFilesDir(), "ota");
                File bundleDir = new File(otaDir, fVersion);
                File tempZip = new File(otaDir, fVersion + ".zip");

                if (!otaDir.exists()) otaDir.mkdirs();
                if (bundleDir.exists()) deleteDir(bundleDir);
                bundleDir.mkdirs();

                downloadFile(fUrl, tempZip);

                emitStage("verify", null);
                String computed = sha256(tempZip);
                if (!computed.equalsIgnoreCase(fChecksum)) {
                    tempZip.delete();
                    deleteDir(bundleDir);
                    String msg = "Checksum mismatch: expected " + fChecksum + " got " + computed;
                    MAIN.post(() -> call.reject(msg));
                    return;
                }

                emitStage("unzip", null);
                unzip(tempZip, bundleDir);
                tempZip.delete();

                SharedPreferences otaPrefs = getContext().getSharedPreferences(OTA_PREFS, Context.MODE_PRIVATE);
                SharedPreferences webPrefs = getContext().getSharedPreferences(WEBVIEW_PREFS, Activity.MODE_PRIVATE);

                // Stage the update. Trial begins in prepareBoot on next cold start.
                otaPrefs.edit()
                    .putString(KEY_PENDING, fVersion)
                    .putString(KEY_PENDING_PATH, bundleDir.getAbsolutePath())
                    .commit();

                webPrefs.edit()
                    .putString(CAP_SERVER_PATH, bundleDir.getAbsolutePath())
                    .commit();

                emitStage("ready", fVersion);

                JSObject ret = new JSObject();
                ret.put("success", true);
                ret.put("version", fVersion);
                MAIN.post(() -> call.resolve(ret));

            } catch (Exception e) {
                String msg = e.getMessage();
                MAIN.post(() -> call.reject("Download failed: " + msg));
            } finally {
                downloading.set(false);
            }
        }).start();
    }

    @PluginMethod()
    public void applyNow(PluginCall call) {
        SharedPreferences otaPrefs = getContext().getSharedPreferences(OTA_PREFS, Context.MODE_PRIVATE);
        String pendingPath = otaPrefs.getString(KEY_PENDING_PATH, null);
        String pendingVersion = otaPrefs.getString(KEY_PENDING, null);

        if (pendingPath == null || !new File(pendingPath).exists()) {
            call.reject("No pending update to apply");
            return;
        }

        String oldVersion = otaPrefs.getString(KEY_CURRENT, "factory");

        otaPrefs.edit()
            .putString(KEY_CURRENT, pendingVersion)
            .putString(KEY_PENDING, "")
            .putString(KEY_PENDING_PATH, "")
            .putBoolean(KEY_CONFIRMED, false)
            .commit();

        if (!"factory".equals(oldVersion)) {
            File oldDir = new File(getContext().getFilesDir(), "ota/" + oldVersion);
            if (oldDir.exists()) deleteDir(oldDir);
        }

        getBridge().setServerBasePath(pendingPath);
        call.resolve();
    }

    @PluginMethod()
    public void notifyReady(PluginCall call) {
        getContext().getSharedPreferences(OTA_PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_CONFIRMED, true).commit();
        call.resolve();
    }

    @PluginMethod()
    public void rollback(PluginCall call) {
        SharedPreferences webPrefs = getContext().getSharedPreferences(WEBVIEW_PREFS, Activity.MODE_PRIVATE);
        SharedPreferences otaPrefs = getContext().getSharedPreferences(OTA_PREFS, Context.MODE_PRIVATE);

        webPrefs.edit().remove(CAP_SERVER_PATH).commit();
        otaPrefs.edit().clear().commit();

        // Defer ota/ directory deletion to avoid 404s on the running WebView.
        // prepareBoot will handle it on next cold start; recreate the activity
        // for immediate visual feedback.
        Activity activity = getActivity();
        call.resolve();
        if (activity != null) {
            activity.recreate();
        }
    }

    // ── Static boot-time logic (runs in MainActivity.onCreate before super) ──

    /**
     * Handles three transitions at cold start:
     *   1. One-time legacy prefs migration ("ota_prefs" -> "silentupdate_prefs")
     *      for apps upgrading from the Tithimala-internal plugin.
     *   2. DOWNLOADED -> TRIAL: pending exists + confirmed=true -> promote,
     *      set confirmed=false.
     *   3. TRIAL -> FACTORY: confirmed=false -> crash before notifyReady on
     *      the previous boot -> rollback to factory.
     * Also cleans up orphaned ota/ files when returning to factory.
     */
    public static void prepareBoot(Context context) {
        migrateLegacyPrefsOnce(context);

        SharedPreferences ota = context.getSharedPreferences(OTA_PREFS, Context.MODE_PRIVATE);
        SharedPreferences web = context.getSharedPreferences(WEBVIEW_PREFS, Activity.MODE_PRIVATE);

        boolean confirmed = ota.getBoolean(KEY_CONFIRMED, true);
        String pending = ota.getString(KEY_PENDING, "");
        String pendingPath = ota.getString(KEY_PENDING_PATH, "");

        if (!confirmed) {
            web.edit().remove(CAP_SERVER_PATH).commit();
            ota.edit()
                .putString(KEY_CURRENT, "factory")
                .putString(KEY_PENDING, "")
                .putString(KEY_PENDING_PATH, "")
                .putBoolean(KEY_CONFIRMED, true)
                .commit();

            File otaDir = new File(context.getFilesDir(), "ota");
            if (otaDir.isDirectory()) {
                deleteDirStatic(otaDir);
            }
            return;
        }

        if (!pending.isEmpty() && new File(pendingPath).exists()) {
            // Also ensure CAP_SERVER_PATH is set: survives a process kill
            // between the two commits in downloadUpdate.
            String oldVersion = ota.getString(KEY_CURRENT, "factory");

            web.edit()
                .putString(CAP_SERVER_PATH, pendingPath)
                .commit();

            ota.edit()
                .putString(KEY_CURRENT, pending)
                .putString(KEY_PENDING, "")
                .putString(KEY_PENDING_PATH, "")
                .putBoolean(KEY_CONFIRMED, false)
                .commit();

            if (!"factory".equals(oldVersion)) {
                File oldDir = new File(context.getFilesDir(), "ota/" + oldVersion);
                if (oldDir.isDirectory()) {
                    deleteDirStatic(oldDir);
                }
            }
            return;
        }

        if ("factory".equals(ota.getString(KEY_CURRENT, "factory")) && pending.isEmpty()) {
            File otaDir = new File(context.getFilesDir(), "ota");
            if (otaDir.isDirectory()) {
                deleteDirStatic(otaDir);
            }
        }
    }

    /**
     * One-time copy from the legacy "ota_prefs" namespace into the new
     * "silentupdate_prefs" namespace. Runs at most once per install: after
     * the copy, legacy prefs are cleared so a later downgrade (unlikely)
     * wouldn't double-migrate. Safe on fresh installs — returns early.
     */
    private static void migrateLegacyPrefsOnce(Context context) {
        SharedPreferences newP = context.getSharedPreferences(OTA_PREFS, Context.MODE_PRIVATE);
        if (newP.contains(KEY_CURRENT) || newP.contains(KEY_PENDING)) return;

        SharedPreferences oldP = context.getSharedPreferences(LEGACY_OTA_PREFS, Context.MODE_PRIVATE);
        if (!oldP.contains(LEGACY_KEY_CURRENT) && !oldP.contains(LEGACY_KEY_PENDING)) return;

        SharedPreferences.Editor e = newP.edit();
        String cur = oldP.getString(LEGACY_KEY_CURRENT, null);
        if (cur != null) e.putString(KEY_CURRENT, cur);
        String pend = oldP.getString(LEGACY_KEY_PENDING, null);
        if (pend != null) e.putString(KEY_PENDING, pend);
        String pendPath = oldP.getString(LEGACY_KEY_PENDING_PATH, null);
        if (pendPath != null) e.putString(KEY_PENDING_PATH, pendPath);
        e.putBoolean(KEY_CONFIRMED, oldP.getBoolean(LEGACY_KEY_CONFIRMED, true));
        e.putLong(KEY_LAST_CHECK, oldP.getLong(LEGACY_KEY_LAST_CHECK, 0));
        e.commit();

        oldP.edit().clear().commit();
    }

    // ── Progress events ──

    private long lastProgressEmitMs = 0;
    private int lastProgressPct = -1;

    private void emitDownloadProgress(long bytesWritten, long totalBytes) {
        int pct = totalBytes > 0 ? (int) ((bytesWritten * 100L) / totalBytes) : -1;
        long now = System.currentTimeMillis();
        if (pct == lastProgressPct && (now - lastProgressEmitMs) < PROGRESS_MIN_INTERVAL_MS) return;
        lastProgressPct = pct;
        lastProgressEmitMs = now;

        JSObject ev = new JSObject();
        ev.put("stage", "download");
        if (pct >= 0) ev.put("percent", pct);
        ev.put("bytesWritten", bytesWritten);
        if (totalBytes >= 0) ev.put("totalBytes", totalBytes);
        try { notifyListeners("updateProgress", ev); } catch (Throwable ignored) { /* no-op */ }
    }

    private void emitStage(String stage, String version) {
        JSObject ev = new JSObject();
        ev.put("stage", stage);
        if (version != null) ev.put("version", version);
        try { notifyListeners("updateProgress", ev); } catch (Throwable ignored) { /* no-op */ }
    }

    // ── Private helpers ──

    private String fetchString(String urlStr) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(10_000);
        conn.setRequestProperty("Cache-Control", "no-cache");
        try (InputStream in = new BufferedInputStream(conn.getInputStream());
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[4096];
            int len;
            while ((len = in.read(buf)) != -1) {
                out.write(buf, 0, len);
            }
            return out.toString("UTF-8");
        } finally {
            conn.disconnect();
        }
    }

    private void downloadFile(String urlStr, File dest) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(60_000);

        long totalBytes = conn.getContentLengthLong();
        long bytesWritten = 0;

        lastProgressEmitMs = 0;
        lastProgressPct = -1;
        emitDownloadProgress(0, totalBytes);

        try (InputStream in = new BufferedInputStream(conn.getInputStream());
             FileOutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[8192];
            int len;
            while ((len = in.read(buf)) != -1) {
                out.write(buf, 0, len);
                bytesWritten += len;
                emitDownloadProgress(bytesWritten, totalBytes);
            }
        } finally {
            conn.disconnect();
        }

        // Ensure 100% lands even if throttle ate the final chunk's emit.
        if (totalBytes > 0) {
            JSObject ev = new JSObject();
            ev.put("stage", "download");
            ev.put("percent", 100);
            ev.put("bytesWritten", bytesWritten);
            ev.put("totalBytes", totalBytes);
            try { notifyListeners("updateProgress", ev); } catch (Throwable ignored) { /* no-op */ }
        }
    }

    private String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] buf = new byte[8192];
            int len;
            while ((len = fis.read(buf)) != -1) {
                digest.update(buf, 0, len);
            }
        }
        byte[] hash = digest.digest();
        StringBuilder hex = new StringBuilder();
        for (byte b : hash) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    private void unzip(File zipFile, File destDir) throws Exception {
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zipFile))) {
            ZipEntry entry;
            byte[] buf = new byte[8192];
            while ((entry = zis.getNextEntry()) != null) {
                File outFile = new File(destDir, entry.getName());
                String canonicalDest = destDir.getCanonicalPath();
                if (!outFile.getCanonicalPath().startsWith(canonicalDest)) {
                    throw new SecurityException("Zip path traversal: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    outFile.mkdirs();
                } else {
                    outFile.getParentFile().mkdirs();
                    try (FileOutputStream fos = new FileOutputStream(outFile)) {
                        int len;
                        while ((len = zis.read(buf)) != -1) {
                            fos.write(buf, 0, len);
                        }
                    }
                }
                zis.closeEntry();
            }
        }
    }

    private void deleteDir(File dir) {
        if (dir.isDirectory()) {
            File[] children = dir.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteDir(child);
                }
            }
        }
        dir.delete();
    }

    private static void deleteDirStatic(File dir) {
        if (dir.isDirectory()) {
            File[] children = dir.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteDirStatic(child);
                }
            }
        }
        dir.delete();
    }
}
