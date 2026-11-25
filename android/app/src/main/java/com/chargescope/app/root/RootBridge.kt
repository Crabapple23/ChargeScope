package com.chargescope.app.root

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * Read-only Root bridge. Hard rules:
 *  - only `su` is invoked, with a fixed, whitelisted command template
 *  - no user-supplied string is ever concatenated into a command
 *  - no writes to sysfs, no kernel parameter changes, no charging control
 *  - bounded timeout and output length on every call
 */
class RootBridge(
    private val commandTimeoutMs: Long = 2_000,
    private val maxOutputChars: Int = 64_000,
) {

    data class CommandResult(
        val ok: Boolean,
        val stdout: String,
        val stderr: String,
        val exitCode: Int?,
        val error: String?,
    )

    /** Non-blocking availability probe. Does not modify the system. */
    suspend fun probe(): Boolean = withContext(Dispatchers.IO) {
        runWhitelisted(WHITELIST.PROBE).ok
    }

    /** List /sys/class/power_supply entries. Path is fixed by whitelist. */
    suspend fun listPowerSupply(): CommandResult = withContext(Dispatchers.IO) {
        runWhitelisted(WHITELIST.LIST_POWER_SUPPLY)
    }

    /**
     * Read a discovered file. `path` must be an absolute path that passes the
     * strict validator; nothing else is accepted.
     */
    suspend fun readFile(path: String): CommandResult = withContext(Dispatchers.IO) {
        if (!isSafeSysfsPath(path)) {
            CommandResult(false, "", "", null, "路径未通过白名单校验: $path")
        } else {
            runWhitelisted(listOf("su", "-c", "cat $path"))
        }
    }

    /** Read a system property with a fixed prefix; key is strictly validated. */
    suspend fun readProperty(key: String): CommandResult = withContext(Dispatchers.IO) {
        if (!key.matches(Regex("[a-zA-Z0-9._-]{1,80}"))) {
            CommandResult(false, "", "", null, "属性名非法: $key")
        } else {
            runWhitelisted(listOf("su", "-c", "getprop $key"))
        }
    }

    private fun runWhitelisted(command: List<String>): CommandResult {
        return try {
            val process = ProcessBuilder(command)
                .redirectErrorStream(false)
                .start()
            val stdout = readBounded(process.inputStream, maxOutputChars)
            val stderr = readBounded(process.errorStream, maxOutputChars)
            val finished = process.waitFor(commandTimeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
                return CommandResult(false, stdout, stderr, null, "命令超时 ${commandTimeoutMs}ms")
            }
            CommandResult(
                ok = process.exitValue() == 0,
                stdout = stdout,
                stderr = stderr,
                exitCode = process.exitValue(),
                error = null,
            )
        } catch (t: Throwable) {
            CommandResult(false, "", "", null, "执行失败: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun readBounded(stream: java.io.InputStream, limit: Int): String {
        val reader = BufferedReader(InputStreamReader(stream, Charsets.UTF_8))
        val sb = StringBuilder()
        var ch: Int
        while (reader.read().also { ch = it } != -1 && sb.length < limit) {
            sb.append(ch.toChar())
        }
        return sb.toString()
    }

    companion object {
        private object WHITELIST {
            val PROBE = listOf("su", "-c", "id")
            val LIST_POWER_SUPPLY = listOf("su", "-c", "ls -1 /sys/class/power_supply")
        }

        /**
         * Only paths under /sys/class/power_supply/ or /sys/class/typec/ with a
         * conservative character set. No spaces, no shell metacharacters,
         * no traversal.
         */
        fun isSafeSysfsPath(path: String): Boolean {
            if (path.length !in 1..180) return false
            if (!path.startsWith("/sys/class/power_supply/") &&
                !path.startsWith("/sys/class/typec/")
            ) return false
            if (path.contains("..")) return false
            return path.matches(Regex("^/sys/class/(power_supply|typec)/[A-Za-z0-9._/@-]+$"))
        }
    }
}
