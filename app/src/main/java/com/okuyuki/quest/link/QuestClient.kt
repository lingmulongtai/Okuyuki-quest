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
import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class QuestClient(val endpoint: Endpoint, private val cache: TemporaryPhotoCache) : AutoCloseable {
    // The ping makes a phone that left the network end the event socket, instead of leaving it open and silent.
    private val http = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(40, TimeUnit.SECONDS)
        .pingInterval(15, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()
    private val downloads = Mutex()
    var token: String? = null
    @Volatile private var socket: WebSocket? = null

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
        if (!it.isSuccessful) throw failure(it, "スマホが応答できませんでした")
        val body = it.body ?: throw LinkError("スマホからの応答が空でした。")
        if (body.contentLength() > 2 * 1024 * 1024) throw LinkError("スマホからの応答が大きすぎます。")
        Protocol.json.decodeFromString(readBounded(body.byteStream(), 2 * 1024 * 1024).toString(Charsets.UTF_8))
    }
    /** The phone explains most refusals in an [ApiError]; 401 always means this Quest is not, or no longer, allowed. */
    private fun failure(response: Response, fallback: String): LinkError {
        if (response.code == 401) return LinkError("スマホで接続を許可してください。", 401)
        val message = runCatching {
            Protocol.json.decodeFromString<ApiError>(readBounded(response.body!!.byteStream(), 8192).toString(Charsets.UTF_8)).message
        }.getOrNull()?.takeIf { it.isNotBlank() }
        return LinkError(message ?: "$fallback（${response.code}）", response.code)
    }
    suspend fun hello(): Hello = json<Hello>(request("/v1/hello").build()).also {
        if (it.protocolVersion != Protocol.VERSION) throw LinkError("Quest とスマホの Okuyuki を、どちらも最新にしてください。")
    }
    suspend fun pair(input: PairRequest): PairResponse = json(request("/v1/pair").post(Protocol.json.encodeToString(input).toRequestBody("application/json".toMediaType())).build())
    suspend fun library(after: String? = null): LibraryPage {
        val path = after?.let { "/v1/library?after=${java.net.URLEncoder.encode(it, "UTF-8")}" } ?: "/v1/library"
        return json(request(path).build())
    }
    suspend fun thumbnail(id: String): ByteArray = execute(request("/v1/items/${safeId(id)}/thumb").build()).use {
        if (!it.isSuccessful) throw failure(it, "サムネイルを受け取れませんでした")
        val body = it.body ?: throw LinkError("サムネイルが空でした。")
        if (body.contentLength() > 2 * 1024 * 1024) throw LinkError("サムネイルが大きすぎます。")
        body.byteStream().use { input -> readBounded(input, 2 * 1024 * 1024) }
    }
    /** The original file, from the session cache when it is already there. [progress] hears how many bytes have arrived. */
    suspend fun original(hostId: String, photo: Photo, progress: (Long) -> Unit = {}): File = downloads.withLock {
        withContext(Dispatchers.IO) {
            if (photo.sizeBytes !in 1..minOf(Protocol.MAX_ORIGINAL_BYTES, cache.limitBytes)) throw LinkError("この写真は大きすぎて開けません。")
            val key = "$hostId:${photo.id}:${photo.takenAt}:${photo.sizeBytes}"
            cache.get(key)?.takeIf { it.length() == photo.sizeBytes }?.let { return@withContext it }
            cache.reserve(key, photo.sizeBytes)
            val partial = cache.partial(key)
            var offset = partial.length().takeIf { it < photo.sizeBytes } ?: 0L
            val request = request("/v1/items/${safeId(photo.id)}/original").apply { if (offset > 0) header("Range", "bytes=$offset-") }.build()
            execute(request).use { response ->
                if (response.code != 200 && response.code != 206) throw failure(response, "写真を受け取れませんでした")
                if (response.code == 200) offset = 0
                else if (response.header("Content-Range") != "bytes $offset-${photo.sizeBytes - 1}/${photo.sizeBytes}") throw LinkError("写真が変わりました。一覧を更新してください。")
                val body = response.body ?: throw LinkError("写真が空でした。")
                FileOutputStream(partial, offset > 0).use { output ->
                    body.byteStream().use { input ->
                        var written = offset
                        progress(written)
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            written += count
                            if (written > photo.sizeBytes) throw LinkError("写真が変わりました。一覧を更新してください。")
                            output.write(buffer, 0, count)
                            progress(written)
                        }
                    }
                }
            }
            cache.commit(key, photo.sizeBytes)
        }
    }
    /**
     * Listens for library changes until the connection ends. Returns true when the phone closed it because this Quest's
     * pairing was removed (1008), false when the phone or the network went away. [opened] runs once the socket is up.
     */
    suspend fun watch(opened: () -> Unit = {}, changed: () -> Unit): Boolean = coroutineScope {
        val ended = CompletableDeferred<Boolean>()
        val live = http.newWebSocket(request("/v1/events").build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { opened() }
            override fun onMessage(webSocket: WebSocket, text: String) {
                val event = runCatching { Protocol.json.decodeFromString<Event>(text) }.getOrNull()
                if (event?.type == "library_changed") changed()
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null); ended.complete(code == 1008) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { ended.complete(code == 1008) }
            override fun onFailure(webSocket: WebSocket, error: Throwable, response: Response?) { ended.complete(response?.code == 401) }
        })
        socket = live
        val heartbeat = launch { while (true) { delay(15_000); live.send(Protocol.json.encodeToString(Event("heartbeat"))) } }
        try { ended.await() } finally { heartbeat.cancel(); live.cancel(); if (socket === live) socket = null }
    }
    /** Tells the phone which photo is on screen, or none, so the phone can show it. */
    fun viewing(id: String?) { socket?.send(Protocol.json.encodeToString(Event("viewing", id))) }
    override fun close() { socket?.cancel(); socket = null; http.dispatcher.cancelAll(); http.connectionPool.evictAll() }
    private fun safeId(id: String): String { require(id.matches(Regex("\\d{1,20}"))); return id }
    private fun readBounded(input: InputStream, limit: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (output.size() + count > limit) throw LinkError("スマホからの応答が大きすぎます。")
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
}
