package com.mojing.app.ui.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Small in-memory owner for keyed operations; it is deliberately not a persisted queue/state machine. */
internal class KeyedOperationOwner<K> {
    private val lock = Any()
    private val _activeKeys = MutableStateFlow<Set<K>>(emptySet())
    val activeKeys: StateFlow<Set<K>> = _activeKeys.asStateFlow()

    fun tryStart(key: K): Boolean = synchronized(lock) {
        if (key in _activeKeys.value) return false
        _activeKeys.value = _activeKeys.value + key
        true
    }

    fun finish(key: K) = synchronized(lock) {
        _activeKeys.value = _activeKeys.value - key
    }
}
