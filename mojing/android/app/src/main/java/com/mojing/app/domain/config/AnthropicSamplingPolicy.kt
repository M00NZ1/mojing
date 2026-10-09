package com.mojing.app.domain.config

/** Native Messages only; OpenAI gateways retain their own parameter contract. */
object AnthropicSamplingPolicy {
    private val versionedModel = Regex(
        "^claude-(?:opus|sonnet|haiku|mythos|fable)-([0-9]{1,2})(?:-([0-9]{1,2}))?(?:-(?:[0-9]{8}|latest))?$",
    )

    fun acceptsTemperature(model: String): Boolean {
        val id = model.trim().lowercase()
        // https://platform.claude.com/docs/en/build-with-claude/working-with-messages
        // Claude 4.7+ and Mythos Preview require sampling parameters to be omitted.
        if (id == "claude-mythos-preview") return false
        val match = versionedModel.matchEntire(id) ?: return true
        val major = match.groupValues[1].toInt()
        val minor = match.groupValues[2].toIntOrNull() ?: 0
        return major < 4 || (major == 4 && minor < 7)
    }
}
