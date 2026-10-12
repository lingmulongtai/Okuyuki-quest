package com.okuyuki.quest

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
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

/**
 * Where the link to the phone stands. [RECONNECTING] and [LOST] keep the library on screen: the first while the app
 * retries on its own, the second once it has stopped and waits for the person or for the phone to show up again.
 */
enum class Link { IDLE, CONNECTING, PAIRING, CONNECTED, RECONNECTING, LOST }

/** A photo on its way: [received] of [total] bytes from the phone, then [decoding] into the two eyes. */
data class Opening(val received: Long = 0, val total: Long = 0, val decoding: Boolean = false) {
    val fraction: Float get() = if (total > 0) (received.toFloat() / total).coerceIn(0f, 1f) else 0f
}

/** A phone on the network. [paired] when this Quest already has its permission, [last] when it is the one used last time. */
data class Nearby(val name: String, val endpoint: Endpoint, val paired: Boolean = false, val last: Boolean = false)

data class QuestState(
    val link: Link = Link.IDLE,
    /** The phone being connected to or shown, by name. */
    val phone: String? = null,
    val endpoint: Endpoint? = null,
    val hello: Hello? = null,
    /** Going back on its own to the phone used last time, while the connect screen stays usable. */
    val resuming: Boolean = false,
    val pairingCode: String? = null,
    /** Why the last connection ended, for the connect screen. */
    val notice: String? = null,
    val photos: List<Photo> = emptyList(),
    val next: String? = null,
    /** How many 3D photos the phone has, including pages not loaded yet. */
    val total: Int = 0,
    val loadingPage: Boolean = false,
    /** Loading the list again from the top, as opposed to its next page. */
    val refreshing: Boolean = false,
    val pageError: String? = null,
    val current: Photo? = null,
    val eyes: StereoPair? = null,
    val opening: Opening? = null,
    val openError: String? = null,
    /** The photo the viewer showed last, so the library can point it out. */
    val lastViewed: String? = null,
    val cacheBytes: Long = 0,
)

/** Everything the screens can ask for. */
interface QuestActions {
    fun connect(endpoint: Endpoint, name: String? = null)
    fun cancel()
    fun disconnect()
    fun forget()
    fun retry()
    fun refresh()
    fun more()
    fun open(photo: Photo)
    fun step(direction: Int)
    fun close()
    fun clearCache()
}

/** Grid thumbnails from the phone: [cached] answers at once, [load] fetches when needed. */
interface Thumbnails {
    fun cached(id: String): Bitmap?
    suspend fun load(id: String): Bitmap?
}

class QuestViewModel(application: Application) : AndroidViewModel(application), QuestActions, Thumbnails {
    private val discovery = PhoneDiscovery(application)
    private val store = PairingStore(application)
    private val cache = TemporaryPhotoCache(File(application.cacheDir, "session-photos"))
    private val decodeLock = Mutex()
    private val _state = MutableStateFlow(QuestState())
    val state = _state.asStateFlow()
    val discoveryError = discovery.error
    private val greeted = MutableStateFlow<Map<Endpoint, Hello>>(emptyMap())
    /** Phones on the network, named by what they said when greeted. */
    val nearby: StateFlow<List<Nearby>> = combine(discovery.phones, greeted) { phones, hellos ->
        val last = store.last()?.first
        phones.map { phone ->
            val hello = hellos[phone.endpoint]
            if (hello == null) Nearby(phone.name.removePrefix("Okuyuki ").ifBlank { phone.name }, phone.endpoint)
            else Nearby(hello.deviceName, phone.endpoint, store.paired(hello.deviceId), hello.deviceId == last)
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    private var client: QuestClient? = null
    private var session = newSession()
    private var opening: Job? = null
    private var prefetch: Job? = null
    private var pageJob: Job? = null
    private var stepWhenLoaded = false
    private var cleanup: Job? = null
    private val drain = SessionDrain(viewModelScope)
    /** Asks a waiting reconnect to try now, at the address given: the person pressed retry, or the phone was seen again. */
    private val retries = Channel<Endpoint>(Channel.CONFLATED)
    private var resumeAllowed = false
    private val resumeTried = mutableSetOf<Endpoint>()
    private val greeting = mutableSetOf<Endpoint>()
    private val thumbs = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8 / 1024).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount / 1024
    }
    private val thumbLoads = HashMap<String, Deferred<Bitmap?>>()
    private fun newSession() = CoroutineScope(SupervisorJob(viewModelScope.coroutineContext[Job]) + Dispatchers.Main.immediate)

    init {
        viewModelScope.launch {
            discovery.phones.collect { phones ->
                for (phone in phones) greeted.value[phone.endpoint]?.let { found(phone.endpoint, it) } ?: greet(phone.endpoint)
            }
        }
    }

