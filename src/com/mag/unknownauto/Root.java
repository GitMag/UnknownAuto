package com.mag.unknownauto;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Runs commands as root through {@code su}, for the phone-only install path on a rooted
 * device — an alternative to Shizuku that needs no separate app or wireless-debugging setup.
 *
 * <p>What root adds over the ordinary install: a <b>multi-APK (split) XAPK</b> installs in
 * one {@code pm} session, silently (no taps), with the installer recorded as
 * {@code com.android.vending}. It cannot give a split install the full Android-Auto mark —
 * Android forces {@code initiatingPackageName=com.android.shell} for any shell/root install,
 * and only the single-APK installer GUI can set {@code initiatingPackageName=packageinstaller}
 * — so a split Car-App-Library app still won't project. Everything else (a clean, silent
 * install of a split app, its expansion files placed, the Play installer name) it does do.
 *
 * <p>Nothing here runs unless the user installs a split XAPK on a rooted phone, and {@code su}
 * is invoked only then — so a non-rooted phone never sees a root prompt.
 */
final class Root {

    private Root() {
    }

    /** Where su binaries usually live; a best-effort hint only (modern Magisk hides su). */
    private static final String[] SU_PATHS = {
            "/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su",
            "/system/sbin/su", "/vendor/bin/su", "/debug_ramdisk/su",
            "/magisk/.core/bin/su", "/data/adb/magisk/su"
    };

    private static final int MAX_OUTPUT = 256 * 1024;

    /**
     * Best-effort "is this phone rooted" for the UI hint. Modern Magisk can hide the su
     * binary, so a negative here isn't final — {@link #run} still tries, and the real
     * answer is whether that su call succeeds.
     */
    static boolean binaryPresent() {
        for (String p : SU_PATHS) {
            if (new File(p).exists()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Feeds {@code script} to a root shell and returns its combined output (stdout + stderr),
     * or {@code null} if {@code su} could not start or was denied — which the caller reads as
     * "no root here" and falls back to the ordinary install. The su prompt (Magisk et al.)
     * appears the first time this is called.
     */
    static String run(String script) {
        Process p = null;
        try {
            p = new ProcessBuilder("su").redirectErrorStream(true).start();
            try (OutputStream os = p.getOutputStream()) {
                os.write(script.getBytes("UTF-8"));
                os.write("\nexit\n".getBytes("UTF-8"));
                os.flush();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream in = p.getInputStream()) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) >= 0) {
                    if (out.size() < MAX_OUTPUT) {
                        out.write(buf, 0, Math.min(n, MAX_OUTPUT - out.size()));
                    }
                }
            }
            p.waitFor();
            return out.toString("UTF-8");
        } catch (Throwable t) {
            // su absent (IOException) or interrupted/denied: let the caller fall back
            if (p != null) {
                p.destroy();
            }
            return null;
        }
    }

    /** Single-quotes a path for a shell command (paths here are our own cache, no quotes). */
    static String q(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }
}
