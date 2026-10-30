// A tiny service that Shizuku instantiates in the shell (uid 2000) process, so we can
// run the package-installer launch as the shell user — no computer / adb needed.
package com.mag.unknownauto;

interface IShellService {
    // Shizuku convention: destroy() must use this transaction id so unbind can stop it.
    void destroy() = 16777114;
    // Run a command (argv) as the shell user; returns its output (stdout + stderr),
    // with "[exit N]" appended when it fails, or null if it could not start.
    String run(in String[] cmd) = 1;
}
