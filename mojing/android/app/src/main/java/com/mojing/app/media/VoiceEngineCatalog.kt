package com.mojing.app.media

import android.content.Context
import android.content.Intent
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import java.util.Locale

data class VoiceEngineOption(val id: String, val name: String)

data class VoiceOption(
    val id: String,
    val name: String,
    val languageTag: String = "",
)

data class VoiceCatalogResult(val voices: List<VoiceOption>, val defaultEnginePackage: String?)

internal class VoiceCatalogException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

/** Lists installed Android TTS engines and their voices without sharing a playback instance. */
object VoiceEngineCatalog {
    private const val QUERY_TIMEOUT_MS = 8_000L
    private const val ANDROID_PREFIX = "android:"

    suspend fun engines(context: Context): List<VoiceEngineOption> = withContext(Dispatchers.IO) {
        val packageManager = context.applicationContext.packageManager
        val services = packageManager.queryIntentServices(
            Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE),
            0,
        )
        buildList {
            add(VoiceEngineOption("system", "系统默认"))
            services
                .map { it.serviceInfo }
                .distinctBy { it.packageName }
                .sortedBy { it.loadLabel(packageManager).toString() }
                .forEach { info ->
                    val label = info.loadLabel(packageManager).toString().trim()
                    add(VoiceEngineOption("$ANDROID_PREFIX${info.packageName}", label.ifBlank { info.packageName }))
                }
        }
    }

    suspend fun voices(context: Context, engineId: String): VoiceCatalogResult {
        val packageName = when {
            engineId == "system" -> null
            engineId.startsWith(ANDROID_PREFIX) -> engineId.removePrefix(ANDROID_PREFIX).trim().takeIf { it.isNotEmpty() }
                ?: throw IllegalArgumentException("请选择有效的朗读引擎")
            else -> throw IllegalArgumentException("不支持的系统朗读引擎")
        }
        val result = try {
            withTimeout(QUERY_TIMEOUT_MS) {
                queryVoices(context.applicationContext, packageName)
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw VoiceCatalogException("读取音色超时，请检查所选引擎后重试", e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: VoiceCatalogException) {
            throw e
        } catch (e: Exception) {
            throw VoiceCatalogException("无法读取所选朗读引擎的音色，请检查引擎是否可用", e)
        }
        return result.copy(voices = normalizeVoiceOptions(result.voices))
    }

    private suspend fun queryVoices(context: Context, packageName: String?): VoiceCatalogResult {
        if (packageName != null) {
            val available = withContext(Dispatchers.IO) {
                context.packageManager.resolveService(
                    Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE).setPackage(packageName),
                    0,
                )?.serviceInfo != null
            }
            if (!available) {
                throw VoiceCatalogException("指定的系统朗读引擎未安装或不可用，请重新选择")
            }
        }
        val engine = withContext(Dispatchers.Main.immediate) { createQueryEngine(context, packageName) }
            ?: throw VoiceCatalogException("朗读引擎初始化失败，无法读取音色列表")
        return try {
            withContext(Dispatchers.IO) {
                VoiceCatalogResult(
                    engine.voices.orEmpty()
                        .filter { it.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) != true }
                        .map { it.toOption() },
                    runCatching { engine.defaultEngine }.getOrNull(),
                )
            }
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { engine.shutdown() }
        }
    }

    private suspend fun createQueryEngine(context: Context, packageName: String?): TextToSpeech? =
        kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
            var engine: TextToSpeech? = null
            val handler = android.os.Handler(android.os.Looper.getMainLooper())
            val delivered = java.util.concurrent.atomic.AtomicBoolean(false)
            val callback: (Int) -> Unit = { status ->
                handler.post {
                    if (delivered.compareAndSet(false, true)) {
                        val current = engine
                        if (!continuation.isActive) current?.shutdown()
                        else if (status != TextToSpeech.SUCCESS) {
                            current?.shutdown()
                            continuation.resumeWithException(VoiceCatalogException("朗读引擎初始化失败，无法读取音色列表"))
                        } else continuation.resume(current) { _, resource, _ -> resource?.shutdown() }
                    }
                }
            }
            try {
                engine = if (packageName == null) TextToSpeech(context, callback)
                    else TextToSpeech(context, callback, packageName)
                continuation.invokeOnCancellation { engine?.shutdown() }
            } catch (e: Exception) {
                engine?.shutdown()
                if (delivered.compareAndSet(false, true) && continuation.isActive) {
                    continuation.resumeWithException(VoiceCatalogException("朗读引擎初始化失败，无法读取音色列表", e))
                }
            }
        }

    private fun Voice.toOption(): VoiceOption {
        val localeName = locale.getDisplayName(Locale.CHINESE).trim()
        val label = if (localeName.isBlank()) name else "$name · $localeName"
        return VoiceOption(name, label, locale.toLanguageTag())
    }
}

/** System TTS implementations may expose several Voice rows with the same selectable name. */
internal fun normalizeVoiceOptions(options: Iterable<VoiceOption>): List<VoiceOption> =
    options
        .filter { it.id.isNotBlank() }
        .distinctBy { it.id }
        .sortedWith(compareBy<VoiceOption> { it.name }.thenBy { it.id })
