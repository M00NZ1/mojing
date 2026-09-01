package com.mojing.app.domain.engine

data class UniversalContextMemory(
    val globalSummary: String = "",
    val userState: UniversalUserState = UniversalUserState(),
    val characterStates: List<UniversalCharacterState> = emptyList(),
    val relationshipStates: List<UniversalRelationshipState> = emptyList(),
    val worldState: UniversalWorldState = UniversalWorldState(),
    val recentCompressedTimeline: List<UniversalTimelineItem> = emptyList(),
    val openThreads: List<String> = emptyList(),
    val continuityRules: List<String> = emptyList(),
)

data class UniversalUserState(
    val identity: String = "",
    val currentGoal: String = "",
    val knownFacts: List<String> = emptyList(),
    val hiddenFacts: List<String> = emptyList(),
    val commitments: List<String> = emptyList(),
    val preferences: List<String> = emptyList(),
)

data class UniversalCharacterState(
    val name: String = "",
    val identity: String = "",
    val currentGoal: String = "",
    val attitudeToUser: String = "",
    val knownFacts: List<String> = emptyList(),
    val unknownFacts: List<String> = emptyList(),
    val constraints: List<String> = emptyList(),
)

data class UniversalRelationshipState(
    val subject: String = "",
    val objectName: String = "",
    val relation: String = "",
    val evidence: String = "",
    val stability: String = "",
)

data class UniversalWorldState(
    val currentLocation: String = "",
    val currentTime: String = "",
    val activeRules: List<String> = emptyList(),
    val changedFacts: List<String> = emptyList(),
    val risks: List<String> = emptyList(),
)

data class UniversalTimelineItem(
    val event: String = "",
    val cause: String = "",
    val result: String = "",
    val impact: String = "",
)
