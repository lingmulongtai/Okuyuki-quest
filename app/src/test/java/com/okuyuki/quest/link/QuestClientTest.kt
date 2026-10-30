package com.okuyuki.quest.link

import com.okuyuki.protocol.Photo
import com.okuyuki.protocol.PanoramaPhotoShare
import com.okuyuki.protocol.PanoramaVideoShare
import com.okuyuki.protocol.PanoramaVideoFormat
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class QuestClientTest {
    @get:Rule val folder = TemporaryFolder()
    @Test fun `server websocket close promptly ends the session exactly once`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().withWebSocketUpgrade(object : okhttp3.WebSocketListener() {
                override fun onOpen(webSocket: okhttp3.WebSocket, response: okhttp3.Response) {
                    webSocket.close(1000, "session ended")
                }
            }))
            server.start()
            val ended = kotlinx.coroutines.CompletableDeferred<Unit>()
            val calls = java.util.concurrent.atomic.AtomicInteger()
            QuestClient(Endpoint(server.hostName, server.port), TemporaryPhotoCache(folder.newFolder())).use { client ->
                client.events(this, {}, { calls.incrementAndGet(); ended.complete(Unit) })
                withTimeout(2000) { ended.await() }
                delay(200)
                assertEquals(1, calls.get())
            }
        }
    }
    @Test fun `cancel a panorama metadata request while the body is stalled`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody("{}").setBodyDelay(5, java.util.concurrent.TimeUnit.SECONDS))
            QuestClient(Endpoint(server.hostName, server.port), TemporaryPhotoCache(folder.newFolder())).use { client ->
                val task = launch { client.panoramaPhoto() }
                delay(200)
                withTimeout(2000) { task.cancelAndJoin() }
                assertTrue(task.isCancelled)
            }
        }
    }
    @Test fun `panorama photo uses bearer authentication and rejects invalid or truncated data`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val share = PanoramaPhotoShare("0123456789abcdef0123456789abcdef", 4)
            server.enqueue(MockResponse().setResponseCode(404))
            server.enqueue(MockResponse().setBody("""{"id":"${share.id}","sizeBytes":4}"""))
            server.enqueue(MockResponse().setBody(Buffer().write(byteArrayOf(1, 2, 3, 4))))
            server.enqueue(MockResponse().setBody(Buffer().write(byteArrayOf(1, 2, 3))))
            QuestClient(Endpoint(server.hostName, server.port), TemporaryPhotoCache(folder.newFolder())).use { client ->
                client.token = "paired-token"
                assertNull(client.panoramaPhoto())
                assertEquals(share, client.panoramaPhoto())
                assertArrayEquals(byteArrayOf(1, 2, 3, 4), client.panoramaPhotoBytes(share))
                assertTrue(runCatching { client.panoramaPhotoBytes(share) }.isFailure)
                repeat(4) { assertEquals("Bearer paired-token", server.takeRequest().getHeader("Authorization")) }
                assertTrue(runCatching { client.panoramaPhotoBytes(share.copy(id = "../1")) }.isFailure)
                assertEquals(4, server.requestCount)
            }
        }
    }
    @Test fun `video metadata uses bearer authentication and validates shares`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setResponseCode(404))
            server.enqueue(MockResponse().setResponseCode(404))
            server.enqueue(MockResponse().setBody("""{"id":"0123456789abcdef0123456789abcdef","sizeBytes":5368709120}"""))
            server.enqueue(MockResponse().setBody("""{"id":"../video","sizeBytes":10}"""))
            QuestClient(Endpoint(server.hostName, server.port), TemporaryPhotoCache(folder.newFolder())).use { client ->
                client.token = "paired-token"
                assertNull(client.panoramaVideo())
                assertEquals(PanoramaVideoShare("0123456789abcdef0123456789abcdef", 5368709120), client.panoramaVideo())
                assertTrue(runCatching { client.panoramaVideo() }.isFailure)
                repeat(4) { assertEquals("Bearer paired-token", server.takeRequest().getHeader("Authorization")) }
            }
        }
    }
    @Test fun `raw video metadata is available without exposing it to legacy clients`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody("""{"id":"0123456789abcdef0123456789abcdef","sizeBytes":10,"format":"OSMO_OSV"}"""))
            QuestClient(Endpoint(server.hostName, server.port), TemporaryPhotoCache(folder.newFolder())).use { client ->
                client.token = "paired-token"
                assertEquals(PanoramaVideoFormat.OSMO_OSV, client.panoramaVideo()!!.format)
                val request = server.takeRequest()
                assertEquals("/v3/panorama/video", request.path)
                assertEquals("Bearer paired-token", request.getHeader("Authorization"))
            }
        }
    }
    @Test fun `download resumes at the exact offset and carries the pairing token`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val bytes = ByteArray(100) { it.toByte() }
            val cache = TemporaryPhotoCache(folder.newFolder())
            val photo = Photo("1", "photo.heic", 123, 4000, 3000, 100)
            cache.partial("phone:1:123:100").writeBytes(bytes.copyOfRange(0, 40))
            server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 40-99/100").setBody(Buffer().write(bytes.copyOfRange(40, 100))))
            QuestClient(Endpoint(server.hostName, server.port), cache).use { client ->
                client.token = "paired-token"
                assertArrayEquals(bytes, client.original("phone", photo).readBytes())
                val request = server.takeRequest()
                assertEquals("bytes=40-", request.getHeader("Range"))
                assertEquals("Bearer paired-token", request.getHeader("Authorization"))
                assertArrayEquals(bytes, client.original("phone", photo).readBytes())
                assertEquals(1, server.requestCount)
            }
        }
    }
    @Test fun `mismatched partial responses never become completed photos`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val cache = TemporaryPhotoCache(folder.newFolder())
            val photo = Photo("1", "photo.heic", 1, 4000, 3000, 100)
            cache.partial("phone:1:1:100").writeBytes(ByteArray(20))
            server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 21-99/100").setBody("bad"))
            QuestClient(Endpoint(server.hostName, server.port), cache).use { client ->
                assertTrue(runCatching { client.original("phone", photo) }.isFailure)
                assertNull(cache.get("phone:1:1:100"))
            }
        }
    }
}