    /** The app came to the front: look for phones, and go back to the one used last time if it is there. */
    fun start() {
        resumeAllowed = true
        resumeTried.clear()
        if (_state.value.link == Link.IDLE) {
            discovery.start()
            store.last()?.let { (_, endpoint) -> resume(endpoint, store.lastName()) }
        }
    }

    /** The app left the front: end the connection and erase the photos it fetched. */
    fun stop() {
        end()
        discovery.close()
        _state.value = QuestState()
        cleanup = drain.after { decodeLock.withLock { runCatching { cache.clear() } } }
    }

    /** Asks a phone found on the network who it is, without connecting. */
    private fun greet(endpoint: Endpoint) {
        if (!greeting.add(endpoint)) return
        viewModelScope.launch {
            val hello = runCatching { QuestClient(endpoint, cache).use { withTimeout(5_000) { it.hello() } } }.getOrNull()
            greeting.remove(endpoint)
            if (hello != null) { greeted.update { it + (endpoint to hello) }; found(endpoint, hello) }
        }
    }

    private fun found(endpoint: Endpoint, hello: Hello) {
        val state = _state.value
        when {
            state.link in setOf(Link.RECONNECTING, Link.LOST) && hello.deviceId == state.hello?.deviceId -> retries.trySend(endpoint)
            state.link == Link.IDLE && !state.resuming && store.paired(hello.deviceId) -> resume(endpoint, hello.deviceName)
        }
    }

    /** Connects quietly with a saved permission; when that does not work, the connect screen simply stays. */
    private fun resume(endpoint: Endpoint, name: String?) {
        if (!resumeAllowed || !resumeTried.add(endpoint)) return
        begin(endpoint, name, quiet = true)
    }

    override fun connect(endpoint: Endpoint, name: String?) { begin(endpoint, name, quiet = false) }

