package com.mag.unknownauto;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.InstallSourceInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;

import org.json.JSONArray;
import org.json.JSONObject;

import rikka.shizuku.Shizuku;

/**
 * Installs an APK with the Google Play install-source attribution.
 *
 * <p>Android Auto refuses an app built with the Android for Cars App Library
 * unless its install source traces to Google Play — the "Unknown sources"
 * developer toggle does not cover these apps. A plain {@code adb install}
 * leaves {@code initiatingPackageName=com.android.shell}, which Android Auto
 * rejects outright.
 *
 * <p>The only way to get {@code initiatingPackageName=com.google.android.packageinstaller}
 * (which Android Auto accepts) is to let the on-device system package installer
 * perform the install. This activity does exactly that: it stages the APK,
 * hands it to the system installer as a {@code content://} URI, and sets the
 * installer name to {@code com.android.vending} and the referrer to Google Play
 * in the install intent. The user taps "Install" once.
 *
 * <p>Result on Android 17 (verified on a Pixel 9):
 * <pre>
 *   initiatingPackageName = com.google.android.packageinstaller   (Android Auto wants this)
 *   installerPackageName  = com.android.vending                    (set by the intent extra)
 * </pre>
 *
 * <p>Source of the APK, in order:
 * <ol>
 *   <li>{@code --es apk &lt;path&gt;} intent extra (used by the Mac helper), or</li>
 *   <li>the intent's data URI (opening an .apk "with UnknownAuto"), or</li>
 *   <li>{@code getExternalFilesDir()/pending.apk} (the Mac helper pushes here).</li>
 * </ol>
 */
public final class InstallActivity extends Activity {

    private static final String VENDING = "com.android.vending";
    private static final int REQ_INSTALL = 1;
    private static final int REQ_PICK = 2;
    private static final int REQ_SHIZUKU = 3000;

    private TextView log;
    private TextView banner;        // big, prominent success/failure line
    private ScrollView scroll;
    private Button installButton;   // "Install <app> for Android Auto" (enabled only when staged)
    private String targetPackage;   // package name parsed from the staged APK
    private String stagedLabel;     // app label of the staged APK
    private File staged;            // cacheDir/install.apk
    private PackageInfo stagedInfo; // the staged APK as parsed (version, signing key)
    private long installedBefore;   // target's lastUpdateTime before this install; 0 = absent
    private long installStartedAt;  // when the installer was launched (start of its log)
    private boolean awaitingBinder; // staged and waiting for Shizuku's binder to connect
    private boolean verifyPending;  // an install was launched; verify when we come back
    private boolean sawPause;       // the installer actually took the foreground
    private boolean viaShell;       // this attempt launched the installer as shell (Shizuku)
    private boolean retried;        // this install already tried the other launch path

    /**
     * Remembered per phone: whether the installer keeps the Play mark when the shell
     * starts it (true on current Android) or only when an app without INSTALL_PACKAGES
     * does (some Android 17 builds have that check inverted).
     */
    private static final String SHELL_KEEPS_MARK = "shellKeepsMark";

