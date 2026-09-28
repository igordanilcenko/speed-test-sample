package com.igordanilcenko.speedtest.data

import com.igordanilcenko.speedtest.domain.PingService
import com.igordanilcenko.speedtest.domain.model.PingResult
import java.io.IOException
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class AndroidPingService(
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val startProcess: (List<String>) -> Process = { command ->
        ProcessBuilder(command).redirectErrorStream(true).apply {
            environment()["LC_ALL"] = "C"
            environment()["LANG"] = "C"
        }.start()
    },
) : PingService {
    override suspend fun ping(host: String): PingResult {
        if (host.length !in 1..253 || host.startsWith('-') || !host.matches(Regex("[A-Za-z0-9:.%_-]+"))) {
            return PingResult.Unavailable
        }
        if (':' in host) return probe("/system/bin/ping6", host)
        val result = probe("/system/bin/ping", host)
        if (result is PingResult.Success || host.matches(Regex("[0-9.]+"))) return result
        val ipv6 = probe("/system/bin/ping6", host)
        return if (ipv6 is PingResult.Success || result == PingResult.Unavailable) ipv6 else result
    }

    private suspend fun probe(binary: String, host: String): PingResult =
        withTimeoutOrNull(5_000) {
            withContext(dispatcher) {
                currentCoroutineContext().ensureActive()
                suspendCancellableCoroutine { continuation ->
                    var process: Process? = null
                    try {
                        val running = startProcess(listOf(binary, "-n", "-c", "3", "-W", "1", "-w", "4", host))
                        process = running
                        continuation.invokeOnCancellation { running.destroy() }
                        if (continuation.isActive) {
                            val output = running.inputStream.bufferedReader().use { it.readText() }
                            val exitCode = running.waitFor()
                            val latency = parseAveragePingMs(output)
                            continuation.resume(when {
                                exitCode in 0..1 && latency != null -> PingResult.Success(latency)
                                exitCode == 1 -> PingResult.NoReply
                                else -> PingResult.Unavailable
                            })
                        }
                    } catch (_: IOException) {
                        continuation.resume(PingResult.Unavailable)
                    } catch (_: SecurityException) {
                        continuation.resume(PingResult.Unavailable)
                    } finally {
                        process?.destroy()
                        process?.let {
                            runCatching { it.inputStream.close() }
                            runCatching { it.errorStream.close() }
                            runCatching { it.outputStream.close() }
                        }
                    }
                }
            }
        } ?: PingResult.NoReply
}

internal fun parseAveragePingMs(output: String): Double? =
    Regex("""(?m)^(?:rtt|round-trip)\s+min/avg/max(?:/\w+)?\s*=\s*[\d.]+/([\d.]+)/[\d.]+(?:/[\d.]+)?\s+ms\s*$""")
        .find(output)?.groupValues?.get(1)?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }
