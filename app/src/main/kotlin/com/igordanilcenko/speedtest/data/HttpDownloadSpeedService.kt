package com.igordanilcenko.speedtest.data

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.igordanilcenko.speedtest.domain.DownloadException
import com.igordanilcenko.speedtest.domain.DownloadFailure
import com.igordanilcenko.speedtest.domain.DownloadSpeedService
import com.igordanilcenko.speedtest.domain.model.DownloadUpdate
import com.igordanilcenko.speedtest.domain.model.Node
import com.igordanilcenko.speedtest.domain.model.SpeedMeasurement
import com.igordanilcenko.speedtest.domain.model.calculateMbps
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.EOFException
import java.io.InterruptedIOException
import java.net.ProtocolException
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration

class HttpDownloadSpeedService internal constructor(
    client: OkHttpClient = OkHttpClient(),
    private val tokenUrl: HttpUrl = "https://sp-dir.uwn.com/api/v1/tokens".toHttpUrl(),
    private val chunkBytes: Long = 50_000_000,
    private val log: (String) -> Unit = {},
) : DownloadSpeedService {
    private val client = client.newBuilder()
        .cache(null)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.SECONDS)
        .build()

    override fun measure(server: Node, duration: Duration): Flow<DownloadUpdate> = flow {
        require(duration.isFinite() && duration.inWholeMilliseconds > 0)
        require(chunkBytes > 0)
        val origin = HttpUrl.Builder().scheme("http").host(server.host).port(server.port).build()
        if (origin.host == "sp-dir.uwn.com" || origin.host.endsWith(".sp-dir.uwn.com")) {
            throw DownloadException(DownloadFailure.InvalidHello)
        }
        val tokenStarted = System.nanoTime()
        log("[Download] Requesting temporary client token")
        val tokenResponse = requestJson(
            Request.Builder().url(tokenUrl).post(ByteArray(0).toRequestBody()).build(),
            DownloadFailure.TokenRequest,
        )
        val token = tokenResponse.get("token")?.takeIf {
            it.isJsonPrimitive && it.asJsonPrimitive.isString
        }?.asString?.takeIf { it.isNotBlank() && it.length <= 4096 }
            ?: throw DownloadException(DownloadFailure.TokenRequest)
        val ttl = tokenResponse.get("ttl")?.toString()?.toLongOrNull()?.takeIf { it in 1..86_400 }
            ?: throw DownloadException(DownloadFailure.TokenRequest)
        log("[Download] Token received: ttl=${ttl}s; validating ${origin.host}:${origin.port}")
        val hello = requestJson(
            Request.Builder().url(
                origin.newBuilder().addPathSegment("hello")
                    .addQueryParameter("token", token).build()
            ).build(),
            DownloadFailure.InvalidHello,
        )
        if (hello.get("pong")?.let {
                it.isJsonPrimitive && it.asJsonPrimitive.isBoolean && it.asBoolean
            } != true
        ) throw DownloadException(DownloadFailure.InvalidHello)
        if (ttl * 1_000_000_000 - (System.nanoTime() - tokenStarted) <= duration.inWholeNanoseconds) {
            throw DownloadException(DownloadFailure.TokenRequest)
        }

        log("[Download] Server validated; starting ${duration.inWholeMilliseconds}ms window, 4 workers, $chunkBytes bytes/request")
        val counter = DownloadCounter(System.nanoTime(), duration.inWholeNanoseconds)
        var previous = DownloadSample(0, 0)
        emit(DownloadUpdate.Progress(previous.measurement(previous)))
        withTimeoutOrNull(duration.inWholeMilliseconds) {
            coroutineScope {
                repeat(4) { worker ->
                    launch {
                        val buffer = ByteArray(64 * 1024)
                        var requestNumber = 0
                        while (isActive && !counter.expired()) {
                            val url = origin.newBuilder().addPathSegment("download")
                                .addQueryParameter("size", chunkBytes.toString())
                                .addQueryParameter("nc", UUID.randomUUID().toString())
                                .addQueryParameter("token", token).build()
                            val request = Request.Builder().url(url)
                                .header("Accept-Encoding", "identity")
                                .header("Cache-Control", "no-cache, no-store").build()
                            requestNumber++
                            log("[Download] Worker ${worker + 1}: request $requestNumber started")
                            val received = execute(request, DownloadFailure.Connection) { response ->
                                val body = response.body
                                if (response.header("Content-Encoding")?.let { !it.equals("identity", true) } == true) {
                                    throw DownloadException(DownloadFailure.InvalidResponse)
                                }
                                if (body.contentLength() != -1L && body.contentLength() != chunkBytes) {
                                    throw DownloadException(DownloadFailure.TruncatedResponse)
                                }
                                var received = 0L
                                val stream = body.byteStream()
                                while (!counter.expired()) {
                                    val count = stream.read(buffer)
                                    if (count == -1) {
                                        if (received != chunkBytes) throw DownloadException(DownloadFailure.TruncatedResponse)
                                        break
                                    }
                                    received += count
                                    if (received > chunkBytes) throw DownloadException(DownloadFailure.InvalidResponse)
                                    counter.add(count)
                                }
                                received
                            }
                            log("[Download] Worker ${worker + 1}: request $requestNumber read $received bytes")
                        }
                    }
                }
                while (isActive) {
                    delay(500)
                    val sample = counter.snapshot()
                    val measurement = sample.measurement(previous)
                    log(
                        String.format(
                            Locale.US,
                            "[Download] Progress: elapsed=%dms, bytes=%d, current=%.2f Mbps, average=%.2f Mbps",
                            measurement.elapsedMillis,
                            measurement.totalBytes,
                            measurement.currentMbps,
                            measurement.averageMbps,
                        )
                    )
                    emit(DownloadUpdate.Progress(measurement))
                    previous = sample
                }
            }
        }
        currentCoroutineContext().ensureActive()
        val measurement = counter.snapshot().measurement(previous)
        log(
            String.format(
                Locale.US, "[Download] Finished: elapsed=%dms, bytes=%d, average=%.2f Mbps; workers stopped",
                measurement.elapsedMillis, measurement.totalBytes, measurement.averageMbps
            )
        )
        emit(DownloadUpdate.Finished(measurement))
    }.onCompletion { error ->
        when (error) {
            is CancellationException -> log("[Download] Cancelled; active requests stopped")
            is DownloadException -> log("[Download] Failed: ${error.failure}; active requests stopped")
            null -> Unit
            else -> log("[Download] Failed: ${error.javaClass.simpleName}; active requests stopped")
        }
    }.flowOn(Dispatchers.IO).buffer(Channel.CONFLATED)

    private suspend fun requestJson(request: Request, failure: DownloadFailure): JsonObject =
        withTimeoutOrNull(10_000) {
            execute(request, failure) { response ->
                val source = response.body.source()
                source.request(65_537)
                if (source.buffer.size > 65_536) throw DownloadException(failure)
                try {
                    JsonParser.parseString(source.readUtf8()).asJsonObject
                } catch (_: RuntimeException) {
                    throw DownloadException(failure)
                }
            }
        } ?: throw DownloadException(DownloadFailure.Timeout)

    private suspend fun <T> execute(
        request: Request,
        failure: DownloadFailure,
        read: (Response) -> T,
    ): T = withContext(Dispatchers.IO) {
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            if (continuation.isActive) {
                try {
                    val result = call.execute().use { response ->
                        log("[Download] ${request.method} ${request.url.encodedPath}: HTTP ${response.code}")
                        if (response.code == 401 || response.code == 403) {
                            throw DownloadException(DownloadFailure.Unauthorized)
                        }
                        if (response.code != 200) throw DownloadException(failure)
                        read(response)
                    }
                    continuation.resume(result)
                } catch (error: Exception) {
                    continuation.resumeWithException(
                        when (error) {
                            is DownloadException -> error
                            is InterruptedIOException -> DownloadException(DownloadFailure.Timeout)
                            is EOFException, is ProtocolException -> DownloadException(DownloadFailure.TruncatedResponse)
                            else -> DownloadException(failure)
                        }
                    )
                }
            }
        }
    }
}

internal data class DownloadSample(val bytes: Long, val elapsedNanos: Long) {
    fun measurement(previous: DownloadSample) = SpeedMeasurement(
        currentMbps = calculateMbps(bytes - previous.bytes, elapsedNanos - previous.elapsedNanos),
        totalBytes = bytes,
        elapsedMillis = elapsedNanos / 1_000_000,
        elapsedNanos = elapsedNanos,
    )
}

private class DownloadCounter(private val started: Long, private val durationNanos: Long) {
    private var bytes = 0L

    fun expired() = System.nanoTime() - started >= durationNanos

    @Synchronized
    fun add(count: Int) {
        if (!expired()) bytes += count
    }

    @Synchronized
    fun snapshot() = DownloadSample(bytes, (System.nanoTime() - started).coerceIn(0, durationNanos))
}
