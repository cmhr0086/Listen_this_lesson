package com.cmhr.listen.data.course

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object RecordNameGenerator {
    fun defaultName(
        courseName: String,
        timestamp: Long = System.currentTimeMillis(),
        timeZone: TimeZone = TimeZone.getDefault()
    ): String {
        val localDate = SimpleDateFormat("MM-dd", Locale.US).apply { this.timeZone = timeZone }
            .format(Date(timestamp))
        return "$courseName-$localDate"
    }
}