    private final Shizuku.OnBinderReceivedListener binderListener =
            new Shizuku.OnBinderReceivedListener() {
        @Override
        public void onBinderReceived() {
            if (awaitingBinder) {
                awaitingBinder = false;
                shizukuProceed();
            }
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        buildUi();
        print(deviceLine());
        try {
            Shizuku.addBinderReceivedListenerSticky(binderListener);
        } catch (Throwable ignore) {
            // Shizuku classes always present; guard anyway
        }
        handleIntent();
    }

    /** Handles the current intent (onCreate and onNewIntent): loads any APK, never installs. */
    private void handleIntent() {
        File src = resolveSource();
        if (src == null) {
            print("No APK loaded yet. Tap “Load APK…” to choose one, "
                    + "or open an .apk with UnknownAuto.", Color.rgb(0xB0, 0x60, 0x00));
            return;
        }
        if (!stage(src)) {
            return;
        }
        // "stage + grant only": stage the APK and grant read to the installer, but
        // let the SHELL (adb / Shizuku) launch the system installer, so the install
        // name com.android.vending is honoured.
        if (getIntent() != null && getIntent().getBooleanExtra("stageonly", false)) {
            grantToInstallers(ApkProvider.uriFor());
            print("\nStaged and granted to the package installer.");
            print("content://" + ApkProvider.AUTHORITY + "/" + ApkProvider.NAME);
            print("The shell can now launch the installer against that URI.");
            return;
        }
        // Loaded only — the user taps “Install” to install it. (No auto-install on open.)
        if (banner != null) {
            banner.setVisibility(View.GONE);
        }
        print("Ready. Tap “Install” to install " + stagedLabel + " for Android Auto.");
    }

    /** Phone, Android version and installer version: what a bug report needs first. */
    private String deviceLine() {
        String pi;
        try {
            pi = getPackageManager().getPackageInfo("com.google.android.packageinstaller", 0)
                    .versionName;
        } catch (Throwable t) {
            pi = "?";
        }
        return Build.MANUFACTURER + " " + Build.MODEL + ", Android " + Build.VERSION.RELEASE
                + " (SDK " + Build.VERSION.SDK_INT + "), package installer " + pi + "\n";
    }

    /** Grants read on the staged-APK URI to the system package installers. */
    private void grantToInstallers(Uri uri) {
        for (String p : new String[]{"com.google.android.packageinstaller",
                "com.android.packageinstaller", VENDING}) {
            try {
                grantUriPermission(p, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignore) {
                // not installed; harmless
            }
        }
    }

    // --- source resolution -------------------------------------------------

    private File resolveSource() {
        Intent it = getIntent();
        String path = it != null ? it.getStringExtra("apk") : null;
        if (path != null) {
            File f = new File(path);
            if (f.isFile()) {
                return f;
            }
            print("apk path not found: " + path, Color.RED);
        }
        if (it != null && it.getData() != null) {
            File f = copyUri(it.getData());
            if (f != null) {
                return f;
            }
        }
        File pending = new File(getExternalFilesDir(null), "pending.apk");
        if (pending.isFile()) {
            return pending;
        }
        return null;
    }

    /** Copies a content:// or file:// URI into cache so we can parse and serve it. */
    private File copyUri(Uri uri) {
        File out = new File(getCacheDir(), "incoming.apk");
        try (InputStream in = getContentResolver().openInputStream(uri);
             OutputStream os = new FileOutputStream(out)) {
            if (in == null) {
                return null;
            }
            copy(in, os);
            return out;
        } catch (Exception e) {
            print("Could not read the APK: " + e.getMessage(), Color.RED);
            return null;
        }
    }

    // --- staging -----------------------------------------------------------

    private boolean stage(File src) {
        staged = new File(getCacheDir(), ApkProvider.NAME);
        try (InputStream in = new java.io.FileInputStream(src);
             OutputStream os = new FileOutputStream(staged)) {
            copy(in, os);
        } catch (Exception e) {
            print("Could not stage the APK: " + e.getMessage(), Color.RED);
            clearStaged();
            return false;
        }
        PackageInfo pi = getPackageManager().getPackageArchiveInfo(staged.getAbsolutePath(),
                signingFlag());
        if (pi == null) {
            print("Not a valid APK: " + src, Color.RED);
            clearStaged();
            return false;
        }
        stagedInfo = pi;
        targetPackage = pi.packageName;
        stagedLabel = targetPackage;
        try {
            ApplicationInfo ai = pi.applicationInfo;
            if (ai != null) {
                ai.sourceDir = staged.getAbsolutePath();
                ai.publicSourceDir = staged.getAbsolutePath();
                stagedLabel = String.valueOf(getPackageManager().getApplicationLabel(ai));
            }
        } catch (Throwable ignore) {
            // keep the package name as the label
        }
        print("Loaded " + stagedLabel + " (" + targetPackage + "), v" + pi.versionName
                + ", " + (staged.length() / 1024) + " kB");
        updateInstallButton();
        return true;
    }

    // --- install permission ------------------------------------------------

    private boolean ensureInstallPermission() {
        if (getPackageManager().canRequestPackageInstalls()) {
            return true;
        }
        print("UnknownAuto needs the \"Install unknown apps\" permission. "
                + "Opening settings — allow it, then reopen UnknownAuto.", Color.rgb(0xB0, 0x60, 0x00));
        startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:" + getPackageName())));
        return false;
    }

    // --- before installing: refuse what Android would refuse anyway ---------

    /**
     * Notes the target's current install time (so verify() can tell "not installed"
     * from "installed without the Play mark") and stops installs Android would refuse
     * anyway — a downgrade or a different signing key — with the fix. Without this the
     * installer fails, the old sideload record stays, and it looks like a rejection.
     */
    private boolean preflight() {
        PackageInfo old = installedInfo(targetPackage);
        installedBefore = old != null ? old.lastUpdateTime : 0;
        if (old == null) {
            return true;
        }
        String label = labelFor(targetPackage);
        print("Already installed: v" + old.versionName + " (installer "
                + installerOf(targetPackage) + ")");
        if (versionCode(old) > versionCode(stagedInfo)) {
            showBanner("✖  A newer " + label + " (v" + old.versionName + ") is installed\n"
                    + "Uninstall it first, then install this one.", Color.RED);
            print("✖ Android does not install an older version over a newer one.", Color.RED);
            return false;
        }
        if (!sameSigner(stagedInfo, old)) {
            showBanner("✖  The installed " + label + " is signed with another key\n"
                    + "Uninstall it first (its data is lost), then install.", Color.RED);
            print("✖ Android only updates an app with an APK signed by the same key.", Color.RED);
            return false;
        }
        return true;
    }

    private static int signingFlag() {
        return Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : 0;
    }

    private PackageInfo installedInfo(String pkg) {
        try {
            return getPackageManager().getPackageInfo(pkg, signingFlag());
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    private String installerOf(String pkg) {
        try {
            return Build.VERSION.SDK_INT >= 30
                    ? getPackageManager().getInstallSourceInfo(pkg).getInstallingPackageName()
                    : getPackageManager().getInstallerPackageName(pkg);
        } catch (Throwable t) {
            return null;
        }
    }

    @SuppressWarnings("deprecation")
    private static long versionCode(PackageInfo pi) {
        return Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
    }

    /** True if the new APK can update the installed one (same key, or a rotation of it). */
    private static boolean sameSigner(PackageInfo apk, PackageInfo installed) {
        if (Build.VERSION.SDK_INT < 28 || apk.signingInfo == null
                || installed.signingInfo == null) {
            return true; // can't tell: let the installer decide
        }
        Signature[] current = installed.signingInfo.getApkContentsSigners();
        Signature[] accepted = apk.signingInfo.hasMultipleSigners()
                ? apk.signingInfo.getApkContentsSigners()
                : apk.signingInfo.getSigningCertificateHistory();
        return Arrays.asList(accepted).containsAll(Arrays.asList(current));
    }

    // --- the actual install ------------------------------------------------

    private void launchInstaller() {
        Uri uri = ApkProvider.uriFor();
        grantToInstallers(uri);

        Intent in = new Intent(Intent.ACTION_INSTALL_PACKAGE);
        in.setDataAndType(uri, "application/vnd.android.package-archive");
        in.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        // This is the attribution Android Auto reads back. The system installer
        // becomes the initiator; these extras make the installer name Google Play.
        in.putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true);
        in.putExtra(Intent.EXTRA_RETURN_RESULT, true);
        in.putExtra("android.intent.extra.INSTALLER_PACKAGE_NAME", VENDING);
        in.putExtra("android.content.pm.extra.VERIFICATION_INSTALLER_PACKAGE", VENDING);
        in.putExtra("android.intent.extra.INSTALL_REASON", 1); // POLICY/USER
        in.putExtra(Intent.EXTRA_REFERRER_NAME, "android-app://" + VENDING);
        in.putExtra(Intent.EXTRA_REFERRER, Uri.parse("android-app://" + VENDING));
        in.putExtra("android.intent.extra.ORIGINATING_URI",
                Uri.parse("https://play.google.com/store/apps/details?id=" + targetPackage));
        if (Build.VERSION.SDK_INT >= 34) {
            in.putExtra("android.content.pm.extra.REQUEST_UPDATE_OWNERSHIP", true);
        }

        try {
            verifyPending = true;
            sawPause = false;
            viaShell = false;
            installStartedAt = System.currentTimeMillis();
            startActivityForResult(in, REQ_INSTALL);
            print("Tap “Install” on the phone…");
        } catch (Exception e) {
            print("No system installer handled the request: " + e.getMessage(), Color.RED);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (verifyPending) {
            sawPause = true; // the installer / Play Protect took the foreground
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (verifyPending && sawPause) {
            verifyPending = false;
            sawPause = false;
            // let PackageManager settle after the install completes
            new android.os.Handler(getMainLooper()).postDelayed(this::verify, 600);
        }
    }

    // --- phone-only path: drive the installer as shell via Shizuku ----------

    /**
     * Installs using Shizuku: the installer runs as the shell user, so the result is
     * initiator=packageinstaller AND installer=com.android.vending — fully on-device,
     * no computer. Returns true if it took over (running, or asked for permission),
     * false if Shizuku is unavailable so the caller can fall back.
     */
    private boolean tryShizukuInstall() {
        if (staged == null || targetPackage == null) {
            return false;
        }
        if (!isShizukuInstalled()) {
            return false; // no Shizuku at all -> caller falls back to the app path
        }
        try {
            if (Shizuku.pingBinder()) {
                shizukuProceed();
            } else {
                // Binder not connected yet. Wait for it (sticky listener calls shizukuProceed).
                awaitingBinder = true;
                print("Connecting to Shizuku… if nothing happens, start Shizuku "
                        + "(Developer options → Wireless debugging, then Shizuku → Start).",
                        Color.rgb(0xB0, 0x60, 0x00));
            }
            return true; // took over; do not fall back to the app path
        } catch (Throwable t) {
            print("Shizuku unavailable: " + t.getMessage(), Color.rgb(0xB0, 0x60, 0x00));
            return false;
        }
    }

    private boolean isShizukuInstalled() {
        try {
            getPackageManager().getPackageInfo("moe.shizuku.privileged.api", 0);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Binder is connected: check permission (request once), then run the install. */
    private void shizukuProceed() {
        try {
            if (Shizuku.isPreV11()) {
                print("Shizuku is too old (pre-v11); update it.", Color.rgb(0xB0, 0x60, 0x00));
                return;
            }
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                Shizuku.addRequestPermissionResultListener(permListener);
                Shizuku.requestPermission(REQ_SHIZUKU);
                print("Grant UnknownAuto the Shizuku permission; it will then install.");
                return;
            }
            runViaShizuku();
        } catch (Throwable t) {
            print("Shizuku error: " + t.getMessage(), Color.RED);
        }
    }

    private final Shizuku.OnRequestPermissionResultListener permListener =
            new Shizuku.OnRequestPermissionResultListener() {
        @Override
        public void onRequestPermissionResult(int requestCode, int grantResult) {
            if (requestCode != REQ_SHIZUKU) {
                return;
            }
            Shizuku.removeRequestPermissionResultListener(this);
            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                runViaShizuku();
            } else {
                print("Shizuku permission denied.", Color.RED);
            }
        }
    };

    private void runViaShizuku() {
        grantToInstallers(ApkProvider.uriFor());
        print("asking Shizuku to run the installer as shell…");
        print("tap Update / Install if it asks (and “Install anyway” if Play Protect asks)…");
        verifyPending = true;
        sawPause = false;
        viaShell = true;
        installStartedAt = System.currentTimeMillis();
        shell(installerCommand(), out -> {
            if (out == null || out.contains("Error") || out.contains("[exit ")) {
                print("Installer launch failed: " + out, Color.RED);
            } else {
                print("Installer launched as shell.");
            }
        });
    }

    private interface ShellDone {
        void done(String output); // null = could not run
    }

    /** Runs argv as the shell user through Shizuku; done gets its output on the UI thread. */
    private void shell(final String[] cmd, final ShellDone done) {
        final Shizuku.UserServiceArgs args = new Shizuku.UserServiceArgs(
                new ComponentName(getPackageName(), ShellService.class.getName()))
                .daemon(false).processNameSuffix("shell").debuggable(false).version(2);
        final ServiceConnection conn = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, final IBinder binder) {
                final ServiceConnection self = this;
                new Thread(() -> {
                    String out;
                    try {
                        out = IShellService.Stub.asInterface(binder).run(cmd);
                    } catch (Throwable t) {
                        out = null;
                    }
                    final String o = out;
                    runOnUiThread(() -> done.done(o));
                    try {
                        Shizuku.unbindUserService(args, self, true);
                    } catch (Throwable ignore) {
                        // best effort
                    }
                }).start();
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
            }
        };
        try {
            Shizuku.bindUserService(args, conn);
        } catch (Throwable t) {
            print("Shizuku bind failed: " + t.getMessage(), Color.RED);
        }
    }

    private boolean shizukuReady() {
        try {
            return Shizuku.pingBinder() && !Shizuku.isPreV11()
                    && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * The install went through but lost the Play mark: try once more, launching the
     * installer the other way. Current Android keeps the mark only when the shell starts
     * the installer; some Android 17 builds have that check inverted and keep it only
     * when an app without INSTALL_PACKAGES (UnknownAuto itself) does. Returns false if
     * the other way isn't available.
     */
    private boolean tryOtherPath(PackageInfo now) {
        if (!viaShell && !shizukuReady()) {
            return false; // the app path asks for its permission itself; the shell needs Shizuku
        }
        retried = true;
        installedBefore = now.lastUpdateTime;
        print("The installer dropped the Play mark when started "
                + (viaShell ? "as shell" : "by UnknownAuto")
                + ". Some Android builds keep it only the other way; trying that once…",
                Color.rgb(0xB0, 0x60, 0x00));
        showBanner("…  One more step\nConfirm the update once more.",
                Color.rgb(0xB0, 0x60, 0x00));
        if (!viaShell) {
            runViaShizuku();
        } else if (ensureInstallPermission()) {
            launchInstaller();
        }
        return true;
    }

    private SharedPreferences prefs() {
        return getSharedPreferences("unknownauto", MODE_PRIVATE);
    }

    /**
     * Prints the package installer's own log lines since the install began — the reason
     * an install was cancelled, blocked or lost its Play mark. Only the shell can read
     * other apps' logs, so this needs Shizuku.
     */
    private void printInstallerLog() {
        if (!shizukuReady()) {
            print("(Start Shizuku to see the installer's own reason here.)");
            return;
        }
        String since = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
                .format(new Date(installStartedAt - 2000));
        // Package names are [A-Za-z0-9_.] only, so it is safe inside the quoted pattern.
        String pattern = INSTALLER_LOG + "| PackageManager *:.*(" + targetPackage
                + "|INSTALL_FAILED)";
        shell(new String[]{"/system/bin/sh", "-c", "logcat -d -t '" + since + "' | grep -E '"
                + pattern + "'"}, out -> {
            print("\ninstaller log:");
            String[] lines = out == null ? new String[0] : out.trim().split("\n");
            int from = Math.max(0, lines.length - 40); // the last 40 lines hold the outcome
            for (int i = from; i < lines.length; i++) {
                String l = lines[i];
                if (!l.isEmpty() && !l.startsWith("[exit ")) {
                    print(l.length() > 240 ? l.substring(0, 240) + "…" : l, Color.DKGRAY);
                }
            }
            if (lines.length == 0 || lines[0].isEmpty() || lines[0].startsWith("[exit ")) {
                print("(nothing logged by the installer)");
            }
        });
    }

    /**
     * Log tags of the platform installer, and words of install failures and Play Protect.
     * The package manager is too chatty for its tag alone; printInstallerLog() adds its
     * lines only when they name the target package or a failure.
     */
    private static final String INSTALLER_LOG = " (InstallStart|InstallRepository|InstallStaging"
            + "|InstallInstalling|InstallLaunch|PackageInstaller|PackageInstallerSession) *:"
            + "|INSTALL_FAILED|VerifyApps|Play Protect";

    /** argv the shell runs: launch the Google installer against our content URI, Play-tagged. */
    private String[] installerCommand() {
        String uri = ApkProvider.uriFor().toString();
        return new String[]{
                "/system/bin/am", "start",
                "-n", "com.google.android.packageinstaller/com.android.packageinstaller.InstallStart",
                "-a", "android.intent.action.INSTALL_PACKAGE",
                "-d", uri,
                "-t", "application/vnd.android.package-archive",
                "--es", "android.intent.extra.INSTALLER_PACKAGE_NAME", VENDING,
                "--ez", "android.intent.extra.NOT_UNKNOWN_SOURCE", "true",
                "--ei", "android.intent.extra.INSTALL_REASON", "1",
                "--es", "android.intent.extra.REFERRER_NAME", "android-app://" + VENDING
        };
    }

    // --- get Shizuku onto the phone (when it is not installed) --------------

    /** Downloads the latest official Shizuku APK and opens the installer for it. */
    private void installShizuku() {
        if (isShizukuInstalled()) {
            print("Shizuku is already installed.");
            return;
        }
        print("Downloading the latest Shizuku…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String url = latestShizukuApkUrl();
                    File out = new File(getCacheDir(), ApkProvider.SHIZUKU);
                    httpDownload(url, out);
                    final long kb = out.length() / 1024;
                    runOnUiThread(() -> {
                        print("Downloaded Shizuku (" + kb + " kB).");
                        firePlainInstall(ApkProvider.SHIZUKU);
                    });
                } catch (final Throwable t) {
                    runOnUiThread(() -> {
                        print("Could not download Shizuku: " + t.getMessage(), Color.RED);
                        print("Opening the Shizuku page — install it, then reopen UnknownAuto.");
                        openShizukuStore();
                    });
                }
            }
        }).start();
    }

    private String latestShizukuApkUrl() throws Exception {
        String body = httpGet("https://api.github.com/repos/RikkaApps/Shizuku/releases/latest");
        JSONArray assets = new JSONObject(body).getJSONArray("assets");
        for (int i = 0; i < assets.length(); i++) {
            JSONObject a = assets.getJSONObject(i);
            if (a.getString("name").toLowerCase().endsWith(".apk")) {
                return a.getString("browser_download_url");
            }
        }
        throw new IllegalStateException("no APK in the latest Shizuku release");
    }

    private String httpGet(String url) throws Exception {
        HttpURLConnection c = open(url);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            copy(in, b);
            return b.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }

    private void httpDownload(String url, File out) throws Exception {
        HttpURLConnection c = open(url);
        try (InputStream in = c.getInputStream(); OutputStream os = new FileOutputStream(out)) {
            copy(in, os);
        } finally {
            c.disconnect();
        }
        if (out.length() <= 0) {
            throw new IllegalStateException("empty download");
        }
    }

    private HttpURLConnection open(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setInstanceFollowRedirects(true);
        c.setConnectTimeout(15000);
        c.setReadTimeout(60000);
        c.setRequestProperty("User-Agent", "UnknownAuto");
        c.setRequestProperty("Accept", "application/octet-stream, application/vnd.github+json");
        return c;
    }

    /** Opens the system installer for a downloaded APK (a plain install, no Play tag). */
    private void firePlainInstall(String name) {
        if (!ensureInstallPermission()) {
            return;
        }
        Uri uri = ApkProvider.uriFor(name);
        grantToInstallers(uri);
        Intent in = new Intent(Intent.ACTION_INSTALL_PACKAGE);
        in.setDataAndType(uri, "application/vnd.android.package-archive");
        in.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        in.putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true);
        try {
            startActivity(in);
            print("Tap Install to add Shizuku. Then start it (Developer options → Wireless "
                    + "debugging, then Shizuku → Start) and reopen UnknownAuto.");
        } catch (Exception e) {
            print("No installer handled the request: " + e.getMessage(), Color.RED);
        }
    }

    private void openShizukuStore() {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW,
                    Uri.parse("market://details?id=moe.shizuku.privileged.api")));
        } catch (Throwable t) {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://github.com/RikkaApps/Shizuku/releases/latest")));
            } catch (Throwable ignore) {
                // nothing else to try
            }
        }
    }

    @Override
    protected void onDestroy() {
        try {
            Shizuku.removeRequestPermissionResultListener(permListener);
            Shizuku.removeBinderReceivedListener(binderListener);
        } catch (Throwable ignore) {
            // best effort
        }
        super.onDestroy();
    }

    @Override
    protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        if (req == REQ_PICK) {
            if (result == RESULT_OK && data != null && data.getData() != null) {
                File f = copyUri(data.getData());
                if (f != null && stage(f)) {
                    // Load only: stage + enable the Install button, never install here.
                    banner.setVisibility(View.GONE);
                    print("Ready. Tap “Install” to install " + stagedLabel + " for Android Auto.");
                }
            }
            return;
        }
        if (req != REQ_INSTALL) {
            return;
        }
        if (result == RESULT_OK) {
            print("Installed.");
        } else if (result == RESULT_FIRST_USER) {
            print("Install failed or was cancelled.", Color.RED);
        }
        // Verification happens in onResume, once the install has settled.
    }

    // --- verification: reproduce Android Auto's rule -----------------------

    private void verify() {
        if (targetPackage == null) {
            return;
        }
        PackageInfo now = installedInfo(targetPackage);
        if (now == null) {
            showBanner("✖  " + (stagedLabel != null ? stagedLabel : targetPackage)
                    + " is not installed\nThe install was cancelled or refused — see details.",
                    Color.RED);
            printInstallerLog();
            return;
        }
        try {
            String initiating = null;
            String installer;
            if (Build.VERSION.SDK_INT >= 30) {
                InstallSourceInfo si = getPackageManager().getInstallSourceInfo(targetPackage);
                initiating = si.getInitiatingPackageName();
                installer = si.getInstallingPackageName();
            } else {
                installer = getPackageManager().getInstallerPackageName(targetPackage);
            }
            print("\ninitiatingPackageName = " + initiating);
            print("installerPackageName  = " + installer);
            boolean ok = androidAutoAccepts(initiating, installer, Build.VERSION.SDK_INT);
            String label = labelFor(targetPackage);
            if (ok) {
                showBanner("✔  " + label + " installed\nAndroid Auto will accept it.",
                        Color.rgb(0x1B, 0x7A, 0x1B));
                toast(label + " installed — Android Auto will accept it");
                print("✔ Android Auto will accept " + targetPackage, Color.rgb(0x1B, 0x7A, 0x1B));
                prefs().edit().putBoolean(SHELL_KEEPS_MARK, viaShell).apply();
            } else if (now.lastUpdateTime == installedBefore) {
                // Nothing was installed: what we read is the old (sideload) record.
                showBanner("✖  " + label + " was not installed\n"
                        + "The install was cancelled or refused — see details.", Color.RED);
                print("✖ nothing was installed — " + label + " still has its old install "
                        + "record (installer=" + installer + ")", Color.RED);
                printInstallerLog();
            } else {
                print("✖ installed without the Play mark (initiator=" + initiating
                        + ", installer=" + installer + ")", Color.RED);
                if (!retried && tryOtherPath(now)) {
                    return;
                }
                showBanner("✖  Installed, but without the Google Play mark\n"
                        + "Android Auto won't accept it — see details.", Color.RED);
                if (!viaShell && !shizukuReady()) {
                    print("Start Shizuku and tap Install again: this phone needs the "
                            + "installer started as shell.", Color.rgb(0xB0, 0x60, 0x00));
                }
                printInstallerLog();
            }
        } catch (PackageManager.NameNotFoundException e) {
            showBanner("✖  " + targetPackage + " is not installed", Color.RED);
        }
    }

    private String labelFor(String pkg) {
        try {
            PackageManager pm = getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (Throwable t) {
            return stagedLabel != null ? stagedLabel : pkg;
        }
    }

    private void showBanner(String text, int color) {
        runOnUiThread(() -> {
            banner.setText(text);
            banner.setTextColor(color);
            banner.setVisibility(View.VISIBLE);
        });
    }

    private void toast(String s) {
        runOnUiThread(() -> Toast.makeText(getApplicationContext(), s, Toast.LENGTH_LONG).show());
    }

    /** Drops any loaded APK and disables the Install button (e.g. after a failed load). */
    private void clearStaged() {
        staged = null;
        stagedInfo = null;
        targetPackage = null;
        stagedLabel = null;
        updateInstallButton();
    }

    private void updateInstallButton() {
        if (installButton == null) {
            return;
        }
        runOnUiThread(() -> {
            if (targetPackage != null) {
                installButton.setEnabled(true);
                installButton.setText("Install " + (stagedLabel != null ? stagedLabel : targetPackage)
                        + " for Android Auto");
            } else {
                installButton.setEnabled(false);
                installButton.setText("Install for Android Auto (load an APK first)");
            }
        });
    }

    /**
     * The check Android Auto applies to a projected app's install source
     * (reconstructed from the Gearhead validator and the AA-Enabler diagnostic).
     * On API 30+ the initiating package must be Google Play or the system
     * package installer, never the adb shell, and the installing package must
     * be Google Play.
     */
    static boolean androidAutoAccepts(String initiating, String installer, int sdk) {
        if (sdk < 30) {
            return VENDING.equals(installer);
        }
        if (initiating == null || installer == null || "com.android.shell".equals(initiating)) {
            return false;
        }
        return VENDING.equals(installer)
                && (VENDING.equals(initiating) || initiating.contains("packageinstaller"));
    }

    // --- tiny UI -----------------------------------------------------------

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setFitsSystemWindows(true); // keep content below the status bar
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("UnknownAuto");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("Install with Google Play attribution, so Android Auto accepts it.");
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        sub.setPadding(0, dp(2), 0, dp(12));
        root.addView(sub);

        banner = new TextView(this);
        banner.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        banner.setTypeface(null, Typeface.BOLD);
        banner.setPadding(0, dp(4), 0, dp(8));
        banner.setVisibility(View.GONE);
        root.addView(banner);

        // 1. Load — pick an APK and stage it; does NOT install.
        Button load = new Button(this);
        load.setText("Load APK…");
        load.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Intent g = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                g.addCategory(Intent.CATEGORY_OPENABLE);
                g.setType("application/vnd.android.package-archive");
                startActivityForResult(g, REQ_PICK);
            }
        });
        root.addView(load, buttonParams(dp(4)));

        // 2. Install — installs the loaded APK; disabled until an APK is loaded.
        installButton = new Button(this);
        installButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (staged == null || targetPackage == null) {
                    print("Nothing loaded. Tap “Load APK…” first.",
                            Color.rgb(0xB0, 0x60, 0x00));
                    return;
                }
                banner.setVisibility(View.GONE);
                retried = false;
                if (!preflight()) {
                    return;
                }
                // Shell first (what current Android keeps the mark for), unless this phone
                // already showed that only the app path keeps it.
                if (prefs().getBoolean(SHELL_KEEPS_MARK, true) && tryShizukuInstall()) {
                    return;
                }
                if (ensureInstallPermission()) {
                    launchInstaller();
                }
            }
        });
        root.addView(installButton, buttonParams(dp(4)));
        updateInstallButton();

        // Shizuku status / installer (phone-only path) — behaviour unchanged.
        Button shizuku = new Button(this);
        if (isShizukuInstalled()) {
            shizuku.setText("Shizuku: installed");
            shizuku.setEnabled(false);
        } else {
            shizuku.setText("Install Shizuku (for phone-only)");
            shizuku.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    installShizuku();
                }
            });
        }
        root.addView(shizuku, buttonParams(dp(16)));

        // Technical details — hidden by default, revealed by the toggle below.
        final Button details = new Button(this);
        details.setText("Show details ▾");
        root.addView(details, buttonParams(dp(16)));

        log = new TextView(this);
        log.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        log.setTextIsSelectable(true);
        log.setPadding(0, dp(4), 0, dp(4));
        scroll = new ScrollView(this);
        scroll.addView(log);
        scroll.setVisibility(View.GONE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        lp.topMargin = dp(4);
        lp.bottomMargin = dp(8);
        root.addView(scroll, lp);

        details.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                boolean show = scroll.getVisibility() != View.VISIBLE;
                scroll.setVisibility(show ? View.VISIBLE : View.GONE);
                details.setText(show ? "Hide details ▴" : "Show details ▾");
                if (show) {
                    scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
                }
            }
        });

        setContentView(root);
    }

    /** Full-width button layout params with a top margin for even spacing. */
    private LinearLayout.LayoutParams buttonParams(int topMargin) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        p.topMargin = topMargin;
        return p;
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        log.setText("");
        handleIntent();
    }

    private void print(String s) {
        print(s, Color.DKGRAY);
    }

    private void print(String s, int color) {
        runOnUiThread(() -> {
            android.text.SpannableString ss = new android.text.SpannableString(s + "\n");
            ss.setSpan(new android.text.style.ForegroundColorSpan(color), 0, ss.length(), 0);
            log.append(ss);
            if (scroll != null && scroll.getVisibility() == View.VISIBLE) {
                scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
            }
        });
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    private static void copy(InputStream in, OutputStream out) throws java.io.IOException {
        byte[] buf = new byte[1 << 16];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
        out.flush();
    }
}
