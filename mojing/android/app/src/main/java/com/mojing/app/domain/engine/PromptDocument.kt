package com.mojing.app.domain.engine

/** Source-labelled complete blocks. Unclassified/user-authored material is protected by default. */
data class PromptBlock(val text: String, val kind: Kind = Kind.PROTECTED, val separator: String = "\n\n") {
    enum class Kind { PROTECTED, AUTOMATIC_SUMMARY }
}

data class PromptDocument(val blocks: List<PromptBlock>) {
    fun render(): String = blocks.mapIndexed { index, block -> (if (index == 0) "" else block.separator) + block.text }.joinToString("")
    fun mapText(transform: (String) -> String): PromptDocument = copy(blocks = blocks.map { it.copy(text = transform(it.text)) })
    fun appendProtected(text: String, separator: String = "\n\n"): PromptDocument = copy(blocks = blocks + PromptBlock(text, separator = separator))

    companion object {
        fun protected(text: String): PromptDocument = PromptDocument(listOf(PromptBlock(text)))
    }
}

/** Used only by PromptBuilder, keeping the old rendering order and one assembly implementation. */
internal class PromptParts {
    private val blocks = mutableListOf<PromptBlock>()
    fun add(text: String) { blocks.add(PromptBlock(text)) }
    fun addSummary(text: String) { blocks.add(PromptBlock(text, PromptBlock.Kind.AUTOMATIC_SUMMARY)) }
    fun addDocument(document: PromptDocument) { blocks.addAll(document.blocks) }
    fun document(): PromptDocument = PromptDocument(blocks.toList())
}
