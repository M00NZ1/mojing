package com.mojing.app.ui.common

import androidx.compose.ui.unit.dp

/** 会话式列表：与微信/TG 接近的边距与分割缩进（头像 + 间距后画分割线）。 */
object MoJingListTokens {
    val rowStart = 12.dp
    val rowEnd = 12.dp
    val avatar = 50.dp
    val gapAfterAvatar = 12.dp
    val dividerInset = rowStart + avatar + gapAfterAvatar
}
