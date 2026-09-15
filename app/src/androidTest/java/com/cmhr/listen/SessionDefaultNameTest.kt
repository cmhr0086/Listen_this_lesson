package com.cmhr.listen

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cmhr.listen.data.course.CourseEntity
import com.cmhr.listen.data.course.CourseRepository
import com.cmhr.listen.data.course.ListenDatabase
import com.cmhr.listen.data.course.RecordNameGenerator
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Calendar
import java.util.TimeZone

@RunWith(AndroidJUnit4::class)
class SessionDefaultNameTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun manualRenameAndReopenNeverRestoreDefaultName() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, ListenDatabase::class.java).allowMainThreadQueries().build()
        try {
            val repository = CourseRepository(database)
            val courseId = database.courseDao().insert(CourseEntity(name = "理论力学", createdAt = 1))
            val zone = TimeZone.getTimeZone("Asia/Shanghai")
            val timestamp = Calendar.getInstance(zone).apply {
                clear(); set(2026, Calendar.SEPTEMBER, 14, 9, 0, 0)
            }.timeInMillis
            val recordId = repository.createRecord(courseId, RecordNameGenerator.defaultName("理论力学", timestamp, zone))
            assertEquals("理论力学-09-14", repository.record(recordId).first()?.name)

            repository.renameRecord(recordId, "手动命名")
            repository.finishRecord(recordId)
            repository.reopenRecord(recordId)
            assertEquals("手动命名", repository.record(recordId).first()?.name)
        } finally {
            database.close()
        }
    }
}
