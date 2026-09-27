package com.igordanilcenko.speedtest.data

import com.igordanilcenko.speedtest.domain.DirectoryException
import com.igordanilcenko.speedtest.domain.DirectoryFailure
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class HttpServerDirectoryRepositoryTest {
    private val server = MockWebServer()
    private val client = OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).build()
    private lateinit var repository: HttpServerDirectoryRepository

    @Before fun setUp() {
        server.start()
        repository = HttpServerDirectoryRepository(Retrofit.Builder().baseUrl(server.url("/"))
            .client(client).addConverterFactory(GsonConverterFactory.create()).build()
            .create(ServerDirectoryApi::class.java))
    }
    @After fun tearDown() { server.shutdown() }

    @Test fun `maps real directory structure and does not send location`() = runBlocking {
        server.enqueue(MockResponse().setBody("""[{"url":"http://86.54.82.199:4780","latitude":50.08,"longitude":14.46,"city":"Prague","country":"Czechia","speedMbps":1000}]"""))
        val node = repository.getNodes().single()
        assertEquals("86.54.82.199", node.host)
        assertEquals(4780, node.port)
        assertEquals("Prague, Czechia", node.name)
        assertEquals(50.08, node.coordinates.latitude, 0.0)
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/api/v2/servers", request.path)
        assertEquals(0L, request.bodySize)
    }

    @Test fun `skips incomplete or invalid entries and removes duplicates`() = runBlocking {
        server.enqueue(MockResponse().setBody("""[
            null, {}, {"url":"not a URL","latitude":0,"longitude":0},
            {"url":"http://example.test","latitude":91,"longitude":0},
            {"url":"http://example.test","latitude":0,"longitude":181},
            {"url":"http://example.test","latitude":0},
            {"url":"http://example.test:80","latitude":0,"longitude":0},
            {"url":"http://example.test:80","latitude":0,"longitude":0},
            {"url":"http://example.test:81","latitude":0,"longitude":0}
        ]"""))
        val nodes = repository.getNodes()
        assertEquals(2, nodes.size)
        assertEquals("example.test", nodes.first().name)
        assertEquals(listOf(80, 81), nodes.map { it.port })
    }

    @Test fun `maps http failures`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503))
        try {
            repository.getNodes()
            fail("Expected a directory error")
        } catch (error: DirectoryException) {
            assertEquals(DirectoryFailure.Unavailable, error.failure)
        }
    }

    @Test fun `maps invalid json response`() = runBlocking {
        server.enqueue(MockResponse().setBody("{\"unexpected\":true}"))
        try {
            repository.getNodes()
            fail("Expected a directory error")
        } catch (error: DirectoryException) {
            assertEquals(DirectoryFailure.InvalidResponse, error.failure)
        }
    }

    @Test fun `cancellation propagates instead of becoming a network error`() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val request = async { repository.getNodes() }
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            assertNotNull(server.takeRequest(3, TimeUnit.SECONDS))
        }
        request.cancelAndJoin()
        assertTrue(request.isCancelled)
    }
}
