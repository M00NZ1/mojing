package com.mojing.app.ui.common

import android.content.Context
import coil.request.CachePolicy
import coil.request.ImageRequest
import java.io.File

/** Coil 模型：支持本地路径文件与 http(s) URL；本地文件用路径+修改时间作缓存键，避免换图后仍显示旧缓存。 */
fun avatarImageModel(context: Context, path: String): Any {
    val p = path.trim()
    if (!hasUserImage(p)) return ""
    return if (p.startsWith("http://", ignoreCase = true) || p.startsWith("https://", ignoreCase = true) ||
        p.startsWith("content://", ignoreCase = true) || p.startsWith("android.resource://", ignoreCase = true)) {
        p
    } else {
        val file = File(p)
        val revision = if (file.exists()) file.lastModified() else 0L
        ImageRequest.Builder(context)
            .data(file)
            .memoryCacheKey("${file.absolutePath}#$revision")
            .diskCacheKey("${file.absolutePath}#$revision")
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .crossfade(false)
            .build()
    }
}

/** Retired capture illustrations are not user uploads. Keep the stored value recoverable. */
fun hasUserImage(path: String?): Boolean = !path.isNullOrBlank() && path.trim() !in setOf(
    "android.resource://com.mojing.app/drawable/creation_world_art",
    "android.resource://com.mojing.app/drawable/creation_character_art",
    "android.resource://com.mojing.app/drawable/creation_story_art",
)
