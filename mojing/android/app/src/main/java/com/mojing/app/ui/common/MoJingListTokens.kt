package com.mojing.app.ui.common

import androidx.compose.ui.unit.dp

/** 普通列表卡片；头像与世界封面使用不同尺寸，旧分割缩进仅兼容历史调用。 */
object MoJingListTokens {
    val rowStart = 16.dp
    val rowEnd = 12.dp
    val avatar = 48.dp
    val worldCover = 72.dp
    val cardGap = 12.dp
    val cardInset = 12.dp
    val gapAfterAvatar = 12.dp
    val dividerInset = rowStart + avatar + gapAfterAvatar
}
