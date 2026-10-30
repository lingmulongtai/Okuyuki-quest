package com.okuyuki.quest

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.okuyuki.protocol.*
import com.okuyuki.quest.link.*
import com.okuyuki.stereo.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/** [reconnecting]: the phone went away and the app is trying it again on its own, with the library left on screen. */
data class QuestState(
    val hello: Hello? = null, val connected: Boolean = false, val reconnecting: Boolean = false, val pairingCode: String? = null,
    val photos: List<Photo> = emptyList(), val next: String? = null, val loadingPage: Boolean = false,
    val current: Photo? = null, val eyes: StereoPair? = null, val opening: Boolean = false,
    val sharedPanorama: PanoramaPhotoShare? = null, val viewingPanorama: Boolean = false,
    val panoramaBytes: ByteArray? = null, val openingPanorama: Boolean = false,
    val sharedVideo: PanoramaVideoShare? = null, val viewingVideo: Boolean = false,
    val videoMotion: OsmoStabilization? = null, val videoMotionLoading: Boolean = false,
    val videoStabilizationEnabled: Boolean = false, val videoStabilizationUnavailable: Boolean = false,
    val videoGeneration: Long = 0L,
    val message: String = "スマホを探しています", val error: String? = null, val cacheBytes: Long = 0,
)

class QuestViewModel(application: Application) : AndroidViewModel(application) {
    val discovery = PhoneDiscovery(application)
    private val store = PairingStore(application)
    private val cache = TemporaryPhotoCache(File(application.cacheDir, "session-photos"))
    private val decodeLock = Mutex()
    private val _state = MutableStateFlow(QuestState())
    val state = _state.asStateFlow()
    private var client: QuestClient? = null
    private var session = newSession()
    private var opening: Job? = null
    private var prefetch: Job? = null
    private var panoramaOpening: Job? = null
    private var panoramaRefresh: Job? = null
    private var panoramaPoll: Job? = null
    private var videoRefresh: Job? = null
    private var videoMotionJob: Job? = null
    private var videoGeneration = 0L
    private var videoRefreshRequested = false
    private var panoramaRefreshRequested = false
    private var lastEndpoint: Endpoint? = null
    private var cleanup: Job? = null
    private val drain = SessionDrain(viewModelScope)
    /** Asks a waiting reconnect to try now, at the address given: 再接続 was pressed, or the phone was seen again. */
    private val retries = Channel<Endpoint>(Channel.CONFLATED)
    private var reconnectingHost: String? = null
    /** Going back on its own to the phone used last time, until the person ends a connection themselves. */
    private var resumeAllowed = false
    private val resumeTried = mutableSetOf<Endpoint>()
    private val greeting = mutableSetOf<Endpoint>()
    private fun newSession() = CoroutineScope(SupervisorJob(viewModelScope.coroutineContext[Job]) + Dispatchers.Main.immediate)
    private fun idle() = client == null && !_state.value.connected && !_state.value.reconnecting

    init { viewModelScope.launch { discovery.phones.collect { phones -> phones.forEach { found(it.endpoint) } } } }

