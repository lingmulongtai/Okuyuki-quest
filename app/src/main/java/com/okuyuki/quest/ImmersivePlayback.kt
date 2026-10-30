package com.okuyuki.quest

import com.okuyuki.protocol.PanoramaVideoShare
import com.okuyuki.protocol.OsmoStabilization
import com.okuyuki.quest.link.QuestClient

/** In-process, one-shot handoff: pairing credentials never enter an Intent. */
internal object ImmersivePlayback {
    data class Connection(val client: QuestClient, val share: PanoramaVideoShare, val stabilization: OsmoStabilization? = null)
    private var pending: Connection? = null
    @Synchronized fun install(connection: Connection) { pending?.client?.close(); pending = connection }
    @Synchronized fun take(): Connection? = pending.also { pending = null }
    @Synchronized fun cancel() { pending?.client?.close(); pending = null }
}
