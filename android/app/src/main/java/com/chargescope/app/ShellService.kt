package com.chargescope.app

import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/** Instantiated by Shizuku inside the shell process. Keep it dependency-free. */
class ShellService : IShellService.Stub() {
    override fun destroy() {
        exitProcess(0)
    }

    override fun run(script: String): String {
        val p = ProcessBuilder("sh").redirectErrorStream(true).start()
        val out = StringBuffer()
        val reader = thread(isDaemon = true) {
            try {
                p.inputStream.bufferedReader().use { r ->
                    val buf = CharArray(4096)
                    while (true) {
                        val n = r.read(buf)
                        if (n < 0) break
                        out.append(buf, 0, n)
                    }
                }
            } catch (_: Exception) {
            }
        }
        p.outputStream.bufferedWriter().use {
            it.write(script)
            it.write("\nexit\n")
        }
        if (!p.waitFor(5, TimeUnit.SECONDS)) p.destroyForcibly()
        reader.join(1000)
        return out.toString()
    }
}
