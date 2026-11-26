package com.chargescope.app;

// Runs inside the Shizuku "shell" process (uid 2000). Only executes the fixed read-only script.
interface IShellService {
    void destroy() = 16777114;
    String run(String script) = 1;
}
