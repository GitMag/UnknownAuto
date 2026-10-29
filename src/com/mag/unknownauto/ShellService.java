package com.mag.unknownauto;

import android.content.Context;

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
    public int exec(String[] cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            InputStream in = p.getInputStream();
            byte[] buf = new byte[4096];
            while (in.read(buf) >= 0) {
                // drain so the child can exit
            }
            return p.waitFor();
        } catch (Throwable t) {
            return -1;
        }
    }
}
