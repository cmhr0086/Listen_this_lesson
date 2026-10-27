package com.cmhr.listen

import android.content.Context
import com.cmhr.listen.data.ai.AiActionType
import com.cmhr.listen.data.ai.AiConversationEntity
import com.cmhr.listen.data.ai.AiMessageEntity
import com.cmhr.listen.data.ai.AiRequestStatus
import com.cmhr.listen.data.ai.AiResultEntity
import com.cmhr.listen.data.ai.AiResultSegmentEntity
import com.cmhr.listen.data.ai.NoteStyle
import com.cmhr.listen.data.course.CourseEntity
import com.cmhr.listen.data.course.ListenDatabase
import com.cmhr.listen.data.course.SegmentEntity
import com.cmhr.listen.data.course.SessionEntity
import com.cmhr.listen.data.recording.RecordingEntity
import com.cmhr.listen.data.recording.RecordingRepository
import com.cmhr.listen.data.recording.RecordingState
import com.cmhr.listen.audio.PcmRecorder
import com.cmhr.listen.audio.StreamingWavRecorder
import java.io.File
import com.cmhr.listen.data.settings.AiPromptSettings
import com.cmhr.listen.data.settings.LEGACY_QUICK_ANSWER_PROMPT
import com.cmhr.listen.data.ai.LEGACY_CONVERSATION_PROMPT
import kotlinx.coroutines.flow.first

/**
 * Debug builds (package `com.cmhr.listen.debug`, installed beside the real app) start with two
 * demo classes so notes and follow-ups can be tried without recording or an AI key. Runs only
 * while the debug database has no courses.
 */
object DemoData {
    suspend fun seedIfEmpty(context: Context) {
        val database = ListenDatabase.get(context)
        if (database.courseDao().courses().first().isEmpty()) seedClasses(database)
        seedConversationIfMissing(database)
        seedRecordingsIfMissing(context, database)
    }

    /** Two short tones on the 材料力学 demo class, so recording export can be tried without a microphone. */
    private suspend fun seedRecordingsIfMissing(context: Context, database: ListenDatabase) {
        val recordId = mechanicsRecordId(database) ?: return
        if (database.recordingDao().observeForSession(recordId).first().isNotEmpty()) return
        val session = database.recordDao().recordNow(recordId) ?: return
        val directory = File(context.filesDir, RecordingRepository.DIRECTORY).apply { mkdirs() }
        listOf(0L, 6 * 60_000L).forEachIndexed { index, offset ->
            val seconds = 3 + index
            val entity = RecordingEntity(recordId = recordId, sessionId = session.sessionId, localPath = "", startedAt = session.startedAt + offset)
            val file = File(directory, RecordingRepository.audioFileName(entity.recordingId))
            file.outputStream().use { out ->
                out.write(ByteArray(StreamingWavRecorder.HEADER_BYTES.toInt()))
                val samples = PcmRecorder.SAMPLE_RATE_HZ * seconds
                val pcm = ByteArray(samples * 2)
                for (i in 0 until samples) {
                    val value = (Math.sin(2 * Math.PI * (440 + 220 * index) * i / PcmRecorder.SAMPLE_RATE_HZ) * 6000).toInt()
                    pcm[2 * i] = value.toByte(); pcm[2 * i + 1] = (value shr 8).toByte()
                }
                out.write(pcm)
            }
            StreamingWavRecorder.repair(file)
            val frames = StreamingWavRecorder.framesIn(file)
            database.recordingDao().insert(
                entity.copy(
                    localPath = file.name,
                    endedAt = entity.startedAt + seconds * 1_000L,
                    durationMs = seconds * 1_000L,
                    totalFrames = frames,
                    processedFrames = frames,
                    state = RecordingState.COMPLETED.name
                )
            )
        }
    }

    private fun mechanicsRecordId(database: ListenDatabase): Long? = database.openHelper.writableDatabase.query(
        "SELECT r.id FROM records r JOIN courses c ON c.id = r.courseId WHERE c.name = ? AND r.deleted = 0 LIMIT 1",
        arrayOf<Any>(MECHANICS_COURSE)
    ).use { if (it.moveToFirst()) it.getLong(0) else null }

