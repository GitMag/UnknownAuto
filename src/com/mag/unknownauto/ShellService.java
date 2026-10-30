package com.mag.unknownauto;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

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

    private static final int MAX_OUTPUT = 256 * 1024;
}
