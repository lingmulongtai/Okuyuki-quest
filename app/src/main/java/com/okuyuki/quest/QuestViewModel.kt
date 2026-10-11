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
    private var lastEndpoint: Endpoint? = null
    private fun newSession() = CoroutineScope(SupervisorJob(viewModelScope.coroutineContext[Job]) + Dispatchers.Main.immediate)

    fun start() { discovery.start() }
    fun connect(endpoint: Endpoint) {
        disconnect()
        lastEndpoint = endpoint
        session = newSession()
        val connection = QuestClient(endpoint, cache); client = connection
        _state.value = QuestState(message = "スマホとつないでいます…")
        session.launch {
            try {
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
            viewModelScope.launch { if (client === connection) _state.update { it.copy(connected = false, error = "スマホとの接続が切れました。再接続してください") } }
        })
    }
    fun reconnect() { lastEndpoint?.let(::connect) }
    fun refresh() { loadPage(replace = true) }
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
        opening?.cancel(); prefetch?.cancel()
        _state.update { it.copy(current = photo, eyes = null, opening = true, error = null) }
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
        val old = client; client = null
        old?.close(); session.cancel(); discovery.close()
        _state.value = QuestState(message = "スマホで「Questとつなぐ」をオンにしてください")
    }
    fun stop() {
        disconnect()
        viewModelScope.launch(Dispatchers.IO) { decodeLock.withLock { runCatching { cache.clear() } } }
    }
    override fun onCleared() { disconnect(); runCatching { cache.clear() }; super.onCleared() }
}
