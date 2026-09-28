package com.igordanilcenko.speedtest.data

import com.igordanilcenko.speedtest.domain.model.PingResult
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class AndroidPingServiceTest {
    private val summary = "rtt min/avg/max/mdev = 10.100/12.345/14.900/1.000 ms\n"

    @Test fun `parser uses summary average not first packet or wall clock`() {
        assertEquals(12.345, parseAveragePingMs("64 bytes: time=10.1 ms\n$summary")!!, 0.0)
        assertEquals(0.03, parseAveragePingMs("round-trip min/avg/max = 0.01/0.03/0.05 ms")!!, 0.0)
        assertNull(parseAveragePingMs("100% packet loss"))
        assertNull(parseAveragePingMs("time<1 ms"))
        assertNull(parseAveragePingMs("rtt min/avg/max = 1/NaN/2 ms"))
    }

    @Test fun `command pings host with bounded probe count and accepts partial replies`() = runBlocking {
        val commands = mutableListOf<List<String>>()
        val process = FakeProcess(summary, 1)
        val service = AndroidPingService(startProcess = { commands += it; process })
        assertEquals(PingResult.Success(12.345), service.ping("192.0.2.1"))
        assertEquals(listOf("/system/bin/ping", "-n", "-c", "3", "-W", "1", "-w", "4", "192.0.2.1"), commands.single())
        assertTrue(process.destroyed)
    }

    @Test fun `ipv6 literal uses ping6 and hostnames fall back to ipv6`() = runBlocking {
        val commands = mutableListOf<List<String>>()
        val service = AndroidPingService(startProcess = {
            commands += it
            if (it.first().endsWith("ping6")) FakeProcess(summary) else FakeProcess("unknown host", 2)
        })
        assertTrue(service.ping("2001:db8::1") is PingResult.Success)
        assertEquals("/system/bin/ping6", commands.single().first())
        commands.clear()
        assertTrue(service.ping("example.test") is PingResult.Success)
        assertEquals(listOf("/system/bin/ping", "/system/bin/ping6"), commands.map { it.first() })
    }

    @Test fun `missing binary malformed output and permission failure are unavailable`() = runBlocking {
        assertEquals(PingResult.Unavailable, AndroidPingService(startProcess = { throw IOException() }).ping("192.0.2.1"))
        assertEquals(PingResult.Unavailable, AndroidPingService(startProcess = { throw SecurityException() }).ping("192.0.2.1"))
        assertEquals(PingResult.Unavailable, AndroidPingService(startProcess = { FakeProcess("bad output") }).ping("192.0.2.1"))
        assertEquals(PingResult.NoReply, AndroidPingService(startProcess = { FakeProcess("100% packet loss", 1) }).ping("192.0.2.1"))
    }

    @Test fun `invalid host cannot inject process options`() = runBlocking {
        val service = AndroidPingService(startProcess = { error("Must not start") })
        for (host in listOf("-f", "host;echo test", "host\n-f", "", "https://host")) {
            assertEquals(PingResult.Unavailable, service.ping(host))
        }
    }

    @Test fun `cancellation destroys process while stdout read is blocked`() = runBlocking {
        val process = BlockingProcess()
        val service = AndroidPingService(startProcess = { process })
        withTimeout(3_000) {
            val job = async { service.ping("192.0.2.1") }
            process.readStarted.await()
            job.cancelAndJoin()
        }
        assertTrue(process.destroyed)
    }

    @Test fun `deadline destroys stalled process and returns no reply`() = runBlocking {
        val process = BlockingProcess()
        val service = AndroidPingService(startProcess = { process })
        assertEquals(PingResult.NoReply, withTimeout(8_000) { service.ping("192.0.2.1") })
        assertTrue(process.destroyed)
    }

    private open class FakeProcess(output: String = "", private val code: Int = 0) : Process() {
        @Volatile var destroyed = false
        private val input = ByteArrayInputStream(output.toByteArray())
        override fun getInputStream(): InputStream = input
        override fun getErrorStream(): InputStream = ByteArrayInputStream(byteArrayOf())
        override fun getOutputStream() = ByteArrayOutputStream()
        override fun waitFor() = code
        override fun exitValue() = code
        override fun destroy() { destroyed = true }
    }

    private class BlockingProcess : FakeProcess() {
        val readStarted = CompletableDeferred<Unit>()
        private val released = CountDownLatch(1)
        override fun getInputStream() = object : InputStream() {
            override fun read(): Int {
                readStarted.complete(Unit)
                check(released.await(10, TimeUnit.SECONDS))
                return -1
            }
        }
        override fun destroy() {
            super.destroy()
            released.countDown()
        }
    }
}
