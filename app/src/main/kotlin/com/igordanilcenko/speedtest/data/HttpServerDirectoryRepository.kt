package com.igordanilcenko.speedtest.data

import com.google.gson.JsonParseException
import com.igordanilcenko.speedtest.domain.DirectoryException
import com.igordanilcenko.speedtest.domain.DirectoryFailure
import com.igordanilcenko.speedtest.domain.ServerDirectoryRepository
import com.igordanilcenko.speedtest.domain.model.Coordinates
import com.igordanilcenko.speedtest.domain.model.Node
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class HttpServerDirectoryRepository(private val api: ServerDirectoryApi) : ServerDirectoryRepository {
    override suspend fun getNodes(): List<Node> = withContext(Dispatchers.IO) {
        val servers = try {
            api.getServers()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: JsonParseException) {
            throw DirectoryException(DirectoryFailure.InvalidResponse)
        } catch (_: IllegalStateException) {
            throw DirectoryException(DirectoryFailure.InvalidResponse)
        } catch (_: HttpException) {
            throw DirectoryException(DirectoryFailure.Unavailable)
        } catch (_: IOException) {
            throw DirectoryException(DirectoryFailure.Unavailable)
        }
        servers.mapNotNull { it?.toNode() }.distinctBy { it.id }
    }

    companion object {
        fun create(): HttpServerDirectoryRepository {
            val client = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .callTimeout(20, TimeUnit.SECONDS)
                .build()
            val api = Retrofit.Builder()
                .baseUrl("https://sp-dir.uwn.com/")
                .client(client)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(ServerDirectoryApi::class.java)
            return HttpServerDirectoryRepository(api)
        }
    }
}

private fun ServerDto.toNode(): Node? {
    val endpoint = url?.toHttpUrlOrNull() ?: return null
    if (endpoint.username.isNotEmpty() || endpoint.password.isNotEmpty()) return null
    val lat = latitude?.takeIf { it.isFinite() && it in -90.0..90.0 } ?: return null
    val lon = longitude?.takeIf { it.isFinite() && it in -180.0..180.0 } ?: return null
    val name = listOfNotNull(city, country).map { it.trim() }.filter { it.isNotEmpty() }
        .joinToString(", ").ifEmpty { endpoint.host }
    return Node(endpoint.toString(), name, endpoint.host, endpoint.port, Coordinates(lat, lon))
}
