package com.mojing.app.domain.engine

object MacroReplacer {
    /** `{{macro}}` 占位；右花括号必须转义，否则 `}}` 会被解析为非法量词语法 */
    private val macroPattern = Regex("""\{\{\s*(\w+)\s*\}\}""", RegexOption.IGNORE_CASE)

    fun replace(text: String, macros: Map<String, String>): String {
        return macroPattern.replace(text) { match ->
            val key = match.groupValues[1].lowercase()
            macros[key] ?: match.value
        }
    }
}
