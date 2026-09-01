package com.mojing.app.domain.engine

object UniversalContextMemoryFormatter {
    fun format(memory: UniversalContextMemory?): String {
        if (memory == null || memory.isEmpty()) return ""
        val parts = mutableListOf<String>()
        parts.add("【通用高密度剧情记忆】\n以下内容是当前会话的连续性记忆。你必须优先遵守，不得与其冲突。")
        memory.globalSummary.trim().takeIf { it.isNotEmpty() }?.let { parts.add("【全局摘要】\n$it") }
        formatUserState(memory.userState).takeIf { it.isNotBlank() }?.let { parts.add("【用户状态】\n$it") }
        formatCharacterStates(memory.characterStates).takeIf { it.isNotBlank() }?.let { parts.add("【角色状态】\n$it") }
        formatRelationshipStates(memory.relationshipStates).takeIf { it.isNotBlank() }?.let { parts.add("【角色关系】\n$it") }
        formatWorldState(memory.worldState).takeIf { it.isNotBlank() }?.let { parts.add("【世界/场景状态】\n$it") }
        formatTimeline(memory.recentCompressedTimeline).takeIf { it.isNotBlank() }?.let { parts.add("【近期压缩时间线】\n$it") }
        memory.openThreads.filter { it.isNotBlank() }.takeIf { it.isNotEmpty() }?.let {
            parts.add("【未完成线索】\n" + it.joinToString("\n") { item -> "- $item" })
        }
        memory.continuityRules.filter { it.isNotBlank() }.takeIf { it.isNotEmpty() }?.let {
            parts.add("【连续性约束】\n" + it.joinToString("\n") { item -> "- $item" })
        }
        return parts.joinToString("\n\n")
    }

    private fun UniversalContextMemory.isEmpty(): Boolean =
        globalSummary.isBlank() && userState == UniversalUserState() && characterStates.isEmpty() &&
            relationshipStates.isEmpty() && worldState == UniversalWorldState() &&
            recentCompressedTimeline.isEmpty() && openThreads.isEmpty() && continuityRules.isEmpty()

    private fun formatUserState(state: UniversalUserState): String = listOfNotNull(
        state.identity.takeIf { it.isNotBlank() }?.let { "- 身份：$it" },
        state.currentGoal.takeIf { it.isNotBlank() }?.let { "- 当前目标：$it" },
        state.knownFacts.takeIf { it.isNotEmpty() }?.joinToString("\n") { "- 已知：$it" },
        state.hiddenFacts.takeIf { it.isNotEmpty() }?.joinToString("\n") { "- 隐瞒/未公开：$it" },
        state.commitments.takeIf { it.isNotEmpty() }?.joinToString("\n") { "- 承诺：$it" },
        state.preferences.takeIf { it.isNotEmpty() }?.joinToString("\n") { "- 偏好：$it" },
    ).joinToString("\n")

    private fun formatCharacterStates(states: List<UniversalCharacterState>): String =
        states.filter { it.name.isNotBlank() }.joinToString("\n") { state ->
            val details = listOfNotNull(
                state.identity.takeIf { it.isNotBlank() }?.let { "身份=$it" },
                state.currentGoal.takeIf { it.isNotBlank() }?.let { "目标=$it" },
                state.attitudeToUser.takeIf { it.isNotBlank() }?.let { "对用户=$it" },
                state.knownFacts.takeIf { it.isNotEmpty() }?.joinToString("；", prefix = "已知="),
                state.unknownFacts.takeIf { it.isNotEmpty() }?.joinToString("；", prefix = "未知="),
                state.constraints.takeIf { it.isNotEmpty() }?.joinToString("；", prefix = "约束="),
            ).joinToString("；")
            "- ${state.name}: $details"
        }

    private fun formatRelationshipStates(states: List<UniversalRelationshipState>): String =
        states.filter { it.subject.isNotBlank() || it.objectName.isNotBlank() }.joinToString("\n") { state ->
            "- ${state.subject} → ${state.objectName}: ${state.relation}; 依据=${state.evidence}; 稳定性=${state.stability}"
        }

    private fun formatWorldState(state: UniversalWorldState): String = listOfNotNull(
        state.currentLocation.takeIf { it.isNotBlank() }?.let { "- 当前地点：$it" },
        state.currentTime.takeIf { it.isNotBlank() }?.let { "- 当前时间/阶段：$it" },
        state.activeRules.takeIf { it.isNotEmpty() }?.joinToString("\n") { "- 生效规则：$it" },
        state.changedFacts.takeIf { it.isNotEmpty() }?.joinToString("\n") { "- 状态变化：$it" },
        state.risks.takeIf { it.isNotEmpty() }?.joinToString("\n") { "- 风险：$it" },
    ).joinToString("\n")

    private fun formatTimeline(items: List<UniversalTimelineItem>): String = items.joinToString("\n") { item ->
        "- 事件=${item.event}; 起因=${item.cause}; 结果=${item.result}; 影响=${item.impact}"
    }
}
