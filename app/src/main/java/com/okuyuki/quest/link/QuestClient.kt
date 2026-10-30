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

/** The phone no longer accepts this Quest's saved permission: it was removed on the phone. */
class NotAllowedException : IllegalStateException("スマホで接続を許可してください")

class QuestClient(val endpoint: Endpoint, private val cache: TemporaryPhotoCache) : AutoCloseable {
    // The ping makes a phone that left the network end the event socket, instead of leaving it open and silent.
    private val http = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(40, TimeUnit.SECONDS)
        .pingInterval(15, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()
    private val downloads = Mutex()
    private val panoramaDownloads = Mutex()
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
        if (it.code == 401) throw NotAllowedException()
        check(it.isSuccessful) { "スマホからの応答: ${it.code}" }
        val body = it.body ?: error("応答が空です")
        require(body.contentLength() <= 2 * 1024 * 1024) { "一覧が大きすぎます" }
        Protocol.json.decodeFromString(readBounded(body.byteStream(), 2 * 1024 * 1024).toString(Charsets.UTF_8))
    }
    suspend fun hello(): Hello = json<Hello>(request("/v1/hello").build()).also { require(it.protocolVersion == Protocol.VERSION) { "両アプリを同じバージョンに更新してください" } }
    suspend fun pair(input: PairRequest): PairResponse = json(request("/v1/pair").post(Protocol.json.encodeToString(input).toRequestBody("application/json".toMediaType())).build())
    suspend fun library(after: String? = null): LibraryPage {
        val path = after?.let { "/v1/library?after=${java.net.URLEncoder.encode(it, "UTF-8")}" } ?: "/v1/library"
        return json(request(path).build())
    }
    private suspend fun <T> readResponse(request: Request, read: (Response) -> T): T =
        readResponseChecked(request) { response, _ -> read(response) }

