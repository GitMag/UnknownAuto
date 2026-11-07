package com.mag.unknownauto;

import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Reads an .xapk: a zip that holds an app's APKs (one base APK and optionally split APKs
 * such as {@code config.arm64_v8a.apk}, {@code config.xxhdpi.apk}, {@code config.en.apk})
 * and, for some games, expansion files under {@code Android/obb/<package>/}. Its
 * {@code manifest.json} names the package and which APK is the base. A zip of APKs
 * without a manifest.json (an .apks file) works too when the base is clear.
 */
final class Xapk {

    /** An expansion file (OBB) and where it belongs, relative to shared storage. */
    static final class Obb {
        final File file;
        final String pkg;     // the package whose Android/obb folder it goes in
        final String name;    // file name inside that folder

        Obb(File file, String pkg, String name) {
            this.file = file;
            this.pkg = pkg;
            this.name = name;
        }
    }

    final File base;                               // the base APK
    final List<File> apks = new ArrayList<>();     // what to install: the base first, then splits
    final List<String> skipped = new ArrayList<>(); // splits for CPU types this phone lacks
    final List<Obb> obbs = new ArrayList<>();
    final String packageName;                      // from manifest.json; null if none

    private Xapk(File base, String packageName) {
        this.base = base;
        this.packageName = packageName;
    }

