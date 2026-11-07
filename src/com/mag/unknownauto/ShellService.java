package com.mag.unknownauto;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * Runs in the Shizuku-spawned process, which has the <b>shell</b> user's identity
 * (uid 2000) — the same identity {@code adb} has. That is what lets the package
 * installer we launch from here honour {@code installer = com.android.vending} and
 * record itself as the initiator. Bound via {@code Shizuku.bindUserService}.
 */
public final class ShellService extends IShellService.Stub {

    // Shizuku tries (Context) first, then the no-arg constructor.
    public ShellService() {
    }

    public ShellService(Context context) {
    }

    @Override
    public void destroy() {
        System.exit(0);
    }

    @Override
    public String run(String[] cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            InputStream in = p.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) >= 0) {
                // drain so the child can exit; keep at most 256 kB (a binder reply is small)
                if (out.size() < MAX_OUTPUT) {
                    out.write(buf, 0, Math.min(n, MAX_OUTPUT - out.size()));
                }
            }
            int code = p.waitFor();
            String s = out.toString("UTF-8");
            return code == 0 ? s : s + "\n[exit " + code + "]";
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Starts {@code intent} as the shell user, with {@code resultTo} (an activity of the
     * app) as its result target. The started activity then sees the app as its calling
     * package and the shell as its launcher — what {@code am start} can't do, as it waits
     * for no result. Hidden API: this process is not an app, so no restrictions apply.
     */
    @Override
    public String startForResult(Intent intent, IBinder resultTo, int requestCode) {
        try {
            Object atm = Build.VERSION.SDK_INT >= 29
                    ? Class.forName("android.app.ActivityTaskManager").getMethod("getService")
                            .invoke(null)
                    : ActivityManager.class.getMethod("getService").invoke(null);
            String type = intent.getType();
            for (Method m : atm.getClass().getMethods()) {
                if (!m.getName().equals("startActivity")) {
                    continue;
                }
                Class<?>[] p = m.getParameterTypes();
                Object[] args;
                if (p.length == 11 && p[3] == Intent.class) {
                    // API 30+: (caller, callingPackage, callingFeatureId, intent, resolvedType,
                    //           resultTo, resultWho, requestCode, flags, profilerInfo, options)
                    args = new Object[]{null, SHELL, null, intent, type, resultTo, null,
                            requestCode, 0, null, null};
                } else if (p.length == 10 && p[2] == Intent.class) {
                    // API 26-29: the same without callingFeatureId
                    args = new Object[]{null, SHELL, intent, type, resultTo, null,
                            requestCode, 0, null, null};
                } else {
                    continue;
                }
                int res = (Integer) m.invoke(atm, args);
                return res >= 0 ? "ok" : "startActivity returned " + res;
            }
            return "no usable startActivity on " + atm.getClass().getName();
        } catch (InvocationTargetException e) {
            return String.valueOf(e.getCause());
        } catch (Throwable t) {
            return String.valueOf(t);
        }
    }

    /**
     * Writes an expansion file into {@code /storage/emulated/<user>/Android/obb/<package>/},
     * which the shell may write and an app may not. Through a ".part" file and a rename, so
     * a game never sees half a file. Refuses any other destination.
     */
    @Override
    public String copyIn(ParcelFileDescriptor src, String dest) {
        File out = new File(dest);
        File dir = out.getParentFile();
        String obbRoot = dir != null && dir.getParentFile() != null
                ? dir.getParentFile().getPath() : "";
        if (!OBB_ROOT.matcher(obbRoot).matches() || dest.contains("/../")
                || out.getName().startsWith(".")) {
            closeQuietly(src);
            return "refused: " + dest;
        }
        File part = new File(dir, out.getName() + ".part");
        try (InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(src)) {
            if (!dir.isDirectory() && !dir.mkdirs()) {
                return "cannot create " + dir;
            }
            try (OutputStream os = new FileOutputStream(part)) {
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = in.read(buf)) > 0) {
                    os.write(buf, 0, n);
                }
            }
            if (out.exists() && !out.delete()) {
                part.delete();
                return "cannot replace " + out;
            }
            return part.renameTo(out) ? "ok" : "cannot rename " + part;
        } catch (Throwable t) {
            part.delete();
            return String.valueOf(t);
        }
    }

    private static void closeQuietly(ParcelFileDescriptor p) {
        try {
            p.close();
        } catch (Throwable ignore) {
            // nothing to do
        }
    }

    private static final java.util.regex.Pattern OBB_ROOT =
            java.util.regex.Pattern.compile("/storage/emulated/[0-9]+/Android/obb");
    private static final String SHELL = "com.android.shell";
    private static final int MAX_OUTPUT = 256 * 1024;
}
