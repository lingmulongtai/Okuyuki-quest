package com.okuyuki.quest.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import com.okuyuki.quest.Link
import com.okuyuki.quest.Nearby
import com.okuyuki.quest.QuestActions
import com.okuyuki.quest.QuestState
import com.okuyuki.quest.Thumbnails
import com.okuyuki.quest.window.StereoSurface
import com.okuyuki.stereo.StereoPair

/** Draws a decoded pair of eyes at the given size and reports whether it is shown in stereo. Tests draw a stand-in. */
typealias StereoContent = @Composable (eyes: StereoPair, modifier: Modifier, onMode: (Boolean) -> Unit) -> Unit

private enum class Screen { CONNECT, CONNECTING, PAIRING, LIBRARY, VIEWER, SETTINGS }

/** The whole app for one state, with no other inputs, so every screen can be drawn and checked on its own. */
@Composable
fun QuestScreen(state: QuestState, nearby: List<Nearby>, discoveryError: String?, actions: QuestActions, thumbnails: Thumbnails,
    stereo: StereoContent = { eyes, modifier, onMode -> StereoSurface(eyes, modifier, onMode) }) {
    var settings by rememberSaveable { mutableStateOf(false) }
    val grid = rememberLazyGridState()
    val connected = state.link == Link.CONNECTED || state.link == Link.RECONNECTING || state.link == Link.LOST
    val screen = when {
        settings -> Screen.SETTINGS
        connected && state.current != null -> Screen.VIEWER
        connected -> Screen.LIBRARY
        state.link == Link.PAIRING -> Screen.PAIRING
        state.link == Link.CONNECTING -> Screen.CONNECTING
        else -> Screen.CONNECT
    }
    BackHandler(screen == Screen.SETTINGS) { settings = false }
    BackHandler(screen == Screen.VIEWER) { actions.close() }
    BackHandler(screen == Screen.PAIRING || screen == Screen.CONNECTING) { actions.cancel() }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Crossfade(screen, animationSpec = tween(200), label = "screen") { shown ->
            when (shown) {
                Screen.SETTINGS -> SettingsScreen(state, actions, back = { settings = false })
                Screen.VIEWER -> ViewerScreen(state, actions, thumbnails, stereo)
                Screen.LIBRARY -> LibraryScreen(state, actions, thumbnails, grid, openSettings = { settings = true })
                Screen.PAIRING -> PairingScreen(state, actions)
                Screen.CONNECTING -> ConnectingScreen(state, actions)
                Screen.CONNECT -> ConnectScreen(state, nearby, discoveryError, actions, openSettings = { settings = true })
            }
        }
    }
}
