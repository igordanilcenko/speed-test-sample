package com.igordanilcenko.speedtest.data

import com.igordanilcenko.speedtest.domain.DownloadException
import com.igordanilcenko.speedtest.domain.DownloadFailure
import com.igordanilcenko.speedtest.domain.model.Coordinates
import com.igordanilcenko.speedtest.domain.model.DownloadUpdate
import com.igordanilcenko.speedtest.domain.model.Node
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class HttpDownloadSpeedServiceTest {
    private val server = MockWebServer()
    private val requests = CopyOnWriteArrayList<RecordedRequest>()
    private val logs = CopyOnWriteArrayList<String>()
    private val active = AtomicInteger()
    private val maxActive = AtomicInteger()
    private val cancelled = AtomicInteger()
    private val client = OkHttpClient.Builder().eventListener(object : EventListener() {
        override fun callStart(call: Call) {
            if (call.request().url.encodedPath == "/download") {
                maxActive.accumulateAndGet(active.incrementAndGet(), ::maxOf)
            }
        }

        override fun callEnd(call: Call) {
            ended(call)
        }

        override fun callFailed(call: Call, ioe: java.io.IOException) {
            ended(call)
        }

        override fun canceled(call: Call) {
            cancelled.incrementAndGet()
        }

        private fun ended(call: Call) {
            if (call.request().url.encodedPath == "/download") active.decrementAndGet()
        }
    }).build()
    private var tokenResponse = MockResponse().setBody("""{"token":"test token","ttl":80}""")
    private var helloResponse = MockResponse().setBody("""{"pong":true,"version":"1.4.2"}""")
    private var downloadResponse: () -> MockResponse = {
        MockResponse().setBody("x".repeat(1024)).throttleBody(256, 20, TimeUnit.MILLISECONDS)
    }
    private lateinit var node: Node
    private lateinit var service: HttpDownloadSpeedService

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests.add(request)
                return when (request.requestUrl?.encodedPath) {
                    "/api/v1/tokens" -> tokenResponse
                    "/hello" -> helloResponse
                    "/download" -> downloadResponse()
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        node = Node("test", "Test", server.hostName, server.port, Coordinates(0.0, 0.0))
        service = HttpDownloadSpeedService(client, server.url("/api/v1/tokens"), 1024, logs::add)
    }

    @After
    fun tearDown() {
        server.shutdown()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }

    @Test
    fun `four workers repeat requests and report current and final speed`() = runBlocking {
        val updates = withTimeout(5_000) { service.measure(node, 1200.milliseconds).toList() }
        val downloads = requests.filter { it.requestUrl?.encodedPath == "/download" }
        assertEquals("POST", requests.first().method)
        assertEquals(0L, requests.first().bodySize)
        assertEquals("/hello", requests[1].requestUrl?.encodedPath)
        assertTrue(downloads.size > 4)
        assertEquals(4, maxActive.get())
        assertEquals(downloads.size, downloads.map { it.requestUrl?.queryParameter("nc") }.toSet().size)
        downloads.forEach {
            assertEquals(server.port, it.requestUrl?.port)
            assertEquals("1024", it.requestUrl?.queryParameter("size"))
            assertEquals("test token", it.requestUrl?.queryParameter("token"))
            assertEquals("identity", it.getHeader("Accept-Encoding"))
        }
        assertTrue(updates.filterIsInstance<DownloadUpdate.Progress>().any { it.measurement.currentMbps > 0 })
        val result = updates.last() as DownloadUpdate.Finished
        assertEquals(1200L, result.measurement.elapsedMillis)
        assertTrue(result.measurement.totalBytes > 4096)
        assertEquals(result.measurement.totalBytes * 8.0 / 1.2 / 1_000_000, result.measurement.averageMbps, 0.000001)
        assertTrue(logs.any { it.contains("Token received: ttl=80s") })
        assertTrue(logs.any { it.contains("Server validated") })
        assertTrue(logs.any { it.contains("Progress:") && it.contains("current=") })
        assertTrue(logs.any { it.contains("Finished:") && it.contains("average=") })
        assertFalse(logs.any { it.contains("test token") || it.contains("token=") })
        assertEquals(0, client.dispatcher.runningCallsCount())
    }

    @Test
    fun `token HTTP failure is explicit`() = runBlocking {
        tokenResponse = MockResponse().setResponseCode(500)
        assertFailure(DownloadFailure.TokenRequest)
        assertEquals(1, requests.size)
    }

    @Test
    fun `invalid hello stops before downloading`() = runBlocking {
        helloResponse = MockResponse().setBody("""{"pong":"true"}""")
        assertFailure(DownloadFailure.InvalidHello)
        assertEquals(2, requests.size)
    }

    @Test
    fun `unauthorized token request is explicit`() = runBlocking {
        tokenResponse = MockResponse().setResponseCode(401)
        assertFailure(DownloadFailure.Unauthorized)
    }

    @Test
    fun `forbidden download fails all workers`() = runBlocking {
        downloadResponse = { MockResponse().setResponseCode(403) }
        assertFailure(DownloadFailure.Unauthorized)
        assertEquals(0, client.dispatcher.runningCallsCount())
    }

    @Test
    fun `early EOF is not a successful measurement`() = runBlocking {
        downloadResponse = { MockResponse().setBody("short") }
        assertFailure(DownloadFailure.TruncatedResponse)
    }

    @Test
    fun `broken stream is detected`() = runBlocking {
        downloadResponse = {
            MockResponse().setBody("x".repeat(1024)).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
        }
        assertFailure(DownloadFailure.TruncatedResponse)
    }

    @Test
    fun `connection loss is reported without a final result`() = runBlocking {
        downloadResponse = { MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST) }
        assertFailure(DownloadFailure.Connection)
        assertEquals(0, client.dispatcher.runningCallsCount())
    }

    @Test
    fun `token must last longer than the measurement`() = runBlocking {
        tokenResponse = MockResponse().setBody("""{"token":"test token","ttl":1}""")
        assertFailure(DownloadFailure.TokenRequest)
        assertEquals(2, requests.size)
    }

    @Test
    fun `redirect is not followed or given token`() = runBlocking {
        helloResponse = MockResponse().setResponseCode(302).setHeader("Location", server.url("/other"))
        assertFailure(DownloadFailure.InvalidHello)
        assertEquals(2, requests.size)
    }

    @Test
    fun `cancellation stops all active response reads without a final result`() = runBlocking {
        downloadResponse = {
            MockResponse().setBody("x".repeat(1024)).setBodyDelay(2, TimeUnit.SECONDS)
        }
        val updates = mutableListOf<DownloadUpdate>()
        val job = launch { service.measure(node, 15.seconds).toList(updates) }
        withTimeout(5_000) { while (requests.count { it.requestUrl?.encodedPath == "/download" } < 4) delay(10) }
        withTimeout(2_000) { job.cancelAndJoin() }
        assertEquals(0, client.dispatcher.runningCallsCount())
        assertEquals(4, cancelled.get())
        assertTrue(updates.none { it is DownloadUpdate.Finished })
        assertTrue(logs.any { it.contains("Cancelled; active requests stopped") })
    }

    @Test
    fun `cancellation interrupts token request too`() = runBlocking {
        tokenResponse = MockResponse().setHeadersDelay(2, TimeUnit.SECONDS)
        val job = launch { service.measure(node, 15.seconds).collect() }
        withTimeout(5_000) { while (requests.isEmpty()) delay(10) }
        withTimeout(2_000) { job.cancelAndJoin() }
        assertEquals(0, client.dispatcher.runningCallsCount())
        assertEquals(1, cancelled.get())
    }

    @Test
    fun `deadline cancels stalled workers and emits exactly one result`() = runBlocking {
        downloadResponse = { MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE) }
        val updates = withTimeout(3_000) { service.measure(node, 400.milliseconds).toList() }
        assertEquals(1, updates.filterIsInstance<DownloadUpdate.Finished>().size)
        assertEquals(400L, updates.last().measurement.elapsedMillis)
        assertEquals(0, client.dispatcher.runningCallsCount())
        assertEquals(4, cancelled.get())
    }

    @Test
    fun `network timeout fails instead of publishing a result`() = runBlocking {
        tokenResponse = MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
        assertFailure(DownloadFailure.Timeout)
    }

    private suspend fun assertFailure(expected: DownloadFailure) {
        try {
            withTimeout(8_000) { service.measure(node, 15.seconds).collect() }
            fail("Expected download failure")
        } catch (error: DownloadException) {
            assertEquals(expected, error.failure)
        }
    }
}
