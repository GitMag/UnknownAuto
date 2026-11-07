package com.mag.unknownauto;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Intent;
import android.content.IntentSender;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.InstallSourceInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
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
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
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
 * <p>Result on Android 17 (verified on a Pixel 9) and Android 14 (emulator):
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
 *
 * <p>Any of these may also be an .xapk ({@link Xapk}). One holding a single APK installs
 * like that APK; one split into several APKs installs them together in one install session.
 * On a rooted phone ({@link Root}) that session is created under Google Play's own account
 * ({@code su <play-uid> pm install-create}) and committed as root, so even a split gets the
 * full Android Auto mark; without root it goes through this app's own PackageInstaller,
 * which Android won't give the Play mark. Expansion files (OBB) are copied to
 * {@code Android/obb/<package>/}.
 */
public final class InstallActivity extends Activity {

    private static final String VENDING = "com.android.vending";
    private static final String KOFI_URL = "https://ko-fi.com/forsblomdev";
    private static final int REQ_INSTALL = 1;
    private static final int REQ_PICK = 2;
    private static final int REQ_SESSION = 4;
    private static final int REQ_SHIZUKU = 3000;
    private static final String INCOMING = "incoming.apk"; // a picked file, copied into cache
    private static final String BUNDLE_DIR = "bundle";     // an XAPK's APKs and OBBs, unpacked

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
    private Xapk bundle;            // the loaded XAPK when it has splits or OBBs; else null
    // Loads (copy, unpack, parse) run here in order, off the UI thread.
    private final java.util.concurrent.ExecutorService loader =
            java.util.concurrent.Executors.newSingleThreadExecutor();
    private boolean splitInstall;   // this install is a split XAPK's session (no Play mark)

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
        if (Root.binaryPresent()) {
            print("Root (su) detected — a multi-APK XAPK installs with the full Play mark "
                    + "through it.");
        }
        try {
            Shizuku.addBinderReceivedListenerSticky(binderListener);
        } catch (Throwable ignore) {
            // Shizuku classes always present; guard anyway
        }
        handleIntent();
    }

    /**
     * Handles the current intent (onCreate and onNewIntent): loads any APK, never installs.
     * Off the UI thread, as an XAPK can be large; loads run one at a time.
     */
    private void handleIntent() {
        final Intent it = getIntent();
        clearStaged();
        loader.execute(() -> {
            final File src = resolveSource(it);
            final boolean ok = src != null && stage(src);
            runOnUiThread(() -> afterLoad(it, src, ok));
        });
    }

    private void afterLoad(Intent it, File src, boolean ok) {
        if (src == null) {
            print("No APK loaded yet. Tap “Load APK or XAPK…” to choose one, "
                    + "or open an .apk or .xapk with UnknownAuto.", Color.rgb(0xB0, 0x60, 0x00));
            return;
        }
        if (!ok) {
            return;
        }
        // "stage + grant only": stage the APK and grant read to the installer, but
        // let the SHELL (adb / Shizuku) launch the system installer, so the install
        // name com.android.vending is honoured.
        if (it != null && it.getBooleanExtra("stageonly", false)) {
            if (isSplit()) {
                print("stageonly works for a single APK only.", Color.RED);
                return;
            }
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
        print(readyLine());
    }

    private String readyLine() {
        return "Ready. Tap “Install” to install " + stagedLabel
                + (isSplit() ? "." : " for Android Auto.");
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

    private File resolveSource(Intent it) {
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
        File out = new File(getCacheDir(), INCOMING);
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

    /** Loads an APK or an XAPK (told apart by content, not by name). */
    private boolean stage(File src) {
        print("Loading…");
        bundle = null;
        Xapk.deleteTree(new File(getCacheDir(), BUNDLE_DIR));
        if (Xapk.isBundle(src)) {
            return stageBundle(src);
        }
        boolean ok = stageApk(src, true);
        updateInstallButton();
        return ok;
    }

    /**
     * Unpacks an XAPK. A single APK is then staged like any APK (Play mark as usual);
     * several stay unpacked for one session install, which can't carry the Play mark.
     */
    private boolean stageBundle(File src) {
        Xapk x;
        try {
            x = Xapk.extract(src, new File(getCacheDir(), BUNDLE_DIR));
        } catch (Exception e) {
            print("Could not read the XAPK: " + e.getMessage(), Color.RED);
            clearStaged();
            return false;
        }
        if (src.equals(new File(getCacheDir(), INCOMING))) {
            src.delete(); // its contents are unpacked; don't keep the XAPK twice
        }
        print("XAPK: " + x.describe());
        boolean split = x.apks.size() > 1;
        if (!stageApk(x.base, !split)) {
            return false;
        }
        if (x.packageName != null && !x.packageName.isEmpty()
                && !x.packageName.equals(targetPackage)) {
            print("✖ manifest.json names " + x.packageName + " but the base APK is "
                    + targetPackage + ".", Color.RED);
            clearStaged();
            return false;
        }
        for (int i = x.obbs.size() - 1; i >= 0; i--) {
            if (!x.obbs.get(i).pkg.equals(targetPackage)) {
                print("Ignoring an OBB for another app: " + x.obbs.get(i).pkg,
                        Color.rgb(0xB0, 0x60, 0x00));
                x.obbs.remove(i);
            }
        }
        if (!split) {
            x.base.delete(); // staged as install.apk
        }
        bundle = split || !x.obbs.isEmpty() ? x : null;
        if (split) {
            print("This XAPK is split into " + x.apks.size() + " APKs. A rooted phone installs "
                    + "them with the full Google Play mark (the session is created under Google "
                    + "Play as root); without root Android's installer gives the mark to a single "
                    + "APK only, so they install together without it.", Color.rgb(0xB0, 0x60, 0x00));
        }
        updateInstallButton();
        return true;
    }

    /** True if the loaded app is a split XAPK (installed by session, without the mark). */
    private boolean isSplit() {
        return bundle != null && bundle.apks.size() > 1;
    }

    /**
     * Parses an APK and makes it the loaded app. With {@code copy} it is first copied to
     * install.apk, the one file ApkProvider serves to the system installer.
     */
    private boolean stageApk(File src, boolean copy) {
        if (copy) {
            staged = new File(getCacheDir(), ApkProvider.NAME);
            try (InputStream in = new FileInputStream(src);
                 OutputStream os = new FileOutputStream(staged)) {
                copy(in, os);
            } catch (Exception e) {
                print("Could not stage the APK: " + e.getMessage(), Color.RED);
                clearStaged();
                return false;
            }
        } else {
            staged = src;
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
        // Android 14 and older take the installer's caller from who waits for its result,
        // and `am start` waits for none: the installer then checks the shell itself, which
        // never declares REQUEST_INSTALL_PACKAGES, and aborts. There the shell starts it
        // with this activity as the result target instead: the caller is UnknownAuto (it
        // declares the permission, so it needs "Install unknown apps") and the launcher is
        // the shell (so the Play name is kept). Android 15+ checks the launcher only.
        final boolean asResult = Build.VERSION.SDK_INT <= 34;
        if (asResult && !ensureInstallPermission()) {
            return;
        }
        grantToInstallers(ApkProvider.uriFor());
        print("asking Shizuku to run the installer as shell…");
        print("tap Update / Install if it asks (and “Install anyway” if Play Protect asks)…");
        verifyPending = true;
        sawPause = false;
        viaShell = true;
        installStartedAt = System.currentTimeMillis();
        if (asResult) {
            final Intent in = shellInstallIntent();
            final IBinder self = getWindow().getAttributes().token; // this activity's token
            withShell(s -> s.startForResult(in, self, REQ_INSTALL), out -> {
                if ("ok".equals(out)) {
                    print("Installer launched as shell.");
                } else {
                    verifyPending = false;
                    print("Installer launch failed: " + out, Color.RED);
                }
            });
            return;
        }
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

    private interface ShellCall {
        String call(IShellService s) throws Exception;
    }

    /** Runs argv as the shell user through Shizuku; done gets its output on the UI thread. */
    private void shell(final String[] cmd, final ShellDone done) {
        withShell(s -> s.run(cmd), done);
    }

    /**
     * Binds the shell-user service through Shizuku and makes one call on it; done gets
     * its return value on the UI thread (null also when the call itself failed).
     */
    private void withShell(final ShellCall call, final ShellDone done) {
        final Shizuku.UserServiceArgs args = new Shizuku.UserServiceArgs(
                new ComponentName(getPackageName(), ShellService.class.getName()))
                .daemon(false).processNameSuffix("shell").debuggable(false).version(4);
        final ServiceConnection conn = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, final IBinder binder) {
                final ServiceConnection self = this;
                new Thread(() -> {
                    String out;
                    try {
                        out = call.call(IShellService.Stub.asInterface(binder));
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

    /**
     * The same launch as {@link #installerCommand()}, as an Intent the shell starts for a
     * result. No URI-grant flag: the shell can't grant our URI (grantToInstallers did).
     */
    private Intent shellInstallIntent() {
        Intent in = new Intent(Intent.ACTION_INSTALL_PACKAGE);
        in.setComponent(new ComponentName("com.google.android.packageinstaller",
                "com.android.packageinstaller.InstallStart"));
        in.setDataAndType(ApkProvider.uriFor(), "application/vnd.android.package-archive");
        in.putExtra("android.intent.extra.INSTALLER_PACKAGE_NAME", VENDING);
        in.putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true);
        in.putExtra("android.intent.extra.INSTALL_REASON", 1);
        in.putExtra(Intent.EXTRA_REFERRER_NAME, "android-app://" + VENDING);
        in.putExtra(Intent.EXTRA_RETURN_RESULT, true);
        return in;
    }

    // --- split XAPK: install as root when the phone is rooted ---------------

    /**
     * Entry point for a split (multi-APK) XAPK. Picks the best path on a background thread:
     * <ol>
     *   <li><b>Rooted, Google Play present (full mark):</b> root creates the install session
     *       under Google Play's own account ({@code su <play-uid> -c 'pm install-create -i
     *       com.android.vending …'}), then writes the APKs and commits as root. Android
     *       records Play as BOTH the initiating and the installing package, so even a split
     *       projects in Android Auto. Writing and committing run as root because a session's
     *       owner can't stream an APK from a pipe (reverse mode is shell/root only), and the
     *       install source is fixed at create time, so committing as root doesn't change
     *       it.</li>
     *   <li><b>Rooted, no Play (or the above failed):</b> install as root with
     *       {@code pm install -i com.android.vending} ({@link #rootInstallScript}) —
     *       installer=Play, but initiating=com.android.shell, so a split won't project on
     *       API 30+ (installer=Play alone is enough on API &lt; 30).</li>
     *   <li><b>No root:</b> this app's own PackageInstaller session, no Play mark.</li>
     * </ol>
     */
    private void installSplitBundle() {
        splitInstall = true;
        viaShell = false;
        verifyPending = false; // these paths verify when they return, not via onResume
        installStartedAt = System.currentTimeMillis();
        print("Installing " + bundle.apks.size() + " APKs…");
        new Thread(this::doSplitInstall).start();
    }

    /** Runs on a worker thread: probe root, then take the best path (see installSplitBundle). */
    private void doSplitInstall() {
        final List<File> apks = new ArrayList<>(bundle.apks);
        final String probe = Root.run("echo UA_ROOT_$(id -u)");
        if (probe == null || !probe.contains("UA_ROOT_0")) {
            final boolean denied = probe != null && probe.contains("UA_ROOT_");
            runOnUiThread(() -> {
                print(denied ? "Root was denied — installing without the Play mark."
                             : "No root on this phone — installing without the Play mark.",
                        Color.rgb(0xB0, 0x60, 0x00));
                installSplits();
            });
            return;
        }
        final int vuid = vendingUid();
        if (vuid > 0) {
            runOnUiThread(() -> print("Installing as root, under Google Play…"));
            final String out = Root.run(fullMarkScript(apks, vuid));
            runOnUiThread(() -> afterFullMark(apks, out));
        } else {
            runOnUiThread(() -> {
                print("Google Play isn't installed; installing as root with the Play installer "
                        + "name only.", Color.rgb(0xB0, 0x60, 0x00));
                installSplitsRootPlain(apks);
            });
        }
    }

    /** uid of the Google Play app, or -1 if it isn't installed. */
    private int vendingUid() {
        try {
            return getPackageManager().getPackageUid(VENDING, 0);
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * The full-mark root script: create the session as Google Play's uid (so Play is the
     * initiating package), then write each APK and commit as root — root may stream into and
     * commit another owner's session, and the install source stays as set at create time.
     * {@code --user 0} avoids a current-user lookup that Play's uid may not be allowed; it
     * assumes the owner user (true on an ordinary single-user phone, which is the use case).
     */
    private String fullMarkScript(List<File> apks, int vuid) {
        StringBuilder s = new StringBuilder();
        s.append("echo UA_ROOT_$(id -u)\n");
        s.append("SID=$(su ").append(vuid).append(" -c 'pm install-create --user 0 -i ")
                .append(VENDING)
                .append(" -r --install-reason 1' | sed -n 's/.*\\[\\([0-9]*\\)\\].*/\\1/p')\n");
        s.append("[ -z \"$SID\" ] && { echo UA_NOSID; exit 0; }\n");
        for (File f : apks) {
            s.append("cat ").append(Root.q(f.getAbsolutePath())).append(" | pm install-write -S ")
                    .append(f.length()).append(" $SID ").append(Root.q(f.getName()))
                    .append(" - 2>&1\n");
        }
        s.append("pm install-commit $SID 2>&1 && echo UA_OK || echo UA_FAIL\n");
        return s.toString();
    }

    /** Result of the full-mark attempt: success -> verify; failure -> installer-name-only root. */
    private void afterFullMark(List<File> apks, String out) {
        if (out == null || !out.contains("UA_ROOT_0")) {
            print("Root was denied — installing without the Play mark.", Color.rgb(0xB0, 0x60, 0x00));
            installSplits();
            return;
        }
        if (out.contains("UA_OK")) {
            print("Installed as root, under Google Play.");
            verify();
            return;
        }
        print("The full Play mark didn't go through; installing as root with the Play installer "
                + "name only.", Color.rgb(0xB0, 0x60, 0x00));
        printPmLines(out);
        installSplitsRootPlain(apks);
    }

    /** Fallback: install the splits as root with {@code pm install -i vending} (installer only). */
    private void installSplitsRootPlain(List<File> apks) {
        print("Installing " + apks.size() + " APKs as root…");
        new Thread(() -> {
            final String out = Root.run(rootInstallScript(apks));
            runOnUiThread(() -> afterRootInstall(apks, out));
        }).start();
    }

    /** Prints a root command's output, dropping our markers and empty lines. */
    private void printPmLines(String out) {
        if (out == null) {
            return;
        }
        for (String line : out.split("\n")) {
            String l = line.trim();
            if (!l.isEmpty() && !l.startsWith("UA_")) {
                print(l.length() > 240 ? l.substring(0, 240) + "…" : l, Color.DKGRAY);
            }
        }
    }

    /**
     * The script the plain-root fallback runs: create one install session named as Google
     * Play, write each APK into it, commit. Markers let afterRootInstall() tell "no root"
     * from "ran as root but failed".
     */
    private String rootInstallScript(List<File> apks) {
        StringBuilder s = new StringBuilder();
        s.append("echo UA_ROOT_$(id -u)\n");
        // Parse the session id from the "[12345]" the installer prints, not every digit in
        // the output — a warning line to stderr would corrupt a tr -dc '0-9' parse.
        s.append("sid=$(pm install-create -r -i ").append(VENDING)
                .append(" --install-reason 1 2>&1 | sed -n 's/.*\\[\\([0-9]*\\)\\].*/\\1/p')\n");
        s.append("[ -z \"$sid\" ] && { echo UA_FAIL; exit 0; }\n");
        for (File f : apks) {
            s.append("pm install-write $sid ").append(Root.q(f.getName())).append(' ')
                    .append(Root.q(f.getAbsolutePath())).append(" 2>&1\n");
        }
        s.append("pm install-commit $sid 2>&1 && echo UA_OK || echo UA_FAIL\n");
        return s.toString();
    }

    /** Handles the root install's output: no root -> fall back; else verify the result. */
    private void afterRootInstall(List<File> apks, String out) {
        if (out == null || !out.contains("UA_ROOT_0")) {
            if (out != null && out.contains("UA_ROOT_")) {
                print("Root was denied — installing the ordinary way (no Play mark).",
                        Color.rgb(0xB0, 0x60, 0x00));
            } else {
                print("No root on this phone — installing the ordinary way (no Play mark).",
                        Color.rgb(0xB0, 0x60, 0x00));
            }
            installSplits(); // app's own PackageInstaller session; shows Android's prompt
            return;
        }
        if (out.contains("UA_OK")) {
            print("Installed " + apks.size() + " APKs as root.");
            verify();
            return;
        }
        showBanner("✖  " + stagedLabel + " was not installed\n"
                + "The root install failed — see details.", Color.RED);
        print("✖ root install failed:", Color.RED);
        for (String line : out.split("\n")) {
            String l = line.trim();
            if (l.isEmpty() || l.startsWith("UA_")) {
                continue;
            }
            print(l.length() > 240 ? l.substring(0, 240) + "…" : l, Color.DKGRAY);
        }
        verify(); // report the real state (usually: still not installed)
    }

    // --- split XAPK, no root: one PackageInstaller session (no Play mark) ----

    /**
     * Installs a split XAPK's APKs together in one PackageInstaller session; Android shows
     * its own install prompt. The system installer app takes a single APK only, so this
     * can't carry the Google Play mark; verify() says so.
     */
    private void installSplits() {
        if (!ensureInstallPermission()) {
            return;
        }
        final List<File> apks = new ArrayList<>(bundle.apks);
        final String pkg = targetPackage;
        // The result comes back to onActivityResult; only the system holds this sender.
        // Mutable, so the installer can fill in the status.
        Intent result = new Intent("com.mag.unknownauto.SESSION_RESULT").setPackage(getPackageName());
        int flags = PendingIntent.FLAG_UPDATE_CURRENT
                | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
        final IntentSender sender = createPendingResult(REQ_SESSION, result, flags).getIntentSender();
        splitInstall = true;
        viaShell = false;
        verifyPending = false; // the session reports its own result
        installStartedAt = System.currentTimeMillis();
        print("Writing " + apks.size() + " APKs into one install…");
        new Thread(() -> {
            PackageInstaller installer = getPackageManager().getPackageInstaller();
            int id = -1;
            try {
                PackageInstaller.SessionParams p = new PackageInstaller.SessionParams(
                        PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                p.setAppPackageName(pkg);
                p.setInstallReason(PackageManager.INSTALL_REASON_USER);
                long size = 0;
                for (File f : apks) {
                    size += f.length();
                }
                p.setSize(size);
                id = installer.createSession(p);
                try (PackageInstaller.Session session = installer.openSession(id)) {
                    for (File f : apks) {
                        try (InputStream in = new FileInputStream(f);
                             OutputStream out = session.openWrite(f.getName(), 0, f.length())) {
                            copy(in, out);
                            session.fsync(out);
                        }
                    }
                    session.commit(sender);
                }
                runOnUiThread(() -> print("Tap “Install” on the phone…"));
            } catch (Exception e) {
                if (id >= 0) {
                    try {
                        installer.abandonSession(id);
                    } catch (Exception ignore) {
                        // already gone
                    }
                }
                final String why = String.valueOf(e.getMessage());
                runOnUiThread(() -> {
                    showBanner("✖  " + stagedLabel + " was not installed\n"
                            + "Its APKs could not be handed to Android — see details.", Color.RED);
                    print("Could not write the install: " + why, Color.RED);
                });
            }
        }).start();
    }

    /** The session's progress: the prompt to show, then its outcome. */
    @SuppressWarnings("deprecation")
    private void onSessionResult(Intent data) {
        if (data == null || !splitInstall) {
            return;
        }
        int status = data.getIntExtra(PackageInstaller.EXTRA_STATUS,
                PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = data.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm != null) {
                try {
                    startActivity(confirm);
                } catch (Exception e) {
                    print("Could not open the install prompt: " + e.getMessage(), Color.RED);
                }
            }
            return;
        }
        if (status == PackageInstaller.STATUS_SUCCESS) {
            print("Installed.");
            verify();
            return;
        }
        String msg = data.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
        boolean cancelled = status == PackageInstaller.STATUS_FAILURE_ABORTED;
        showBanner("✖  " + stagedLabel + " was not installed\n"
                + (cancelled ? "The install was cancelled." : "Android refused it — see details."),
                Color.RED);
        print("✖ install " + statusName(status) + (msg != null ? ": " + msg : ""), Color.RED);
        if (!cancelled) {
            printInstallerLog();
        }
    }

    private static String statusName(int status) {
        switch (status) {
            case PackageInstaller.STATUS_FAILURE_ABORTED: return "cancelled";
            case PackageInstaller.STATUS_FAILURE_BLOCKED: return "blocked";
            case PackageInstaller.STATUS_FAILURE_CONFLICT: return "conflicts with an installed app";
            case PackageInstaller.STATUS_FAILURE_INCOMPATIBLE: return "incompatible with this phone";
            case PackageInstaller.STATUS_FAILURE_INVALID: return "invalid";
            case PackageInstaller.STATUS_FAILURE_STORAGE: return "out of storage";
            default: return "failed";
        }
    }

    // --- XAPK expansion files (OBB) ------------------------------------------

    /**
     * Copies the XAPK's OBB files to Android/obb/&lt;package&gt;/ once the app is installed.
     * An app allowed to install apps may write there on current Android; where it may not,
     * the shell does it through Shizuku.
     */
    private void copyObbs() {
        if (bundle == null || bundle.obbs.isEmpty()) {
            return;
        }
        final List<Xapk.Obb> obbs = new ArrayList<>(bundle.obbs);
        final File root = new File(Environment.getExternalStorageDirectory(), "Android/obb");
        print("Copying " + obbs.size() + " OBB file" + (obbs.size() > 1 ? "s" : "")
                + " to " + root + "/" + targetPackage + "…");
        new Thread(() -> {
            final List<Xapk.Obb> left = new ArrayList<>();
            for (Xapk.Obb o : obbs) {
                if (!copyObbDirect(o, root)) {
                    left.add(o);
                }
            }
            runOnUiThread(() -> {
                if (left.isEmpty()) {
                    print("OBB files copied.", Color.rgb(0x1B, 0x7A, 0x1B));
                } else {
                    copyObbsPrivileged(left, root);
                }
            });
        }).start();
    }

    /** Writes the OBB files Android refused us, as root if the phone is rooted, else Shizuku. */
    private void copyObbsPrivileged(final List<Xapk.Obb> obbs, final File root) {
        new Thread(() -> {
            final String out = Root.run(obbCopyScript(obbs, root));
            final boolean ok = out != null && out.contains("UA_ROOT_0") && out.contains("UA_OBB_OK");
            runOnUiThread(() -> {
                if (ok) {
                    print("OBB files copied (as root).", Color.rgb(0x1B, 0x7A, 0x1B));
                } else if (shizukuReady()) {
                    copyObbsAsShell(obbs, root);
                } else {
                    print("✖ Android didn't let UnknownAuto write the OBB files. Grant root or "
                            + "start Shizuku, then load the XAPK and install it again.", Color.RED);
                }
            });
        }).start();
    }

    /** Script a root shell runs to place the expansion files under Android/obb/&lt;package&gt;/. */
    private String obbCopyScript(List<Xapk.Obb> obbs, File root) {
        StringBuilder s = new StringBuilder();
        s.append("echo UA_ROOT_$(id -u)\n");
        s.append("ok=1\n");
        for (Xapk.Obb o : obbs) {
            String dir = new File(root, o.pkg).getAbsolutePath();
            String dest = new File(new File(root, o.pkg), o.name).getAbsolutePath();
            s.append("mkdir -p ").append(Root.q(dir)).append(" && cp -f ")
                    .append(Root.q(o.file.getAbsolutePath())).append(' ').append(Root.q(dest))
                    .append(" || ok=0\n");
        }
        s.append("[ \"$ok\" = 1 ] && echo UA_OBB_OK || echo UA_OBB_FAIL\n");
        return s.toString();
    }

    /** Writes one OBB file itself; false if Android doesn't allow it. */
    private static boolean copyObbDirect(Xapk.Obb o, File root) {
        File dir = new File(root, o.pkg);
        File part = new File(dir, o.name + ".part");
        try {
            if (!dir.isDirectory() && !dir.mkdirs()) {
                return false;
            }
            try (InputStream in = new FileInputStream(o.file);
                 OutputStream os = new FileOutputStream(part)) {
                copy(in, os);
            }
            File out = new File(dir, o.name);
            if (out.exists() && !out.delete()) {
                part.delete();
                return false;
            }
            return part.renameTo(out);
        } catch (Exception e) {
            part.delete();
            return false;
        }
    }

    private void copyObbsAsShell(final List<Xapk.Obb> obbs, final File root) {
        withShell(s -> {
            StringBuilder failed = new StringBuilder();
            for (Xapk.Obb o : obbs) {
                String r;
                try (ParcelFileDescriptor fd = ParcelFileDescriptor.open(o.file,
                        ParcelFileDescriptor.MODE_READ_ONLY)) {
                    r = s.copyIn(fd, new File(new File(root, o.pkg), o.name).getPath());
                }
                if (!"ok".equals(r)) {
                    failed.append("\n").append(o.name).append(": ").append(r);
                }
            }
            return failed.length() == 0 ? "ok" : failed.toString();
        }, out -> {
            if ("ok".equals(out)) {
                print("OBB files copied (through Shizuku).", Color.rgb(0x1B, 0x7A, 0x1B));
            } else {
                print("✖ Could not copy the OBB files:" + (out != null ? out : " Shizuku failed"),
                        Color.RED);
            }
        });
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
        loader.shutdown();
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
        if (req == REQ_SESSION) {
            onSessionResult(data);
            return;
        }
        if (req == REQ_PICK) {
            if (result == RESULT_OK && data != null && data.getData() != null) {
                final Uri uri = data.getData();
                clearStaged();
                loader.execute(() -> {
                    File f = copyUri(uri);
                    final boolean ok = f != null && stage(f);
                    runOnUiThread(() -> {
                        if (ok) {
                            // Load only: stage + enable the Install button, never install here.
                            banner.setVisibility(View.GONE);
                            print(readyLine());
                        }
                    });
                });
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
                if (!splitInstall) {
                    prefs().edit().putBoolean(SHELL_KEEPS_MARK, viaShell).apply();
                }
                copyObbs();
            } else if (now.lastUpdateTime == installedBefore) {
                // Nothing was installed: what we read is the old (sideload) record.
                showBanner("✖  " + label + " was not installed\n"
                        + "The install was cancelled or refused — see details.", Color.RED);
                print("✖ nothing was installed — " + label + " still has its old install "
                        + "record (installer=" + installer + ")", Color.RED);
                printInstallerLog();
            } else if (splitInstall) {
                // Expected: a session install can't carry the mark (see installSplits).
                showBanner("✔  " + label + " installed, without the Google Play mark\n"
                        + "Android Auto shows it only with Unknown sources on in its developer "
                        + "settings, and not at all if it's a Car App Library app.",
                        Color.rgb(0xB0, 0x60, 0x00));
                print("Installed from a split XAPK without the Play mark (installer=" + installer
                        + ", initiating=com.android.shell). The full-mark path (create the "
                        + "session under Google Play as root) wasn't used or fell back. Android "
                        + "Auto won't project this install.", Color.rgb(0xB0, 0x60, 0x00));
                copyObbs();
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
                copyObbs();
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
        bundle = null;
        updateInstallButton();
    }

    private void updateInstallButton() {
        if (installButton == null) {
            return;
        }
        runOnUiThread(() -> {
            if (targetPackage != null) {
                installButton.setEnabled(true);
                String name = stagedLabel != null ? stagedLabel : targetPackage;
                installButton.setText(isSplit() ? "Install " + name + " (multi-APK)"
                        : "Install " + name + " for Android Auto");
            } else {
                installButton.setEnabled(false);
                installButton.setText("Install for Android Auto (load an APK or XAPK first)");
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

        // 1. Load — pick an APK or XAPK and stage it; does NOT install.
        Button load = new Button(this);
        load.setText("Load APK or XAPK…");
        load.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Intent g = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                g.addCategory(Intent.CATEGORY_OPENABLE);
                // Android has no type for .xapk: the picker lists it as octet-stream.
                g.setType("*/*");
                g.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                        "application/vnd.android.package-archive",
                        "application/xapk-package-archive",
                        "application/octet-stream", "application/zip"});
                startActivityForResult(g, REQ_PICK);
            }
        });
        root.addView(load, buttonParams(dp(4)));

        // 2. Install — installs the loaded APK; disabled until an APK is loaded.
        installButton = new Button(this);
        installButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (staged == null || targetPackage == null) {
                    print("Nothing loaded. Tap “Load APK or XAPK…” first.",
                            Color.rgb(0xB0, 0x60, 0x00));
                    return;
                }
                banner.setVisibility(View.GONE);
                retried = false;
                splitInstall = false;
                if (!preflight()) {
                    return;
                }
                if (isSplit()) {
                    installSplitBundle();
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

        // Keeps the support link at the bottom while the details are hidden.
        final View spacer = new View(this);
        root.addView(spacer, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        TextView support = new TextView(this);
        support.setText("♥ Support UnknownAuto on Ko-fi");
        support.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        support.setTextColor(Color.rgb(0xC0, 0x3A, 0x3A));
        support.setGravity(Gravity.CENTER);
        support.setPadding(0, dp(12), 0, dp(12)); // a comfortable tap target
        support.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(KOFI_URL)));
                } catch (Exception e) {
                    toast(KOFI_URL);
                }
            }
        });
        root.addView(support);

        details.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                boolean show = scroll.getVisibility() != View.VISIBLE;
                scroll.setVisibility(show ? View.VISIBLE : View.GONE);
                spacer.setVisibility(show ? View.GONE : View.VISIBLE);
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
