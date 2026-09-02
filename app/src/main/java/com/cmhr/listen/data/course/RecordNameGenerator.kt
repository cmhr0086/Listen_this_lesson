package com.cmhr.listen.data.course

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object RecordNameGenerator {
    fun defaultName(timestamp: Long = System.currentTimeMillis(), locale: Locale = Locale.getDefault()): String =
        SimpleDateFormat("yyyy-MM-dd HH-mm 课堂记录", locale).format(Date(timestamp))
}
