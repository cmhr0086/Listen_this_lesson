package com.cmhr.listen.data.course

import java.util.Calendar
import java.util.TimeZone
import kotlin.math.abs

data class CourseSuggestion(val courseId: Long, val reason: Reason) {
    enum class Reason {
        /** Attended on this weekday at about this time in recent weeks. */
        USUAL_TIME,
        /** Fallback: the most recently attended (or created) course. */
        MOST_RECENT
    }
}

/**
 * Guesses which course is starting now from the user's own history: a course attended on the same
 * weekday within [WINDOW_MINUTES] of the current time-of-day during the last [LOOKBACK_WEEKS]
 * weeks wins; otherwise the most recently used course. The user always confirms after recording.
 */
object CourseSuggester {
    const val LOOKBACK_WEEKS = 8
    const val WINDOW_MINUTES = 75
    const val LOOKBACK_MS = LOOKBACK_WEEKS * 7L * 24 * 60 * 60 * 1000

    fun suggest(
        now: Long,
        history: List<CourseStart>,
        courses: List<CourseSummary>,
        timeZone: TimeZone = TimeZone.getDefault()
    ): CourseSuggestion? {
        val known = courses.map { it.course.id }.toSet()
        if (known.isEmpty()) return null
        val current = calendar(now, timeZone)
        val weekday = current.get(Calendar.DAY_OF_WEEK)
        val minute = current.minuteOfDay()
        // Only earlier days count as a habit: a class started an hour ago today is not "usual".
        val startOfToday = (current.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val usual = history
            .filter { it.courseId in known && it.startedAt in (now - LOOKBACK_MS) until startOfToday }
            .filter { start ->
                val then = calendar(start.startedAt, timeZone)
                then.get(Calendar.DAY_OF_WEEK) == weekday && abs(then.minuteOfDay() - minute) <= WINDOW_MINUTES
            }
            .groupBy { it.courseId }
            // Most occurrences first; ties go to the course attended most recently in that slot.
            .maxWithOrNull(compareBy<Map.Entry<Long, List<CourseStart>>> { it.value.size }.thenBy { entry -> entry.value.maxOf { it.startedAt } })
        if (usual != null) return CourseSuggestion(usual.key, CourseSuggestion.Reason.USUAL_TIME)
        val recent = courses.maxByOrNull { it.lastStartedAt ?: it.course.createdAt } ?: return null
        return CourseSuggestion(recent.course.id, CourseSuggestion.Reason.MOST_RECENT)
    }

    private fun calendar(epochMs: Long, timeZone: TimeZone) = Calendar.getInstance(timeZone).apply { timeInMillis = epochMs }
    private fun Calendar.minuteOfDay() = get(Calendar.HOUR_OF_DAY) * 60 + get(Calendar.MINUTE)
}
