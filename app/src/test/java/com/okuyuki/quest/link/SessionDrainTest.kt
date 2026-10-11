package com.okuyuki.quest.link

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class SessionDrainTest {
    @Test fun `rapid reconnect preserves a blocked older transfer barrier`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val release = CompletableDeferred<Unit>()
        try {
            val started = CompletableDeferred<Unit>()
            val old = scope.launch { withContext(NonCancellable) { started.complete(Unit); release.await() } }
            started.await(); old.cancel()
            val drain = SessionDrain(scope)
            val first = drain.after(old)
            val abandonedConnection = scope.launch { first.join() }
            abandonedConnection.cancelAndJoin()
            val second = drain.after(abandonedConnection)
            val connected = scope.async { second.join(); true }
            assertNull(withTimeoutOrNull(150) { connected.await() })
            release.complete(Unit)
            assertTrue(withTimeout(3000) { connected.await() })
            assertTrue(old.isCompleted)
        } finally { release.complete(Unit); scope.cancel() }
    }

    @Test fun `repeated stop cleanup finishes before a new connection`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val release = CompletableDeferred<Unit>()
        try {
            val drain = SessionDrain(scope)
            val started = CompletableDeferred<Unit>()
            val order = CopyOnWriteArrayList<String>()
            val firstClear = drain.after { started.complete(Unit); release.await(); order.add("first") }
            started.await()
            val secondClear = drain.after { order.add("second") }
            val ready = drain.after()
            assertNull(withTimeoutOrNull(150) { ready.join(); true })
            release.complete(Unit)
            withTimeout(3000) { ready.join() }
            assertTrue(firstClear.isCompleted)
            assertTrue(secondClear.isCompleted)
            assertEquals(listOf("first", "second"), order.toList())
        } finally { release.complete(Unit); scope.cancel() }
    }
}
