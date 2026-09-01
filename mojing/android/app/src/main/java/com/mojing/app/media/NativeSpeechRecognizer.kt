package com.mojing.app.media

import android.content.Context
import android.speech.RecognizerIntent
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

object NativeSpeechRecognizer {
    fun createIntent(): Intent {
        return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "请说话...")
        }
    }

    fun isAvailable(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) ==
               PackageManager.PERMISSION_GRANTED
    }

    /** 是否有应用能处理语音听写（避免在未安装语音服务的机型上 `startActivity` 直接闪退） */
    fun isSpeechRecognitionResolvable(context: Context): Boolean {
        val pm = context.packageManager
        val list = pm.queryIntentActivities(createIntent(), PackageManager.MATCH_DEFAULT_ONLY)
        return list.isNotEmpty()
    }
}