    private suspend fun seedClasses(database: ListenDatabase) {
        val now = System.currentTimeMillis()
        seedClass(
            database,
            course = MECHANICS_COURSE,
            className = "截面的几何性质",
            topic = "静矩与惯性矩",
            startedAt = now - 26 * HOUR,
            lines = MECHANICS_LINES,
            // Saved the way notes looked before whitespace-only stream chunks were kept.
            notes = LEGACY_NOTES,
            notesPrompt = LEGACY_PROMPT
        )
        seedClass(
            database,
            course = "示例 · 建设法规",
            className = "考试说明与复习范围",
            topic = "期末复习要点",
            startedAt = now - 3 * HOUR,
            lines = LAW_LINES,
            notes = OUTLINE_NOTES,
            notesPrompt = NoteStyle.buildPrompt(NoteStyle.OUTLINE, AiPromptSettings())
        )
    }

    /**
     * A finished class Q&A on the 材料力学 demo class, saved with the pre-1.5.1 prompts, so the chat
     * page and the prompt upgrade can be looked at without sending a question.
     */
    private suspend fun seedConversationIfMissing(database: ListenDatabase) {
        val sql = database.openHelper.writableDatabase
        val recordId = mechanicsRecordId(database) ?: return
        val hasConversation = sql.query("SELECT 1 FROM ai_conversations WHERE recordId = ? LIMIT 1", arrayOf<Any>(recordId))
            .use { it.moveToFirst() }
        if (hasConversation) return
        val now = System.currentTimeMillis()
        val conversationId = database.aiDao().insertConversation(
            AiConversationEntity(
                recordId = recordId,
                title = "静矩为什么可以是负的",
                sourceTextSnapshot = MECHANICS_LINES.joinToString("\n") { it.first },
                systemPrompt = "$LEGACY_CONVERSATION_PROMPT\n\n原始处理任务：$LEGACY_QUICK_ANSWER_PROMPT",
                createdAt = now - 20 * 60_000L,
                updatedAt = now - 19 * 60_000L
            )
        )
        DEMO_CHAT.forEachIndexed { index, (role, content) ->
            val at = now - 20 * 60_000L + index * 30_000L
            database.aiDao().insertMessage(
                AiMessageEntity(conversationId = conversationId, role = role, content = content, createdAt = at, finishedAt = at)
            )
        }
    }

    private suspend fun seedClass(
        database: ListenDatabase,
        course: String,
        className: String,
        topic: String,
        startedAt: Long,
        lines: List<Pair<String, Boolean>>,
        notes: String,
        notesPrompt: String
    ) {
        val courseId = database.courseDao().insert(CourseEntity(name = course, createdAt = startedAt))
        val endedAt = startedAt + lines.size * SEGMENT_MS
        val session = SessionEntity(courseId = courseId, name = className, startedAt = startedAt, endedAt = endedAt, topic = topic)
        val recordId = database.recordDao().insert(session)
        val segmentIds = lines.mapIndexed { index, (text, marked) ->
            val start = startedAt + index * SEGMENT_MS
            database.transcriptDao().insert(
                SegmentEntity(
                    recordId = recordId,
                    startTime = start,
                    endTime = start + SEGMENT_MS,
                    audioDurationMs = SEGMENT_MS,
                    recognitionDurationMs = null,
                    text = text,
                    sessionId = session.sessionId,
                    marked = marked
                )
            )
        }
        val snapshot = lines.joinToString("\n") { it.first }
        val resultId = database.aiDao().insertResult(
            AiResultEntity(
                recordId = recordId,
                actionType = AiActionType.ORGANIZE_NOTES.name,
                requestPrompt = notesPrompt,
                sourceTextSnapshot = snapshot,
                output = notes,
                status = AiRequestStatus.SUCCESS.name,
                createdAt = endedAt,
                finishedAt = endedAt
            )
        )
        database.aiDao().insertResultSegments(segmentIds.map { AiResultSegmentEntity(resultId, it) })
    }

    private const val HOUR = 60 * 60_000L
    private const val MECHANICS_COURSE = "示例 · 材料力学"

    private val DEMO_CHAT = listOf(
        "user" to "老师说静矩可正可负也可以是零，为什么面积乘坐标会是负的？",
        "assistant" to """
            因为静矩里乘的是 **带符号的坐标**，不是距离。

            - 对 y 轴的静矩 Sy = ∫ x·dA，x 在 y 轴左边是负的
            - 图形大部分面积在轴的左侧，Sy 就是负的；左右抵消时就是零
            - 所以静矩的符号只说明形心在轴的哪一侧：Sy = A·xc，xc < 0 时 Sy < 0

            这也是为什么 **对形心轴的静矩等于零**：形心轴两边的面积矩正好抵消。
        """.trimIndent(),
        "user" to "那惯性矩为什么一定是正的？",
        "assistant" to """
            惯性矩里坐标是 **平方**：Ix = ∫ y²·dA。

            - y² 无论 y 正负都不小于零，dA 又是正的，积分只能是正数
            - 极惯性矩同理：Ip = ∫ ρ²·dA，ρ 是到原点的距离

            一句话：静矩看"在哪一边"，惯性矩看"离得有多远"。
        """.trimIndent()
    )
    private const val SEGMENT_MS = 30_000L

