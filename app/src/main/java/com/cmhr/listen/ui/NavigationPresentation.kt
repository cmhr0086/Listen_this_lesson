package com.cmhr.listen.ui

/** Presentation rules shared by native UI and regression tests, independent of Compose. */
internal enum class MainDestination(val route: String, val label: String) {
    RECORD("record-home", "录音"),
    COURSES("courses", "课程"),
    AI("ai", "AI 会话"),
    SETTINGS("settings", "设置")
}

internal fun isSettingsRoute(route: String?): Boolean = route == "settings" || route?.startsWith("settings/") == true

/**
 * Tab that owns a page, decided by what the page is rather than how it was reached: the class that
 * is recording or paused ([liveRecordId]) belongs to 录音, every other course/class page to 课程.
 */
internal fun mainDestinationForRoute(route: String?, recordId: Long? = null, liveRecordId: Long? = null): MainDestination = when {
    isSettingsRoute(route) -> MainDestination.SETTINGS
    route == "ai" || route?.startsWith("ai/new") == true || route?.startsWith("ai-conversation/") == true || route?.startsWith("ai/result/") == true -> MainDestination.AI
    route == MainDestination.RECORD.route || route == "search" -> MainDestination.RECORD
    route == "record/{recordId}" && recordId != null && recordId == liveRecordId -> MainDestination.RECORD
    else -> MainDestination.COURSES
}

internal fun newAiConversationRoute(activeRecordId: Long?, isListening: Boolean): String =
    activeRecordId?.takeIf { isListening }?.let { "ai/new/$it" } ?: "ai/new"
