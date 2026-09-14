package com.mojing.app.ui.chat

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import com.mojing.app.service.GenerateService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Main-thread session ownership. Only visible screens and unfinished requests retain a store. */
internal class RetainedSessionStores(private val scope: CoroutineScope) {
    data class FailureNotice(val token: Long, val message: String)
    private data class Entry(val store: ViewModelStore, val model: ViewModel, var readers: Int)
    private val entries = mutableMapOf<Long, Entry>()
    private val jobs = mutableMapOf<Long, MutableSet<Job>>()
    private val _running = MutableStateFlow<Set<Long>>(emptySet())
    val running = _running.asStateFlow()
    private var nextReplyToken = 0L
    private val replyTokens = mutableMapOf<Long, Long>()
    private val _failures = MutableStateFlow<Map<Long, FailureNotice>>(emptyMap())
    val failures = _failures.asStateFlow()
    fun contains(id: Long) = id in entries

    fun beginReply(id: Long): Long {
        val token = ++nextReplyToken
        replyTokens[id] = token
        _failures.value = _failures.value - id
        return token
    }

    fun finishReply(id: Long, token: Long, message: String?) {
        if (replyTokens[id] != token) return
        replyTokens.remove(id)
        if (entries[id]?.readers?.let { it > 0 } == true || message.isNullOrBlank()) return
        val notices = _failures.value.toMutableMap()
        notices[id] = FailureNotice(token, message.take(500))
        while (notices.size > 32) notices.remove(notices.keys.first())
        _failures.value = notices
    }

    fun dismissFailure(id: Long, token: Long) {
        if (_failures.value[id]?.token == token) _failures.value = _failures.value - id
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : ViewModel> acquire(id: Long, create: (ViewModelStore) -> T): T {
        val entry = entries[id] ?: ViewModelStore().let { store ->
            try { Entry(store, create(store), 0).also { entries[id] = it } }
            catch (failure: Throwable) { store.clear(); throw failure }
        }
        entry.readers++
        return entry.model as T
    }

    fun release(id: Long) {
        entries[id]?.let { it.readers = (it.readers - 1).coerceAtLeast(0) }
        evictIdle(id)
    }

    fun retainJob(id: Long, job: Job, onSettled: () -> Unit = {}) {
        jobs.getOrPut(id) { mutableSetOf() }.add(job)
        _running.value = jobs.keys.toSet()
        job.invokeOnCompletion {
            scope.launch {
                jobs[id]?.let { active -> if (active.remove(job) && active.isEmpty()) jobs.remove(id) }
                _running.value = jobs.keys.toSet()
                evictIdle(id)
                onSettled()
            }
        }
    }

    fun stopAll() { jobs.values.flatMap { it.toList() }.forEach { it.cancel() } }
    fun stop(id: Long) { jobs[id]?.toList()?.forEach { it.cancel() } }

    private fun evictIdle(id: Long) {
        val entry = entries[id] ?: return
        if (entry.readers == 0 && id !in jobs) entries.remove(id)?.store?.clear()
    }
}

internal object RetainedChatSessions {
    val stores = RetainedSessionStores(CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate))
    val running get() = stores.running

    fun retainGeneration(id: Long, job: Job, context: Context, failure: (() -> String?)? = null) {
        if (!stores.contains(id)) return
        val app = context.applicationContext
        val wasIdle = running.value.isEmpty()
        val token = if (failure != null) stores.beginReply(id) else null
        stores.retainJob(id, job) {
            if (token != null) stores.finishReply(id, token, failure?.invoke())
            if (running.value.isEmpty()) runCatching { app.stopService(Intent(app, GenerateService::class.java)) }
        }
        if (wasIdle) runCatching {
            ContextCompat.startForegroundService(app, Intent(app, GenerateService::class.java))
        }
    }
}