    private const val LEGACY_PROMPT = "你是课堂笔记整理助手。只依据原文，按主题、关键要点和专业术语整理成中文笔记；保留重要限定条件，不得虚构。"

    private val MECHANICS_LINES = listOf(
        "好，我们今天讲截面的几何性质，先讲静矩，也叫面积矩。" to false,
        "微面积 dA 乘以它的 x 坐标，叫做这个微面积对 y 轴的静矩。" to false,
        "整个图形对 x 轴的静矩就是 y 乘 dA 在整个面积上积分。" to false,
        "静矩和形心坐标的关系一定要记住，Sy 等于 A 乘 xc，这个考试必考。" to true,
        "静矩的量纲是长度的三次方，它可正可负也可以是零。" to false,
        "过形心的轴叫形心轴，图形对形心轴的静矩等于零。" to true,
        "组合图形的静矩等于各部分对同一轴静矩的代数和。" to false,
        "我们算一个半圆，它关于 y 轴对称，所以 Sy 等于零。" to false,
        "取一个窄条，dA 等于二倍根号下 r 方减 y 方再乘 dy。" to false,
        "积出来 Sx 等于三分之二 r 的三次方，形心纵坐标是三π分之四 r。" to false,
        "下面讲极惯性矩，Ip 等于 ρ 方乘 dA 积分，它恒为正。" to false,
        "实心圆截面 Ip 等于 π d 四次方除以三十二，这个公式要背下来。" to true
    )

    private val LAW_LINES = listOf(
        "同学们，这节课我们讲一下期末考试的安排。" to false,
        "考试时长两个小时，开卷，可以带课本和打印的资料。" to true,
        "题型有单选、多选、判断、简答、案例分析和论述。" to false,
        "单选每题两分，多选每题四分，漏选每个选项得一分，错选不得分。" to true,
        "多选题拿不准的宁缺毋滥，不要乱选。" to false,
        "十章内容都会考，第四、第五、第七章内容稍多。" to false,
        "案例题一定要先给明确的判断，合法还是不合法，再写理由。" to true,
        "写依据要写清楚法律名称，中华人民共和国几个字可以省略。" to false,
        "课上做过的两套练习题，部分题目会从里面抽。" to false,
        "大家把课本目录熟悉一下，开卷考试找得快很重要。" to false
    )

    private val LEGACY_NOTES = """
        一、静矩（面积矩）
        -定义：微面积 dA乘以其 x坐标，称为该微面积对 y轴的静矩，记为 dSy = x·dA。
        -整个图形对 x轴的静矩：Sx = ∫ y·dA。
        -静矩与形心坐标的关系：Sy = A·xc，Sx = A·yc。
        -量纲：长度的三次方；可正、可负、可为零。
        -形心轴：图形对形心轴的静矩等于零。

        二、组合图形的静矩
        -组合图形对某轴的静矩 =各组成部分对该轴静矩的代数和。

        三、半圆静矩算例
        1.半圆关于 y轴对称，故 Sy =0。
        2.窄条微面积 dA =2√(r²−y²)·dy。
        3.Sx = (2/3)r³，形心 yc =4r/(3π)。

        四、极惯性矩
        -Ip = ∫ ρ²·dA，恒为正。
        -实心圆截面：Ip = πd⁴/32。
    """.trimIndent()

    private val OUTLINE_NOTES = """
        ## 考点速览

        - **考试形式**：开卷，2 小时，可带课本和打印资料
        - **题型**：单选、多选、判断、简答、案例分析、论述
        - **案例题**：先给明确判断（合法 / 不合法），再写理由
        - **复习重点**：第四、五、七章内容稍多

        ## 分值与作答规则

        - 单选每题 2 分
        - 多选每题 4 分
          - 漏选：每选对一项得 1 分
          - 错选：本题不得分
        - 多选拿不准时宁缺毋滥

        ## 易错与提醒

        - 写判别依据时要写明 **法律名称**，“中华人民共和国”可省略
        - 课上做过的两套练习题会抽部分原题
        - 熟悉课本目录，开卷考试找得快很重要
    """.trimIndent()
}
