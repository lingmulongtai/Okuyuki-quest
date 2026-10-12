package com.okuyuki.quest.link

import kotlinx.serialization.SerializationException
import java.io.IOException
import java.net.SocketTimeoutException

/** A failure the person can act on; its message is shown as is. [code] is the phone's HTTP status, when there was one. */
class LinkError(message: String, val code: Int = 0) : IOException(message) {
    val unauthorized get() = code == 401
}

/** A failure in words for the person: the phone's or this app's own message, or what to check when the network failed. */
fun describe(error: Throwable): String = when (error) {
    is LinkError -> sentence(error.message.orEmpty())
    is SocketTimeoutException -> "スマホから返事がありません。スマホで Okuyuki が開いているか確かめてください。"
    is IOException -> "スマホにつながりませんでした。スマホの「接続を待つ」がオンで、Quest と同じ Wi-Fi にいるか確かめてください。"
    is SerializationException -> "スマホの Okuyuki を最新にしてください。"
    else -> "うまくいきませんでした。もう一度試してください。"
}

/** Ends [text] the way this app's messages end, with 。, also when it came from an older phone app that leaves it off. */
fun sentence(text: String): String = text.trimEnd().let { if (it.isEmpty() || it.last() in "。．.！!？?…") it else "$it。" }