    private suspend fun <T> readResponseChecked(request: Request, read: (Response, () -> Unit) -> T): T = suspendCancellableCoroutine { continuation ->
        val call = http.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                if (continuation.isActive) continuation.resumeWithException(error)
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val value = response.use { read(it) { if (!continuation.isActive) throw CancellationException("Request cancelled") } }
                    if (continuation.isActive) continuation.resume(value)
                } catch (error: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            }
        })
    }
    /** Older phones do not expose this endpoint; an empty result is normal. */
    suspend fun panoramaPhoto(): PanoramaPhotoShare? = readResponse(request("/v2/panorama/photo").build()) { response ->
        if (response.code == 204 || response.code == 404) return@readResponse null
        check(response.isSuccessful) { "360°写真の情報を取得できませんでした (${response.code})" }
        val body = response.body ?: error("応答が空です")
        require(body.contentLength() in -1..4096)
        val share = Protocol.json.decodeFromString<PanoramaPhotoShare>(readBounded(body.byteStream(), 4096).toString(Charsets.UTF_8))
        require(share.isValid()) { "360°写真の情報が正しくありません" }
        share
    }
    /** v3 distinguishes camera formats; fall back to the legacy stitched-MP4 endpoint. */
    suspend fun panoramaVideo(): PanoramaVideoShare? {
        suspend fun fetch(path: String): Pair<Int, PanoramaVideoShare?> = readResponse(request(path).build()) { response ->
            if (response.code == 204 || response.code == 404) return@readResponse response.code to null
            check(response.isSuccessful) { "360°動画の情報を取得できませんでした (${response.code})" }
            val body = response.body ?: error("応答が空です")
            require(body.contentLength() in -1..4096)
            val share = Protocol.json.decodeFromString<PanoramaVideoShare>(readBounded(body.byteStream(), 4096).toString(Charsets.UTF_8))
            require(share.isValid()) { "360°動画の情報が正しくありません" }
            response.code to share
        }
        val (status, share) = fetch("/v3/panorama/video")
        return if (status == 404) fetch("/v2/panorama/video").second?.also {
            require(it.format == PanoramaVideoFormat.EQUIRECTANGULAR_MP4)
        } else share
    }
    /** Motion data is optional on older hosts and is bound to the selected video ID. */
    suspend fun panoramaVideoMotion(share: PanoramaVideoShare): OsmoMotionTrack? {
        require(share.isValid() && share.format != PanoramaVideoFormat.EQUIRECTANGULAR_MP4)
        return readResponseChecked(request("/v3/panorama/video/${share.id}/motion").build()) { response, checkActive ->
            if (response.code == 204 || response.code == 404) return@readResponseChecked null
            check(response.code == 200) { "動画の姿勢情報を取得できませんでした (${response.code})" }
            val body = response.body ?: error("姿勢情報が空です")
            require(body.contentLength() in -1..OsmoMotionCodec.MAX_BYTES.toLong()) { "姿勢情報が大きすぎます" }
            OsmoMotionCodec.decode(body.byteStream(), body.contentLength(), checkActive)
        }
    }
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun panoramaVideoSource(share: PanoramaVideoShare): androidx.media3.exoplayer.source.MediaSource {
        require(share.isValid())
        val authorization = "Bearer ${requireNotNull(token)}"
        val data = androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(http)
            .setDefaultRequestProperties(mapOf("Authorization" to authorization))
        val prefix = if (share.format == PanoramaVideoFormat.EQUIRECTANGULAR_MP4) "/v2" else "/v3"
        val media = androidx.media3.common.MediaItem.fromUri(endpoint.url + "$prefix/panorama/video/${share.id}")
        return androidx.media3.exoplayer.source.ProgressiveMediaSource.Factory(data).createMediaSource(media)
    }
    suspend fun panoramaPhotoBytes(share: PanoramaPhotoShare): ByteArray = panoramaDownloads.withLock {
        require(share.isValid())
        readResponse(request("/v2/panorama/photo/${share.id}").build()) { response ->
            check(response.code == 200) { "360°写真を取得できませんでした (${response.code})" }
            val body = response.body ?: error("画像が空です")
            require(body.contentLength() == share.sizeBytes) { "360°写真のサイズが一致しません" }
            val bytes = ByteArray(share.sizeBytes.toInt())
            body.byteStream().use { stream ->
                var offset = 0
                while (offset < bytes.size) {
                    val count = stream.read(bytes, offset, bytes.size - offset)
                    require(count > 0) { "360°写真の転送が中断されました" }
                    offset += count
                }
            }
            bytes
        }
    }
    suspend fun thumbnail(id: String): ByteArray = execute(request("/v1/items/${safeId(id)}/thumb").build()).use {
        check(it.isSuccessful) { "サムネイルを取得できませんでした" }
        val body = it.body ?: error("画像が空です")
        require(body.contentLength() <= 2 * 1024 * 1024)
        body.byteStream().use { input -> readBounded(input, 2 * 1024 * 1024) }
    }
    suspend fun original(hostId: String, photo: Photo): File = downloads.withLock {
        withContext(Dispatchers.IO) {
            require(photo.sizeBytes in 1..minOf(Protocol.MAX_ORIGINAL_BYTES, cache.limitBytes)) { "写真が大きすぎます" }
            val key = "$hostId:${photo.id}:${photo.takenAt}:${photo.sizeBytes}"
            cache.get(key)?.takeIf { it.length() == photo.sizeBytes }?.let { return@withContext it }
            cache.reserve(key, photo.sizeBytes)
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
    /** [failed] hears whether the phone ended the socket because this Quest's permission was removed (1008 or 401). */
    fun events(scope: CoroutineScope, changed: () -> Unit, failed: (revoked: Boolean) -> Unit) {
        socket?.cancel(); heartbeat?.cancel()
        socket = http.newWebSocket(request("/v1/events").build(), object : WebSocketListener() {
            private val ended = java.util.concurrent.atomic.AtomicBoolean(false)
            private fun notifyEnded(revoked: Boolean = false) { if (ended.compareAndSet(false, true)) failed(revoked) }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                notifyEnded(code == 1008)
                webSocket.close(1000, null)
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                val event = runCatching { Protocol.json.decodeFromString<Event>(text) }.getOrNull()
                if (event?.type == "library_changed" || event?.type == "panorama_changed" || event?.type == "panorama_video_changed") changed()
            }
            override fun onFailure(webSocket: WebSocket, error: Throwable, response: Response?) { notifyEnded(response?.code == 401) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { notifyEnded(code == 1008) }
        })
        heartbeat = scope.launch { while (isActive) { delay(15_000); socket?.send(Protocol.json.encodeToString(Event("heartbeat"))) } }
    }
    fun viewing(id: String) { socket?.send(Protocol.json.encodeToString(Event("viewing", id))) }
    override fun close() { heartbeat?.cancel(); socket?.cancel(); socket = null; http.dispatcher.cancelAll(); http.connectionPool.evictAll() }
    private fun safeId(id: String): String { require(id.matches(Regex("\\d{1,20}"))); return id }
    private fun readBounded(input: InputStream, limit: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size() + count <= limit) { "応答が大きすぎます" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
}
