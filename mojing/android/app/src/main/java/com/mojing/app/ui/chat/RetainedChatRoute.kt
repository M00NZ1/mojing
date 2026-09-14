package com.mojing.app.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.HiltViewModelFactory
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.NavBackStackEntry

@Composable
internal fun retainedChatViewModel(entry: NavBackStackEntry, sessionId: Long): ChatViewModel {
    val context = LocalContext.current
    val model = remember(entry) {
        RetainedChatSessions.stores.acquire(sessionId) { store ->
            ViewModelProvider(store, HiltViewModelFactory(context, entry), entry.defaultViewModelCreationExtras)[ChatViewModel::class.java]
        }
    }
    DisposableEffect(entry) { onDispose { RetainedChatSessions.stores.release(sessionId) } }
    return model
}
