package com.okuyuki.quest.link

import com.okuyuki.protocol.OsmoMotionCodec
import com.okuyuki.protocol.OsmoMotionFrame
import com.okuyuki.protocol.OsmoMotionTrack
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
    @Test fun `a socket ended for a removed pairing is told apart from a lost phone`() = runBlocking {
        fun upgrade(code: Int) = MockResponse().withWebSocketUpgrade(object : okhttp3.WebSocketListener() {
            override fun onOpen(webSocket: okhttp3.WebSocket, response: okhttp3.Response) { webSocket.close(code, null) }
        })
        for ((response, revoked) in listOf(upgrade(1008) to true, upgrade(1001) to false, MockResponse().setResponseCode(401) to true)) {
            MockWebServer().use { server ->
                server.enqueue(response)
                server.start()
                val ended = kotlinx.coroutines.CompletableDeferred<Boolean>()
                QuestClient(Endpoint(server.hostName, server.port), TemporaryPhotoCache(folder.newFolder())).use { client ->
                    client.events(this, {}, { ended.complete(it) })
                    assertEquals(revoked, withTimeout(2000) { ended.await() })
                }
            }
        }
    }
    @Test fun `a refused saved permission is reported apart from other failures`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401))
            server.enqueue(MockResponse().setResponseCode(500))
            server.start()
            QuestClient(Endpoint(server.hostName, server.port), TemporaryPhotoCache(folder.newFolder())).use { client ->
                client.token = "removed-token"
                assertTrue(runCatching { client.library() }.exceptionOrNull() is NotAllowedException)
                val other = runCatching { client.library() }.exceptionOrNull()
                assertTrue(other != null && other !is NotAllowedException)
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
    @Test fun `legacy phone video metadata falls back to v2`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setResponseCode(404))
            server.enqueue(MockResponse().setBody("""{"id":"0123456789abcdef0123456789abcdef","sizeBytes":10}"""))
            QuestClient(Endpoint(server.hostName, server.port), TemporaryPhotoCache(folder.newFolder())).use { client ->
                client.token = "paired-token"
                assertEquals(PanoramaVideoFormat.EQUIRECTANGULAR_MP4, client.panoramaVideo()!!.format)
                assertEquals("/v3/panorama/video", server.takeRequest().path)
                val legacy = server.takeRequest()
                assertEquals("/v2/panorama/video", legacy.path)
                assertEquals("Bearer paired-token", legacy.getHeader("Authorization"))
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
    @Test fun `motion download is bounded authenticated and backward compatible`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val share = PanoramaVideoShare("0123456789abcdef0123456789abcdef", 10, PanoramaVideoFormat.OSMO_OSV)
            val motion = OsmoMotionTrack(listOf(OsmoMotionFrame(0, listOf(0f, 0f, 0f, 1f))), 20000)
            val encoded = OsmoMotionCodec.encode(motion)
            server.enqueue(MockResponse().setResponseCode(404))
            server.enqueue(MockResponse().setBody(Buffer().write(encoded)))
            server.enqueue(MockResponse().setBody(Buffer().write(encoded.copyOf(encoded.size - 1))))
            server.enqueue(MockResponse().setHeader("Content-Length", OsmoMotionCodec.MAX_BYTES + 1))
            server.enqueue(MockResponse().setResponseCode(401))
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", server.url("/leak")))
            QuestClient(Endpoint(server.hostName, server.port), TemporaryPhotoCache(folder.newFolder())).use { client ->
                client.token = "paired-token"
                assertNull(client.panoramaVideoMotion(share))
                assertEquals(motion, client.panoramaVideoMotion(share))
                repeat(4) { assertTrue(runCatching { client.panoramaVideoMotion(share) }.isFailure) }
                repeat(6) {
                    val request = server.takeRequest()
                    assertEquals("/v3/panorama/video/${share.id}/motion", request.path)
                    assertEquals("Bearer paired-token", request.getHeader("Authorization"))
                }
                assertTrue(runCatching { client.panoramaVideoMotion(share.copy(id = "../1")) }.isFailure)
                assertEquals(6, server.requestCount)
            }
        }
    }
    @Test fun `motion download can be cancelled while the body is stalled`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val share = PanoramaVideoShare("0123456789abcdef0123456789abcdef", 10, PanoramaVideoFormat.OSMO_LRF)
            val motion = OsmoMotionCodec.encode(OsmoMotionTrack(listOf(OsmoMotionFrame(0, listOf(0f, 0f, 0f, 1f))), 40000))
            // Keep the response stalled past the cancellation timeout, but below MockWebServer's shutdown deadline.
            server.enqueue(MockResponse().setBody(Buffer().write(motion)).setBodyDelay(3, java.util.concurrent.TimeUnit.SECONDS))
            QuestClient(Endpoint(server.hostName, server.port), TemporaryPhotoCache(folder.newFolder())).use { client ->
                val task = launch { client.panoramaVideoMotion(share) }
                delay(200); withTimeout(2000) { task.cancelAndJoin() }
                assertTrue(task.isCancelled)
            }
        }
    }
    @Test fun `long motion can be decoded from a chunked response`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val share = PanoramaVideoShare("0123456789abcdef0123456789abcdef", 10, PanoramaVideoFormat.OSMO_OSV)
            val builder = com.okuyuki.protocol.PackedMotionFrames.Builder(50_001)
            repeat(50_001) { builder.add(it * 20_000L, 0f, 0f, 0f, 1f) }
            val motion = OsmoMotionTrack(builder.build(), 20_000)
            server.enqueue(MockResponse().setChunkedBody(Buffer().write(OsmoMotionCodec.encode(motion)), 4096))
            QuestClient(Endpoint(server.hostName, server.port), TemporaryPhotoCache(folder.newFolder())).use { client ->
                val received = client.panoramaVideoMotion(share)!!
                assertEquals(50_001, received.frames.size)
                assertEquals(1_000_000_000L, received.frames.last().presentationTimeUs)
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