    private fun begin(endpoint: Endpoint, name: String?, quiet: Boolean) {
        end()
        val ready = cleanup
        session = newSession()
        _state.value = QuestState(link = if (quiet) Link.IDLE else Link.CONNECTING, phone = name, endpoint = endpoint, resuming = quiet,
            cacheBytes = _state.value.cacheBytes)
        session.launch {
            ready?.join()
            try { run(endpoint, quiet) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { idle(if (quiet) null else describe(error)) }
        }
    }

    /** One connection to a phone, from hello to the person leaving, with the reconnects in between. */
    private suspend fun run(first: Endpoint, quiet: Boolean) {
        var connection = QuestClient(first, cache).also { client = it }
        val hello = io { connection.hello() }
        _state.update { it.copy(hello = hello, phone = hello.deviceName) }
        connection.token = store.token(hello.deviceId)
        val allowed = connection.token?.let {
            try { io { connection.library() } }
            catch (error: LinkError) { if (error.unauthorized) null else throw error }
        }
        if (allowed == null) {
            store.forget(hello.deviceId)
            connection.token = null
            if (quiet) return idle(null)
        }
        accept(connection, hello, allowed ?: pair(connection, hello), keep = false)
        // Event sockets that fail before opening, one after another, back off like failed reconnects instead of flapping.
        var unopened = 0
        while (true) {
            val live = connection
            var opened = false
            val revoked = live.watch(opened = { opened = true; main { _state.value.current?.let { live.viewing(it.id) } } },
                changed = { main { if (client === live) refresh() } })
            unopened = if (opened) 0 else unopened + 1
            if (client === connection) client = null
            connection.close()
            if (revoked) { store.forget(hello.deviceId); return idle("スマホで接続が解除されました。もう一度つなぐと、スマホで許可できます。") }
            opening?.cancel(); prefetch?.cancel(); pageJob?.cancel()
            _state.update { it.copy(link = Link.RECONNECTING, loadingPage = false, refreshing = false, opening = null,
                openError = if (it.opening != null) "スマホとの接続が切れました。" else it.openError) }
            discovery.start()
            connection = reconnect(hello.deviceId, connection.endpoint, firstAttempt = unopened - 1) ?: return
        }
    }

    /** Asks for permission on the phone, showing the same four digits here, and returns the first page once allowed. */
    private suspend fun pair(connection: QuestClient, hello: Hello): LibraryPage {
        var request = PairRequest(store.clientId, android.os.Build.MODEL)
        repeat(120) {
            val response = io { connection.pair(request) }
            when (response.state) {
                "approved" -> {
                    val token = response.token ?: throw LinkError("スマホから許可を受け取れませんでした。もう一度つないでください。")
                    store.save(hello.deviceId, token)
                    connection.token = token
                    return io { connection.library() }
                }
                "pending" -> _state.update { it.copy(link = Link.PAIRING, pairingCode = response.code) }
                "rejected" -> throw LinkError("スマホで拒否されました。つなぐときは、スマホで「許可する」を押してください。")
                else -> throw LinkError("確認の時間が過ぎました。もう一度つないでください。")
            }
            request = request.copy(requestId = response.requestId)
            delay(1000)
        }
        throw LinkError("確認の時間が過ぎました。もう一度つないでください。")
    }

    private fun accept(connection: QuestClient, hello: Hello, page: LibraryPage, keep: Boolean) {
        client = connection
        store.remember(hello.deviceId, connection.endpoint, hello.deviceName)
        discovery.close()
        while (retries.tryReceive().isSuccess) Unit
        _state.update { state ->
            // After a short outage the list has usually not changed: keep what was loaded, and so the person's place in it.
            val same = keep && state.photos.take(page.items.size) == page.items
            state.copy(link = Link.CONNECTED, hello = hello, phone = hello.deviceName, endpoint = connection.endpoint, resuming = false,
                pairingCode = null, notice = null, photos = if (same) state.photos else page.items,
                next = if (same) state.next else page.next, total = hello.photoCount, loadingPage = false, refreshing = false, pageError = null)
        }
    }

    /**
     * Tries the same phone again, pausing a little longer each time for about two minutes, then waits for the person or for
     * the phone to be seen again. Returns null when the phone has removed this Quest, after going back to the connect screen.
     */
    private suspend fun reconnect(hostId: String, from: Endpoint, firstAttempt: Int = 0): QuestClient? {
        var endpoint = from
        var attempt = firstAttempt.coerceAtLeast(0)
        while (true) {
            val pause = RETRY_SECONDS.getOrNull(attempt++)
            if (pause == null) _state.update { it.copy(link = Link.LOST) }
            val sooner = if (pause == null) retries.receive() else withTimeoutOrNull(pause * 1000L) { retries.receive() }
            if (sooner != null) endpoint = sooner
            if (pause == null) { attempt = 0; _state.update { it.copy(link = Link.RECONNECTING) } }
            val token = store.token(hostId) ?: return null.also { idle("スマホで接続が解除されました。もう一度つないでください。") }
            val connection = QuestClient(endpoint, cache).also { it.token = token }
            try {
                val hello = io { connection.hello() }
                if (hello.deviceId != hostId) { connection.close(); continue }
                accept(connection, hello, io { connection.library() }, keep = true)
                return connection
            } catch (cancelled: CancellationException) { connection.close(); throw cancelled }
            catch (error: Exception) {
                connection.close()
                if (error is LinkError && error.unauthorized) {
                    store.forget(hostId)
                    idle("スマホで接続が解除されました。もう一度つなぐと、スマホで許可できます。")
                    return null
                }
            }
        }
    }

    /** Back to the connect screen, saying why when there is something to say. */
    private fun idle(notice: String?) {
        end()
        _state.value = QuestState(notice = notice, cacheBytes = _state.value.cacheBytes)
        discovery.start()
        // A paired phone seen while another attempt was running gets its turn now.
        for (phone in discovery.phones.value) greeted.value[phone.endpoint]?.let { found(phone.endpoint, it) }
    }

    /** Closes the connection and stops what it started; the next connection waits until that has finished. */
    private fun end() {
        val old = session.coroutineContext[Job]
        client?.close(); client = null
        opening = null; prefetch = null; pageJob = null; stepWhenLoaded = false
        session.cancel()
        cleanup = drain.after(old)
    }

    override fun cancel() { resumeAllowed = false; idle(null) }
    override fun disconnect() { resumeAllowed = false; idle(null) }
    override fun forget() {
        val hostId = _state.value.hello?.deviceId ?: store.last()?.first ?: return
        resumeAllowed = false
        store.forget(hostId)
        idle("登録を解除しました。次につなぐときは、スマホでもう一度許可します。")
    }
    override fun retry() { _state.value.endpoint?.let { retries.trySend(it) } }

    override fun refresh() { loadPage(replace = true) }
    override fun more() { loadPage(replace = false) }
    private fun loadPage(replace: Boolean) {
        val connection = client ?: return
        val state = _state.value
        if (!replace && (state.next == null || pageJob?.isActive == true)) return
        pageJob?.cancel()
        val after = if (replace) null else state.next
        _state.update { it.copy(loadingPage = true, refreshing = replace, pageError = null) }
        pageJob = session.launch {
            try {
                val loaded = io { connection.library(after) }
                val total = if (replace) runCatching { io { connection.hello() }.photoCount }.getOrNull() else null
                _state.update { s ->
                    s.copy(photos = if (replace) loaded.items else (s.photos + loaded.items).distinctBy { it.id }, next = loaded.next,
                        total = total ?: s.total, loadingPage = false, refreshing = false)
                }
                if (stepWhenLoaded) { stepWhenLoaded = false; step(1) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                // The list changed under the page being asked for: start again from the top.
                if (!replace && error is LinkError && error.code == 400) { pageJob = null; loadPage(replace = true) }
                else _state.update { it.copy(loadingPage = false, refreshing = false, pageError = describe(error)) }
            }
        }
    }

    override fun cached(id: String): Bitmap? = thumbKey(id)?.let(thumbs::get)
    override suspend fun load(id: String): Bitmap? {
        val key = thumbKey(id) ?: return null
        thumbs.get(key)?.let { return it }
        val connection = client ?: return null
        val loading = thumbLoads.getOrPut(key) {
            viewModelScope.async(Dispatchers.IO) {
                runCatching { connection.thumbnail(id).let { BitmapFactory.decodeByteArray(it, 0, it.size) } }.getOrNull()
            }
        }
        val bitmap = loading.await()
        if (thumbLoads[key] === loading) thumbLoads.remove(key)
        return bitmap?.also { thumbs.put(key, it) }
    }
    private fun thumbKey(id: String) = _state.value.hello?.deviceId?.let { "$it:$id" }

    override fun open(photo: Photo) {
        val connection = client ?: return
        val hostId = _state.value.hello?.deviceId ?: return
        opening?.cancel(); prefetch?.cancel()
        _state.update { it.copy(current = photo, eyes = null, opening = Opening(total = photo.sizeBytes), openError = null, lastViewed = photo.id) }
        val photos = _state.value.photos
        if (photos.indexOfFirst { it.id == photo.id } >= photos.size - 4) more()
        opening = session.launch {
            try {
                var percent = -1
                val file = connection.original(hostId, photo) { received ->
                    val now = (received * 100 / photo.sizeBytes).toInt()
                    if (now != percent) { percent = now; _state.update { if (it.current?.id == photo.id) it.copy(opening = Opening(received, photo.sizeBytes)) else it } }
                }
                _state.update { it.copy(opening = Opening(photo.sizeBytes, photo.sizeBytes, decoding = true)) }
                val eyes = decodeLock.withLock { io { StereoDecoder.decode(file) } }
                _state.update { it.copy(eyes = eyes, opening = null, cacheBytes = cache.usage()) }
                connection.viewing(photo.id)
                prefetchAround(connection, hostId, photo)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: java.io.IOException) { failOpening(describe(error)) }
            catch (_: Exception) { failOpening("この写真を立体で開けませんでした。") }
            catch (_: LinkageError) { failOpening("この Quest では HEIC 写真を読み込めません。") }
        }
    }
    private fun failOpening(message: String) { _state.update { it.copy(opening = null, openError = message, cacheBytes = cache.usage()) } }

    /** Fetches the photos on either side, next first, so stepping through does not wait for the network. */
    private fun prefetchAround(connection: QuestClient, hostId: String, photo: Photo) {
        val photos = _state.value.photos
        val index = photos.indexOfFirst { it.id == photo.id }
        val around = listOfNotNull(photos.getOrNull(index + 1), photos.getOrNull(index - 1))
        prefetch = session.launch {
            for (neighbour in around) {
                try { connection.original(hostId, neighbour) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { break }
            }
            _state.update { it.copy(cacheBytes = cache.usage()) }
        }
    }

    override fun step(direction: Int) {
        val state = _state.value
        val index = state.photos.indexOfFirst { it.id == state.current?.id }
        val target = state.photos.getOrNull(index + direction)
        when {
            target != null -> open(target)
            direction > 0 && state.next != null -> { stepWhenLoaded = true; more() }
        }
    }

    override fun close() {
        opening?.cancel(); prefetch?.cancel(); stepWhenLoaded = false
        _state.update { it.copy(current = null, eyes = null, opening = null, openError = null) }
        client?.viewing(null)
    }

    override fun clearCache() {
        val running = listOfNotNull(opening, prefetch)
        running.forEach { it.cancel() }
        viewModelScope.launch {
            running.joinAll()
            decodeLock.withLock { io { runCatching { cache.clear() } } }
            _state.update { it.copy(cacheBytes = cache.usage()) }
        }
    }

    override fun onCleared() { end(); discovery.close(); runCatching { cache.clear() }; super.onCleared() }

    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }
    private fun main(block: () -> Unit) { viewModelScope.launch { block() } }

    private companion object {
        /** Pauses between reconnect attempts, about two minutes in all. */
        val RETRY_SECONDS = listOf(1, 2, 4, 8, 15, 30, 30, 30)
    }
}
