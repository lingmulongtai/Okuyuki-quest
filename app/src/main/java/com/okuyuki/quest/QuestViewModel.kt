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
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

data class QuestState(
    val hello: Hello? = null, val connected: Boolean = false, val pairingCode: String? = null,
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
    private fun newSession() = CoroutineScope(SupervisorJob(viewModelScope.coroutineContext[Job]) + Dispatchers.Main.immediate)

    fun start() { discovery.start() }
    fun connect(endpoint: Endpoint) {
        disconnect()
        val ready = cleanup
        lastEndpoint = endpoint
        session = newSession()
        val connection = QuestClient(endpoint, cache); client = connection
        _state.value = QuestState(message = "スマホとつないでいます…")
        session.launch {
            try {
                ready?.join()
                val hello = withContext(Dispatchers.IO) { connection.hello() }
                _state.update { it.copy(hello = hello) }
                connection.token = store.token(hello.deviceId)
                if (connection.token != null) {
                    val restored = runCatching { withContext(Dispatchers.IO) { connection.library() } }.getOrNull()
                    if (restored != null) { accept(connection, restored); return@launch }
                    store.forget(hello.deviceId); connection.token = null
                }
                var request = PairRequest(store.clientId, android.os.Build.MODEL)
                repeat(120) {
                    val response = withContext(Dispatchers.IO) { connection.pair(request) }
                    _state.update { it.copy(pairingCode = response.code, message = "スマホで同じ番号を確認し、「許可」を押してください") }
                    val approvedToken = response.token
                    if (response.state == "approved" && approvedToken != null) {
                        store.save(hello.deviceId, approvedToken)
                        connection.token = approvedToken
                        accept(connection, withContext(Dispatchers.IO) { connection.library() })
                        return@launch
                    }
                    check(response.state == "pending") { "接続は許可されませんでした。もう一度つないでください" }
                    request = request.copy(requestId = response.requestId)
                    delay(1000)
                }
                error("接続の確認時間が過ぎました。もう一度つないでください")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { if (client === connection) _state.update { it.copy(pairingCode = null, error = error.message ?: "スマホにつながりませんでした", message = "再接続してください") } }
        }
    }
    private fun accept(connection: QuestClient, page: LibraryPage) {
        _state.update { it.copy(connected = true, pairingCode = null, photos = page.items, next = page.next, message = "スマホの3D写真", error = null) }
        discovery.close()
        connection.events(session, changed = { viewModelScope.launch { if (client === connection) refresh() } }, failed = {
            viewModelScope.launch {
                if (client === connection) {
                    panoramaPoll?.cancel(); panoramaRefresh?.cancel(); panoramaOpening?.cancel(); videoRefresh?.cancel(); videoMotionJob?.cancel()
                    panoramaRefreshRequested = false; videoRefreshRequested = false
                    _state.update { it.copy(connected = false, sharedPanorama = null, viewingPanorama = false,
                        panoramaBytes = null, openingPanorama = false, sharedVideo = null, viewingVideo = false,
                        videoMotion = null, videoMotionLoading = false, videoStabilizationEnabled = false, error = "スマホとの接続が切れました。再接続してください") }
                }
            }
        })
        panoramaPoll = session.launch {
            while (isActive && client === connection) {
                refreshPanorama()
                refreshVideo()
                delay(5000)
            }
        }
    }
    fun reconnect() { lastEndpoint?.let(::connect) }
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
            catch (error: Exception) { _state.update { it.copy(loadingPage = false, error = error.message) } }
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
    fun disconnect() {
        val oldSession = session.coroutineContext[Job]
        val old = client; client = null
        old?.close(); session.cancel(); panoramaRefreshRequested = false; videoRefreshRequested = false; discovery.close()
        cleanup = drain.after(oldSession)
        _state.value = QuestState(message = "スマホで「Questとつなぐ」をオンにしてください")
    }
    fun stop() {
        disconnect()
        cleanup = drain.after {
            decodeLock.withLock { runCatching { cache.clear() } }
        }
    }
    override fun onCleared() { disconnect(); runCatching { cache.clear() }; super.onCleared() }
}
