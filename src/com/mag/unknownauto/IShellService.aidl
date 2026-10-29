// A tiny service that Shizuku instantiates in the shell (uid 2000) process, so we can
// run the package-installer launch as the shell user — no computer / adb needed.
package com.mag.unknownauto;

interface IShellService {
    // Shizuku convention: destroy() must use this transaction id so unbind can stop it.
    void destroy() = 16777114;
    // Run a command (argv) and return its exit code. Runs as the shell user.
    int exec(in String[] cmd) = 1;
}
