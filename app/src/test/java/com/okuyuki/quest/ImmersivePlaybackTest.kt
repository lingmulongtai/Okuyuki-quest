package com.okuyuki.quest

import com.okuyuki.protocol.PanoramaVideoShare
import com.okuyuki.protocol.OsmoMotionFrame
import com.okuyuki.protocol.OsmoMotionTrack
import com.okuyuki.protocol.OsmoStabilization
import com.okuyuki.quest.link.Endpoint
import com.okuyuki.quest.link.QuestClient
import com.okuyuki.quest.link.TemporaryPhotoCache
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ImmersivePlaybackTest {
    @get:Rule val folder = TemporaryFolder()
    @Test fun `handoff is one shot and cancellation removes pending connection`() {
        val share = PanoramaVideoShare("0123456789abcdef0123456789abcdef", 10)
        fun connection() = ImmersivePlayback.Connection(QuestClient(Endpoint("127.0.0.1", 12345), TemporaryPhotoCache(folder.newFolder())), share)
        val correction = OsmoStabilization(OsmoMotionTrack(listOf(OsmoMotionFrame(0, listOf(0f, 0f, 0f, 1f))), 40000))
        val first = connection().copy(stabilization = correction)
        try {
            ImmersivePlayback.install(first)
            val handoff = ImmersivePlayback.take()
            assertSame(first, handoff)
            assertSame(correction, handoff?.stabilization)
            assertNull(ImmersivePlayback.take())
            ImmersivePlayback.install(connection())
            ImmersivePlayback.cancel()
            assertNull(ImmersivePlayback.take())
        } finally { first.client.close(); ImmersivePlayback.cancel() }
    }
}
