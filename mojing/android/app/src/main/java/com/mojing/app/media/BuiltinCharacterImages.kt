package com.mojing.app.media

import android.content.Context
import java.io.File
import java.io.FileOutputStream

/** 一项内置图：展示名 + `assets/character_presets/` 内文件名。 */
data class BuiltinPresetCharacterImage(val label: String, val assetFileName: String)

object BuiltinCharacterImages {
    const val PRESET_ASSET_DIR = "character_presets"

    private val imageExtensions = setOf("png", "jpg", "jpeg", "webp")

    /**
     * 扫描 [PRESET_ASSET_DIR] 下所有常见图片后缀，按文件名排序。
     * 你把图放进 `android/app/src/main/assets/character_presets/` 即可出现在选图里。
     */
    fun listLocalPresets(context: Context): List<BuiltinPresetCharacterImage> = runCatching {
        val names = context.assets.list(PRESET_ASSET_DIR) ?: return@runCatching emptyList()
        names
            .asSequence()
            .filter { name ->
                val ext = name.substringAfterLast('.', "").lowercase()
                ext in imageExtensions
            }
            .sorted()
            .map { name ->
                val label = name.substringBeforeLast('.').replace('_', ' ')
                BuiltinPresetCharacterImage(label = label, assetFileName = name)
            }
            .toList()
    }.getOrElse { emptyList() }

    fun presetAssetUri(assetFileName: String): String =
        "file:///android_asset/$PRESET_ASSET_DIR/$assetFileName"

    /** 复制到应用私有目录，供头像/封面路径与 [com.mojing.app.ui.common.avatarImageModel] 使用。 */
    fun copyPresetAssetToFilesDir(context: Context, assetFileName: String): String? = runCatching {
        val rel = "$PRESET_ASSET_DIR/$assetFileName"
        val bytes = context.assets.open(rel).use { it.readBytes() }
        val ext = assetFileName.substringAfterLast('.', "png")
        val f = File(context.filesDir, "character_preset_${System.currentTimeMillis()}.$ext")
        FileOutputStream(f).use { it.write(bytes) }
        f.absolutePath
    }.getOrNull()
}
