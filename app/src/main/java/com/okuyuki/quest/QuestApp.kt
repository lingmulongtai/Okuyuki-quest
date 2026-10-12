package com.okuyuki.quest

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.okuyuki.quest.ui.OkuyukiTheme
import com.okuyuki.quest.ui.QuestScreen

@Composable
fun QuestApp(model: QuestViewModel) {
    val state by model.state.collectAsStateWithLifecycle()
    val nearby by model.nearby.collectAsStateWithLifecycle()
    val discoveryError by model.discoveryError.collectAsStateWithLifecycle()
    OkuyukiTheme { QuestScreen(state, nearby, discoveryError, actions = model, thumbnails = model) }
}
