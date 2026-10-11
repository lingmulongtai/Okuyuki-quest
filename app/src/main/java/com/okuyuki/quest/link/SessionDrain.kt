package com.okuyuki.quest.link

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Called by the UI thread; barriers belong to the view model, never to a connection. */
class SessionDrain(private val scope: CoroutineScope) {
    private var tail: Job? = null
    fun after(session: Job? = null, cleanup: suspend () -> Unit = {}): Job {
        val previous = tail
        return scope.launch(Dispatchers.IO) {
            previous?.join()
            session?.join()
            cleanup()
        }.also { tail = it }
    }
}
