package com.mojing.app.ui.common

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import coil.request.CachePolicy
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private const val RETIRED_CREATION_WORLD = "android.resource://com.mojing.app/drawable/creation_world_art"
private const val RETIRED_CREATION_CHARACTER = "android.resource://com.mojing.app/drawable/creation_character_art"
private const val RETIRED_CREATION_STORY = "android.resource://com.mojing.app/drawable/creation_story_art"

internal sealed interface AvatarImagePath {
    data object Empty : AvatarImagePath
    data object Retired : AvatarImagePath
    data class Immediate(val value: String) : AvatarImagePath
    data class Local(val value: String) : AvatarImagePath
}

internal fun classifyAvatarImagePath(path: String): AvatarImagePath {
    val value = path.trim()
    if (value.isBlank()) return AvatarImagePath.Empty
    if (value == RETIRED_CREATION_WORLD || value == RETIRED_CREATION_CHARACTER || value == RETIRED_CREATION_STORY) {
        return AvatarImagePath.Retired
    }
    if (value.startsWith("http://", ignoreCase = true) || value.startsWith("https://", ignoreCase = true) ||
        value.startsWith("content://", ignoreCase = true) || value.startsWith("android.resource://", ignoreCase = true)
    ) return AvatarImagePath.Immediate(value)
    return AvatarImagePath.Local(value)
}

internal fun buildAvatarImageRequest(context: Context, path: String, revision: Long): ImageRequest {
    val file = File(path)
    return ImageRequest.Builder(context)
        .data(file)
        .memoryCacheKey(avatarImageCacheKey(file, revision))
        .diskCacheKey(avatarImageCacheKey(file, revision))
        .memoryCachePolicy(CachePolicy.ENABLED)
        .diskCachePolicy(CachePolicy.ENABLED)
        .crossfade(false)
        .build()
}

internal fun avatarImageCacheKey(file: File, revision: Long): String = "${file.absolutePath}#$revision"

internal suspend fun readAvatarImageFileRevision(file: File): Long = withContext(Dispatchers.IO) {
    if (file.exists()) file.lastModified() else 0L
}

internal suspend fun resolveAvatarImageModel(context: Context, path: String): Any {
    return when (val classified = classifyAvatarImagePath(path)) {
        AvatarImagePath.Empty, AvatarImagePath.Retired -> ""
        is AvatarImagePath.Immediate -> classified.value
        is AvatarImagePath.Local -> {
            val file = File(classified.value)
            val revision = readAvatarImageFileRevision(file)
            buildAvatarImageRequest(context, classified.value, revision)
        }
    }
}

/** Returns immediate URI models and resolves local-file metadata off the UI thread. */
@Composable
fun rememberAvatarImageModel(context: Context, path: String, retryRevision: Int = 0): Any? {
    val value = path.trim()
    return key(context, value, retryRevision) {
        val initial = when (val classified = classifyAvatarImagePath(value)) {
            AvatarImagePath.Empty, AvatarImagePath.Retired -> ""
            is AvatarImagePath.Immediate -> classified.value
            is AvatarImagePath.Local -> null
        }
        var model by remember { mutableStateOf<Any?>(initial) }
        LaunchedEffect(context, value, retryRevision) {
            model = resolveAvatarImageModel(context, value)
        }
        model
    }
}

/** Retired capture illustrations are not user uploads. Keep the stored value recoverable. */
fun hasUserImage(path: String?): Boolean = when (classifyAvatarImagePath(path.orEmpty())) {
    AvatarImagePath.Empty, AvatarImagePath.Retired -> false
    is AvatarImagePath.Immediate, is AvatarImagePath.Local -> true
}