    // Plain file names only: no folders, nothing hidden (zip entries are untrusted).
    private static final Pattern APK = Pattern.compile("[A-Za-z0-9_][A-Za-z0-9._-]*\\.apk",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern OBB = Pattern.compile(
            "Android/obb/([A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+)/([A-Za-z0-9_][A-Za-z0-9._-]*)");
    // Split names that hold native code for one CPU type (config.arm64_v8a, …).
    private static final Pattern ABI_SPLIT = Pattern.compile(
            "config\\.(arm64_v8a|armeabi_v7a|armeabi|x86_64|x86|mips64|mips)");
    private static final long MAX_MANIFEST = 1 << 20;

    /** True if the file is a zip of APKs (an XAPK) rather than an APK itself. */
    static boolean isBundle(File f) {
        try (ZipFile z = new ZipFile(f)) {
            if (z.getEntry("AndroidManifest.xml") != null) {
                return false; // an APK
            }
            Enumeration<? extends ZipEntry> e = z.entries();
            while (e.hasMoreElements()) {
                if (APK.matcher(e.nextElement().getName()).matches()) {
                    return true;
                }
            }
        } catch (IOException | RuntimeException e) {
            // not a zip at all: let the APK parser report it
        }
        return false;
    }

    /**
     * Unpacks what the phone needs into {@code dir} (emptied first): the base APK, the splits
     * except those for CPU types the phone can't run, and the expansion files.
     */
    static Xapk extract(File src, File dir) throws IOException {
        deleteTree(dir);
        if (!dir.mkdirs()) {
            throw new IOException("cannot create " + dir);
        }
        try (ZipFile z = new ZipFile(src)) {
            JSONObject manifest = readManifest(z);
            String pkg = manifest != null ? manifest.optString("package_name", null) : null;

            // The APKs at the top of the zip, with the split id manifest.json gives each.
            List<String> names = new ArrayList<>();
            Map<String, String> ids = new HashMap<>();
            List<ZipEntry> obbEntries = new ArrayList<>();
            Enumeration<? extends ZipEntry> e = z.entries();
            while (e.hasMoreElements()) {
                ZipEntry en = e.nextElement();
                if (en.isDirectory()) {
                    continue;
                }
                if (APK.matcher(en.getName()).matches()) {
                    names.add(en.getName());
                } else if (OBB.matcher(en.getName()).matches()) {
                    obbEntries.add(en);
                }
            }
            if (names.isEmpty()) {
                throw new IOException("no APK inside");
            }
            JSONArray splits = manifest != null ? manifest.optJSONArray("split_apks") : null;
            String baseName = null;
            if (splits != null) {
                for (int i = 0; i < splits.length(); i++) {
                    JSONObject s = splits.optJSONObject(i);
                    if (s == null) {
                        continue;
                    }
                    String file = s.optString("file", "");
                    String id = s.optString("id", "");
                    ids.put(file, id);
                    if ("base".equals(id) && names.contains(file)) {
                        baseName = file;
                    }
                }
            }
            if (baseName == null) {
                baseName = pickBase(names, pkg);
            }
            if (baseName == null) {
                throw new IOException("can't tell which of its " + names.size()
                        + " APKs is the base (no manifest.json)");
            }

            // Keep the CPU-type splits this phone runs, when the bundle has one for it.
            List<String> want = new ArrayList<>();
            List<String> abiOther = new ArrayList<>();
            boolean abiMatch = false;
            List<String> abis = new ArrayList<>();
            for (String a : Build.SUPPORTED_ABIS) {
                abis.add(a.replace('-', '_'));
            }
            want.add(baseName);
            for (String n : names) {
                if (n.equals(baseName)) {
                    continue;
                }
                String id = ids.containsKey(n) ? ids.get(n) : n.substring(0, n.length() - 4);
                Matcher m = ABI_SPLIT.matcher(id);
                if (m.matches() && !abis.contains(m.group(1))) {
                    abiOther.add(n);
                    continue;
                }
                if (m.matches()) {
                    abiMatch = true;
                }
                want.add(n);
            }
            if (abiMatch) {
                // native code for this phone is there: the other CPU types aren't needed
            } else {
                want.addAll(abiOther); // none matches: install them all, Android decides
                abiOther.clear();
            }

            File baseFile = null;
            Xapk x = null;
            for (String n : want) {
                File out = new File(dir, n);
                unzip(z, z.getEntry(n), out);
                if (n.equals(baseName)) {
                    baseFile = out;
                    x = new Xapk(out, pkg);
                }
            }
            x.apks.add(baseFile);
            for (String n : want) {
                if (!n.equals(baseName)) {
                    x.apks.add(new File(dir, n));
                }
            }
            x.skipped.addAll(abiOther);

            File obbDir = new File(dir, "obb");
            for (ZipEntry en : obbEntries) {
                Matcher m = OBB.matcher(en.getName());
                m.matches();
                File out = new File(obbDir, m.group(1) + "-" + m.group(2));
                if (!obbDir.isDirectory() && !obbDir.mkdirs()) {
                    throw new IOException("cannot create " + obbDir);
                }
                unzip(z, en, out);
                x.obbs.add(new Obb(out, m.group(1), m.group(2)));
            }
            return x;
        }
    }

    /** Without manifest.json: base.apk, else <package>.apk, else the only APK there is. */
    private static String pickBase(List<String> names, String pkg) {
        for (String n : names) {
            if (n.equalsIgnoreCase("base.apk")) {
                return n;
            }
        }
        if (pkg != null && names.contains(pkg + ".apk")) {
            return pkg + ".apk";
        }
        return names.size() == 1 ? names.get(0) : null;
    }

    private static JSONObject readManifest(ZipFile z) throws IOException {
        ZipEntry en = z.getEntry("manifest.json");
        if (en == null) {
            return null;
        }
        try (InputStream in = z.getInputStream(en)) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                b.write(buf, 0, n);
                if (b.size() > MAX_MANIFEST) {
                    throw new IOException("manifest.json is too large");
                }
            }
            return new JSONObject(b.toString("UTF-8"));
        } catch (org.json.JSONException ex) {
            throw new IOException("manifest.json is not valid JSON");
        }
    }

    private static void unzip(ZipFile z, ZipEntry en, File out) throws IOException {
        try (InputStream in = z.getInputStream(en); OutputStream os = new FileOutputStream(out)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) {
                os.write(buf, 0, n);
            }
        }
    }

    static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) {
            for (File k : kids) {
                deleteTree(k);
            }
        }
        f.delete();
    }

    /** "3 APKs (base + config.arm64_v8a, config.en)" style summary for the log. */
    String describe() {
        StringBuilder b = new StringBuilder();
        b.append(apks.size() == 1 ? "1 APK" : apks.size() + " APKs (base + ");
        for (int i = 1; i < apks.size(); i++) {
            String n = apks.get(i).getName();
            b.append(i > 1 ? ", " : "").append(n.substring(0, n.length() - 4));
        }
        if (apks.size() > 1) {
            b.append(")");
        }
        if (!obbs.isEmpty()) {
            b.append(", ").append(obbs.size()).append(" OBB file").append(obbs.size() > 1 ? "s" : "");
        }
        if (!skipped.isEmpty()) {
            b.append("; skipped ").append(skipped.size()).append(" split")
                    .append(skipped.size() > 1 ? "s" : "").append(" for other CPU types");
        }
        return b.toString();
    }
}