    /** The app came to the front: look for phones, and go back to the one used last time while this Quest may. */
    fun start() {
        resumeAllowed = true
        resumeTried.clear()
        discovery.start()
        if (idle()) store.last()?.let { (_, endpoint) -> resume(endpoint) }
    }
    /** A phone appeared on the network: when it is the one being waited for, or the one used last time, go back to it. */
    private fun found(endpoint: Endpoint) {
        val hostId = if (_state.value.reconnecting) reconnectingHost else if (resumeAllowed && idle() && endpoint !in resumeTried) store.last()?.first else null
        if (hostId == null || !greeting.add(endpoint)) return
        viewModelScope.launch {
            val hello = runCatching { QuestClient(endpoint, cache).use { withTimeout(5_000) { it.hello() } } }.getOrNull()
            greeting.remove(endpoint)
            if (hello?.deviceId != hostId) return@launch
            if (_state.value.reconnecting) retries.trySend(endpoint) else if (idle()) resume(endpoint)
        }
    }
    private fun resume(endpoint: Endpoint) { if (resumeAllowed && resumeTried.add(endpoint)) begin(endpoint, quiet = true) }
    fun connect(endpoint: Endpoint) = begin(endpoint, quiet = false)
    /** [quiet] goes back with the saved permission only, and returns to the phone list without an error when that fails. */
    private fun begin(endpoint: Endpoint, quiet: Boolean) {
        end()
        val ready = cleanup
        lastEndpoint = endpoint
        session = newSession()
        val connection = QuestClient(endpoint, cache); client = connection
        _state.value = QuestState(message = if (quiet) "前回のスマホにつないでいます…" else "スマホとつないでいます…")
        session.launch {
            try {
                ready?.join()
                val hello = withContext(Dispatchers.IO) { connection.hello() }
                _state.update { it.copy(hello = hello) }
                connection.token = store.token(hello.deviceId)
                if (connection.token != null) {
                    val restored = try { withContext(Dispatchers.IO) { connection.library() } } catch (_: NotAllowedException) { null }
                    if (restored != null) { accept(connection, hello, restored); return@launch }
                    store.forget(hello.deviceId); connection.token = null
                }
                // Asking for permission needs the person at the phone, so going back on its own stops here.
                if (quiet) { if (client === connection) toPhoneList(); return@launch }
                var request = PairRequest(store.clientId, android.os.Build.MODEL)
                repeat(120) {
                    val response = withContext(Dispatchers.IO) { connection.pair(request) }
                    _state.update { it.copy(pairingCode = response.code, message = "スマホで同じ番号を確認し、「許可」を押してください") }
                    val approvedToken = response.token
                    if (response.state == "approved" && approvedToken != null) {
                        store.save(hello.deviceId, approvedToken)
                        connection.token = approvedToken
                        accept(connection, hello, withContext(Dispatchers.IO) { connection.library() })
                        return@launch
                    }
                    check(response.state == "pending") { "接続は許可されませんでした。もう一度つないでください" }
                    request = request.copy(requestId = response.requestId)
                    delay(1000)
                }
                error("接続の確認時間が過ぎました。もう一度つないでください")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (client !== connection) return@launch
                if (quiet) toPhoneList()
                else _state.update { it.copy(pairingCode = null, error = error.message ?: "スマホにつながりませんでした", message = "再接続してください") }
            }
        }
    }
    private fun accept(connection: QuestClient, hello: Hello, page: LibraryPage) {
        client = connection
        lastEndpoint = connection.endpoint
        reconnectingHost = null
        store.remember(hello.deviceId, connection.endpoint)
        while (retries.tryReceive().isSuccess) Unit
        _state.update { state ->
            // After a short outage the list has usually not changed: keep what was loaded, and so the place in it.
            val same = state.reconnecting && state.photos.take(page.items.size) == page.items
            state.copy(connected = true, reconnecting = false, hello = hello, pairingCode = null, photos = if (same) state.photos else page.items,
                next = if (same) state.next else page.next, message = "スマホの3D写真", error = null)
        }
        discovery.close()
        connection.events(session, changed = { viewModelScope.launch { if (client === connection) refresh() } }, failed = { revoked ->
            viewModelScope.launch { if (client === connection) lost(connection, hello.deviceId, revoked) }
        })
        panoramaPoll = session.launch {
            while (isActive && client === connection) {
                refreshPanorama()
                refreshVideo()
                delay(5000)
            }
        }
    }
    /**
     * The phone went away. Unless it removed this Quest, keep the library on screen and try the same phone again, waiting a
     * little longer each time for about two minutes; the phone seen again on the network, or 再接続, tries at once.
     */
    private fun lost(connection: QuestClient, hostId: String, revoked: Boolean) {
        panoramaPoll?.cancel(); panoramaRefresh?.cancel(); panoramaOpening?.cancel(); videoRefresh?.cancel(); videoMotionJob?.cancel()
        panoramaRefreshRequested = false; videoRefreshRequested = false
        opening?.cancel(); prefetch?.cancel()
        client = null; connection.close()
        if (revoked) return removed(hostId)
        reconnectingHost = hostId
        _state.update { it.copy(connected = false, reconnecting = true, opening = false, loadingPage = false, sharedPanorama = null,
            viewingPanorama = false, panoramaBytes = null, openingPanorama = false, sharedVideo = null, viewingVideo = false,
            videoMotion = null, videoMotionLoading = false, videoStabilizationEnabled = false, error = null) }
        discovery.start()
        session.launch { reconnect(hostId, connection.endpoint) }
    }
    private suspend fun reconnect(hostId: String, from: Endpoint) {
        var endpoint = from
        for (pause in RETRY_SECONDS) {
            withTimeoutOrNull(pause * 1000L) { retries.receive() }?.let { endpoint = it }
            val token = store.token(hostId) ?: return removed(hostId)
            val connection = QuestClient(endpoint, cache).also { it.token = token }
            try {
                val hello = withContext(Dispatchers.IO) { connection.hello() }
                if (hello.deviceId != hostId) { connection.close(); continue }
                accept(connection, hello, withContext(Dispatchers.IO) { connection.library() })
                // A photo that was still arriving when the phone went away is fetched again.
                _state.value.current?.takeIf { _state.value.eyes == null }?.let(::open)
                return
            } catch (cancelled: CancellationException) { connection.close(); throw cancelled }
            catch (_: NotAllowedException) { connection.close(); return removed(hostId) }
            catch (_: Exception) { connection.close() }
        }
        reconnectingHost = null
        resumeTried.clear()
        _state.update { it.copy(reconnecting = false, error = "スマホとの接続が切れました。スマホのOkuyukiで待機がオンか確かめて、再接続してください") }
    }
    /** The phone removed this Quest: forget its permission and go back to the phone list, saying so. */
    private fun removed(hostId: String) {
        store.forget(hostId)
        toPhoneList()
        _state.update { it.copy(error = "スマホで接続が解除されました。つなぐと、スマホでもう一度許可できます") }
    }
    fun reconnect() {
        val endpoint = lastEndpoint ?: return
        if (_state.value.reconnecting) retries.trySend(endpoint) else connect(endpoint)
    }
    fun refresh() { if (_state.value.connected) { loadPage(replace = true); refreshPanorama(); refreshVideo() } }
    private fun refreshPanorama() {
        if (!_state.value.connected) return
        val connection = client ?: return
        if (panoramaRefresh?.isActive == true) { panoramaRefreshRequested = true; return }
        panoramaRefresh = session.launch {
            do {
                panoramaRefreshRequested = false
                try {
                    val item = connection.panoramaPhoto()
                    if (client !== connection || !_state.value.connected) return@launch
                    if (_state.value.sharedPanorama != item) {
                        panoramaOpening?.cancel()
                        _state.update { it.copy(sharedPanorama = item, viewingPanorama = false, panoramaBytes = null, openingPanorama = false) }
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) {
                    if (client === connection) {
                        panoramaOpening?.cancel()
                        _state.update { it.copy(sharedPanorama = null, viewingPanorama = false, panoramaBytes = null, openingPanorama = false) }
                    }
                }
            } while (panoramaRefreshRequested && isActive && client === connection && _state.value.connected)
        }
    }
    private fun refreshVideo() {
        if (!_state.value.connected) return
        val connection = client ?: return
        if (videoRefresh?.isActive == true) { videoRefreshRequested = true; return }
        videoRefresh = session.launch {
            do {
                videoRefreshRequested = false
                try {
                    val item = connection.panoramaVideo()
                    if (client !== connection || !_state.value.connected) return@launch
                    if (!videoRefreshRequested) {
                        if (_state.value.sharedVideo != item) videoMotionJob?.cancel()
                        _state.update { state -> if (state.sharedVideo == item) state else state.copy(
                            sharedVideo = item, viewingVideo = false, videoMotion = null,
                            videoMotionLoading = false, videoStabilizationEnabled = false) }
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) {
                    if (client === connection) {
                        videoMotionJob?.cancel()
                        _state.update { it.copy(sharedVideo = null, viewingVideo = false, videoMotion = null,
                            videoMotionLoading = false, videoStabilizationEnabled = false) }
                    }
                }
            } while (videoRefreshRequested && isActive && client === connection && _state.value.connected)
        }
    }
    fun openVideo() {
        val state = _state.value
        val share = state.sharedVideo ?: return
        val connection = client ?: return
        if (!state.connected) return
        opening?.cancel(); prefetch?.cancel(); panoramaOpening?.cancel(); videoMotionJob?.cancel()
        val raw = share.format != PanoramaVideoFormat.EQUIRECTANGULAR_MP4
        val generation = ++videoGeneration
        _state.update { it.copy(current = null, eyes = null, viewingPanorama = false, panoramaBytes = null,
            openingPanorama = false, viewingVideo = true, videoMotion = null,
            videoMotionLoading = raw, videoStabilizationEnabled = false, videoStabilizationUnavailable = false,
            videoGeneration = generation, error = null) }
        if (!raw) return
        videoMotionJob = session.launch {
            try {
                // OkHttp's read timeout bounds stalls; a progressing long transfer may need more than 12 seconds.
                val track = connection.panoramaVideoMotion(share)
                val correction = track?.let { withContext(Dispatchers.Default) {
                    val workContext = coroutineContext
                    OsmoStabilization(it) { workContext.ensureActive() }
                } }
                if (client !== connection) return@launch
                _state.update { current ->
                    if (!current.connected || !current.viewingVideo || current.sharedVideo != share || current.videoGeneration != generation) current
                    else current.copy(videoMotion = correction, videoMotionLoading = false)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (client === connection) _state.update { current ->
                    if (!current.connected || !current.viewingVideo || current.sharedVideo != share || current.videoGeneration != generation) current
                    else current.copy(videoMotion = null, videoMotionLoading = false)
                }
            }
        }
    }
    fun setVideoStabilization(enabled: Boolean, shareId: String, generation: Long) {
        _state.update { state ->
            if (!state.viewingVideo || state.sharedVideo?.id != shareId || state.videoGeneration != generation) state
            else state.copy(videoStabilizationEnabled = enabled && state.videoMotion != null, videoStabilizationUnavailable = false)
        }
    }
    fun videoStabilizationUnavailable(shareId: String, generation: Long) {
        _state.update { state ->
            if (!state.viewingVideo || state.sharedVideo?.id != shareId || state.videoGeneration != generation) state
            else state.copy(videoStabilizationEnabled = false, videoStabilizationUnavailable = true)
        }
    }
    fun prepareImmersiveVideo(highResolution: Boolean = false): Boolean {
        val current = _state.value
        val share = current.sharedVideo ?: return false
        val existing = client ?: return false
        val token = existing.token ?: return false
        if (!current.connected || !current.viewingVideo || current.videoMotionLoading) return false
        val connection = QuestClient(existing.endpoint, cache).apply { this.token = token }
        val stabilization = current.videoMotion.takeIf { current.videoStabilizationEnabled }
        ImmersivePlayback.install(ImmersivePlayback.Connection(connection, share, stabilization,
            highResolution && share.format == PanoramaVideoFormat.OSMO_OSV))
        return true
    }
    fun closeVideo() {
        videoMotionJob?.cancel()
        _state.update { it.copy(viewingVideo = false, videoMotion = null, videoMotionLoading = false, videoStabilizationEnabled = false) }
    }
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun videoSource(): androidx.media3.exoplayer.source.MediaSource? {
        val state = _state.value
        if (!state.connected || !state.viewingVideo) return null
        return client?.panoramaVideoSource(state.sharedVideo ?: return null)
    }
    fun openPanorama() {
        if (!_state.value.connected) return
        val connection = client ?: return
        val item = _state.value.sharedPanorama ?: return
        opening?.cancel(); prefetch?.cancel(); panoramaOpening?.cancel()
        _state.update { it.copy(current = null, eyes = null, viewingVideo = false, viewingPanorama = true, panoramaBytes = null, openingPanorama = true, error = null) }
        panoramaOpening = session.launch {
            try {
                val bytes = connection.panoramaPhotoBytes(item)
                if (client === connection && _state.value.sharedPanorama == item) {
                    _state.update { it.copy(panoramaBytes = bytes, openingPanorama = false) }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (client === connection) _state.update { it.copy(openingPanorama = false, viewingPanorama = false, error = "360°写真を取得できませんでした") }
            }
        }
    }
    fun closePanorama() {
        panoramaOpening?.cancel()
        _state.update { it.copy(viewingPanorama = false, panoramaBytes = null, openingPanorama = false) }
    }
    fun more() { if (_state.value.next != null) loadPage(replace = false) }
    private fun loadPage(replace: Boolean) {
        if (_state.value.loadingPage) return
        val connection = client ?: return
        val after = if (replace) null else _state.value.next
        _state.update { it.copy(loadingPage = true) }
        session.launch {
            try {
                val page = withContext(Dispatchers.IO) { connection.library(after) }
                _state.update { it.copy(photos = (if (replace) page.items else it.photos + page.items).distinctBy { photo -> photo.id }, next = page.next, loadingPage = false, error = null) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { _state.update { it.copy(loadingPage = false, error = if (client === connection) error.message else it.error) } }
        }
    }
    suspend fun thumbnail(id: String): Bitmap? {
        val connection = client ?: return null
        return withContext(Dispatchers.IO) {
            runCatching { val data = connection.thumbnail(id); BitmapFactory.decodeByteArray(data, 0, data.size) }.getOrNull()
        }
    }
    fun open(photo: Photo) {
        val connection = client ?: return
        val host = _state.value.hello ?: return
        opening?.cancel(); prefetch?.cancel(); panoramaOpening?.cancel()
        _state.update { it.copy(current = photo, eyes = null, opening = true, viewingVideo = false, error = null, viewingPanorama = false, panoramaBytes = null, openingPanorama = false) }
        opening = session.launch {
            try {
                val file = connection.original(host.deviceId, photo)
                val eyes = decodeLock.withLock { withContext(Dispatchers.IO) { StereoDecoder.decode(file) } }
                _state.update { it.copy(eyes = eyes, opening = false, cacheBytes = cache.usage()) }
                connection.viewing(photo.id)
                val next = _state.value.photos.getOrNull(_state.value.photos.indexOfFirst { it.id == photo.id } + 1)
                if (next != null) prefetch = session.launch { runCatching { connection.original(host.deviceId, next); _state.update { it.copy(cacheBytes = cache.usage()) } } }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { _state.update { it.copy(opening = false, error = error.message ?: "3D写真を開けませんでした") } }
            catch (_: LinkageError) { _state.update { it.copy(opening = false, error = "この端末ではHEICデコーダを使用できません") } }
        }
    }
    fun step(direction: Int) {
        val state = _state.value
        val index = state.photos.indexOfFirst { it.id == state.current?.id }
        state.photos.getOrNull(index + direction)?.let(::open)
    }
    fun backToLibrary() { opening?.cancel(); prefetch?.cancel(); _state.update { it.copy(current = null, eyes = null, opening = false, error = null) } }
    /** The person ended the connection: back to the phone list, without going back on its own until the app reopens. */
    fun disconnect() { resumeAllowed = false; toPhoneList() }
    private fun toPhoneList() {
        end()
        _state.value = QuestState(message = "スマホのOkuyukiで待機をオンにしてください")
        discovery.start()
        // A phone seen while another attempt was running gets its turn now.
        discovery.phones.value.forEach { found(it.endpoint) }
    }
    private fun end() {
        val oldSession = session.coroutineContext[Job]
        val old = client; client = null
        old?.close(); session.cancel(); panoramaRefreshRequested = false; videoRefreshRequested = false; reconnectingHost = null
        cleanup = drain.after(oldSession)
    }
    fun stop() {
        end()
        discovery.close()
        _state.value = QuestState(message = "スマホのOkuyukiで待機をオンにしてください")
        cleanup = drain.after {
            decodeLock.withLock { runCatching { cache.clear() } }
        }
    }
    override fun onCleared() { end(); discovery.close(); runCatching { cache.clear() }; super.onCleared() }

    private companion object {
        /** Pauses before each reconnect attempt, about two minutes in all. */
        val RETRY_SECONDS = listOf(1, 2, 4, 8, 15, 30, 30, 30)
    }
}
