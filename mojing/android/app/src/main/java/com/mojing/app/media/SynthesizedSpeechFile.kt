package com.mojing.app.media

import java.io.File

/** One provider-owned audio segment handed to the caller after synthesis succeeds. */
data class SynthesizedSpeechFile(
    val file: File,
    val mimeType: String,
)
