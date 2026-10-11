package com.okuyuki.quest.link

import com.okuyuki.protocol.*
import com.okuyuki.protocol.Protocol
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class QuestClient(val endpoint: Endpoint, private val cache: TemporaryPhotoCache) : AutoCloseable {
    private val http = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(40, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build()
    private val downloads = Mutex()
    var token: String? = null
    private var socket: WebSocket? = null
    private var heartbeat: Job? = null

    private fun request(path: String) = Request.Builder().url(endpoint.url + path).apply { token?.let { header("Authorization", "Bearer $it") } }
    private suspend fun execute(request: Request): Response = suspendCancellableCoroutine { continuation ->
        val call = http.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) { if (continuation.isActive) continuation.resumeWithException(error) }
            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response, onCancellation = { _, value, _ -> value.close() })
            }
        })
    }
    private suspend inline fun <reified T> json(request: Request): T = execute(request).use {
        check(it.isSuccessful) { if (it.code == 401) "スマホで接続を許可してください" else "スマホからの応答: ${it.code}" }
        val body = it.body ?: error("応答が空です")
        require(body.contentLength() <= 2 * 1024 * 1024) { "一覧が大きすぎます" }
        Protocol.json.decodeFromString(body.string().also { text -> require(text.length <= 2 * 1024 * 1024) })
    }
    suspend fun hello(): Hello = json<Hello>(request("/v1/hello").build()).also { require(it.protocolVersion == Protocol.VERSION) { "両アプリを同じバージョンに更新してください" } }
    suspend fun pair(input: PairRequest): PairResponse = json(request("/v1/pair").post(Protocol.json.encodeToString(input).toRequestBody("application/json".toMediaType())).build())
    suspend fun library(after: String? = null): LibraryPage {
        val path = after?.let { "/v1/library?after=${java.net.URLEncoder.encode(it, "UTF-8")}" } ?: "/v1/library"
        return json(request(path).build())
    }
    suspend fun thumbnail(id: String): ByteArray = execute(request("/v1/items/${safeId(id)}/thumb").build()).use {
        check(it.isSuccessful) { "サムネイルを取得できませんでした" }
        val body = it.body ?: error("画像が空です")
        require(body.contentLength() <= 2 * 1024 * 1024)
        body.byteStream().use { input ->
            val bytes = input.readNBytes(2 * 1024 * 1024 + 1)
            require(bytes.size <= 2 * 1024 * 1024); bytes
        }
    }
    suspend fun original(hostId: String, photo: Photo): File = downloads.withLock {
        withContext(Dispatchers.IO) {
            require(photo.sizeBytes in 1..minOf(Protocol.MAX_ORIGINAL_BYTES, cache.limitBytes)) { "写真が大きすぎます" }
            val key = "$hostId:${photo.id}:${photo.takenAt}:${photo.sizeBytes}"
            cache.get(key)?.takeIf { it.length() == photo.sizeBytes }?.let { return@withContext it }
            val partial = cache.partial(key)
            var offset = partial.length().takeIf { it < photo.sizeBytes } ?: 0L
            val request = request("/v1/items/${safeId(photo.id)}/original").apply { if (offset > 0) header("Range", "bytes=$offset-") }.build()
            execute(request).use { response ->
                check(response.code == 200 || response.code == 206) { "写真を取得できませんでした (${response.code})" }
                if (response.code == 200) offset = 0
                else require(response.header("Content-Range") == "bytes $offset-${photo.sizeBytes - 1}/${photo.sizeBytes}") { "写真のサイズが変わりました" }
                val body = response.body ?: error("写真が空です")
                FileOutputStream(partial, offset > 0).use { output ->
                    body.byteStream().use { input ->
                        var written = offset
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            written += count
                            require(written <= photo.sizeBytes) { "写真のサイズが一致しません" }
                            output.write(buffer, 0, count)
                        }
                    }
                }
            }
            cache.commit(key, photo.sizeBytes)
        }
    }
    fun events(scope: CoroutineScope, changed: () -> Unit, failed: () -> Unit) {
        socket?.cancel(); heartbeat?.cancel()
        socket = http.newWebSocket(request("/v1/events").build(), object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val event = runCatching { Protocol.json.decodeFromString<Event>(text) }.getOrNull()
                if (event?.type == "library_changed") changed()
            }
            override fun onFailure(webSocket: WebSocket, error: Throwable, response: Response?) { failed() }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { failed() }
        })
        heartbeat = scope.launch { while (isActive) { delay(15_000); socket?.send(Protocol.json.encodeToString(Event("heartbeat"))) } }
    }
    fun viewing(id: String) { socket?.send(Protocol.json.encodeToString(Event("viewing", id))) }
    override fun close() { heartbeat?.cancel(); socket?.cancel(); socket = null; http.dispatcher.cancelAll(); http.connectionPool.evictAll() }
    private fun safeId(id: String): String { require(id.matches(Regex("\\d{1,20}"))); return id }
}
