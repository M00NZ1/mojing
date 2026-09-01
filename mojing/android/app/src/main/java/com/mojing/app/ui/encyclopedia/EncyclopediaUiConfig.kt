package com.mojing.app.ui.encyclopedia

/**
 * 百科相关 UI 开关（与 Web `EncyclopediaPage.tsx` 的 `SHOW_BUILTIN_ENCYCLOPEDIA_NAME_GENERATOR_UI` 对齐）。
 *
 * **内置名称生成器**：马尔可夫 / 东方分层等走后端 `POST /encyclopedia/generate-names`；与条目「AI 补全」、批量新建等 **AI 一键起名/成文** 场景重叠，故入口默认关闭。
 * 当前 Android **未实现**该生成器界面；若将来接入，请先查此开关再渲染入口，避免与 Web 行为分叉。
 */
object EncyclopediaUiConfig {
    const val SHOW_BUILTIN_NAME_GENERATOR_UI: Boolean = false
}
