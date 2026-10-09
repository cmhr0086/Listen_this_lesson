package com.cmhr.listen.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material.icons.outlined.Search
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Badge
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.cmhr.listen.AiViewModel
import com.cmhr.listen.AppNavigationRequests
import com.cmhr.listen.CourseViewModel
import com.cmhr.listen.SettingsViewModel
import com.cmhr.listen.AiPromptKind
import com.cmhr.listen.SttViewModel
import com.cmhr.listen.RecordingViewModel
import com.cmhr.listen.recording.CaptureMode
import com.cmhr.listen.data.ai.AiActionType
import com.cmhr.listen.data.stt.AsrDiagnosticStateCounts
import com.cmhr.listen.data.stt.AsrRuntimeSummary
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

internal enum class MainDestination(val route: String, val label: String) {
    RECORD("record-home", "录音"),
    COURSES("courses", "课程"),
    AI("ai", "AI 会话"),
    SETTINGS("settings", "设置")
}

internal val AppBottomNavigationHeight = 80.dp

internal data class BottomChromeLayout(
    val navigationContainerHeight: Dp,
    val composerBottomPadding: Dp
)

/**
 * Keeps the app navigation bar mounted while moving only the chat composer.
 * The IME height is read from the current animated WindowInsets value; no
 * keyboard height is guessed or hard-coded.
 */
@Composable
internal fun bottomChromeLayout(): BottomChromeLayout {
    val density = LocalDensity.current
    val rawImeBottom = with(density) { WindowInsets.ime.getBottom(this).toDp() }
    val systemNavigationBottom = with(density) { WindowInsets.navigationBars.getBottom(this).toDp() }
    val fullNavigationReservation = AppBottomNavigationHeight + systemNavigationBottom
    return BottomChromeLayout(
        navigationContainerHeight = fullNavigationReservation,
        composerBottomPadding =
            (rawImeBottom - fullNavigationReservation).coerceAtLeast(0.dp)
    )
}

private sealed interface FabState {
    data object None : FabState
    data object NewCourse : FabState
    data class NewRecord(val courseId: Long) : FabState
}

private fun isSettingsRoute(route: String?): Boolean = route == "settings" || route?.startsWith("settings/") == true
private fun isAiWorkspaceRoute(route: String?): Boolean =
    route?.contains("ai-results") == true || route?.contains("ai-result/") == true || route?.startsWith("ai-conversation/") == true

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

/** What to do with a course created from the "新建课程" dialog. */
private sealed interface CourseCreation {
    data object Plain : CourseCreation
    data class ThenStart(val mode: CaptureMode) : CourseCreation
    data object ThenPickForStart : CourseCreation
    data class ThenFile(val recordId: Long) : CourseCreation
}

private fun routeTitle(route: String?): String = when (route) {
    "record-home" -> "录音"
    "courses" -> "课程"
    "ai" -> "AI 会话"
    "ai/new" -> "新对话"
    "ai/new/{recordId}" -> "课堂新对话"
    "ai/result/{recordId}/{resultId}" -> "AI 结果详情"
    "course/{courseId}" -> "课堂记录"
    "record/{recordId}" -> "记录详情"
    "settings" -> "设置"
    "search" -> "搜索"
    "settings/stt-service" -> "语音识别"
    "settings/vad" -> "VAD 预设与参数"
    "settings/ai-service" -> "AI 服务"
    "settings/ai-service/models" -> "选择模型"
    "settings/asr-prompt-policy/thresholds" -> "自动模式门槛"
    "settings/cloud-sync" -> "云同步"
    "settings/appearance" -> "外观"
    "settings/ai-prompts" -> "AI 提示词"
    "settings/asr-prompt-policy" -> "专业词提示"
    "settings/ai-generation" -> "AI 生成参数"
    "settings/ai-prompts/{kind}" -> "编辑提示词"
    "settings/asr-diagnostics" -> "ASR 诊断"
    "settings/asr-diagnostics/history/{recordId}" -> "ASR 诊断历史"
    "record/{recordId}/ai-results" -> "AI 结果"
    "record/{recordId}/ai-result/{resultId}" -> "AI 结果详情"
    "record/{recordId}/ai-conversations" -> "AI 对话"
    "ai-conversation/{conversationId}" -> "课堂问答"
    else -> "课堂提问识别助手"
}

internal fun newAiConversationRoute(activeRecordId: Long?, isListening: Boolean): String =
    activeRecordId?.takeIf { isListening }?.let { "ai/new/$it" } ?: "ai/new"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListenApp(
    stt: SttViewModel = viewModel(),
    courses: CourseViewModel = viewModel(),
    settings: SettingsViewModel = viewModel(),
    ai: AiViewModel = viewModel(),
    recordings: RecordingViewModel = viewModel()
) {
    val nav = rememberNavController()
    val backStackEntry by nav.currentBackStackEntryAsState()
    val route = backStackEntry?.destination?.route ?: MainDestination.RECORD.route
    val sttState by stt.uiState.collectAsStateWithLifecycle()
    val liveRecordId = sttState.activeRecordId ?: sttState.pausedClass?.recordId
    val currentTab = mainDestinationForRoute(route, backStackEntry?.arguments?.getString("recordId")?.toLongOrNull(), liveRecordId)
    val courseState by courses.uiState.collectAsStateWithLifecycle()
    val settingsState by settings.uiState.collectAsStateWithLifecycle()
    val backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    val aiState by ai.uiState.collectAsStateWithLifecycle()
    val recordingState by recordings.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    // Why the "新建课程" dialog is open decides what happens with the new course.
    var courseCreation by remember { mutableStateOf<CourseCreation?>(null) }
    // Record-first flow: the course chosen on home before starting (null = use the suggestion),
    // and the record that must be filed under a course once its capture stops.
    var startCourseOverride by remember { mutableStateOf<Long?>(null) }
    var pickingStartCourse by remember { mutableStateOf(false) }
    var pendingQuickStartCourseId by remember { mutableStateOf<Long?>(null) }
    var awaitingFilingRecordId by remember { mutableStateOf<Long?>(null) }
    var filingCaptureSeen by remember { mutableStateOf(false) }
    var filingRecordId by remember { mutableStateOf<Long?>(null) }
    var creatingRecordForCourse by remember { mutableStateOf<Long?>(null) }
    var newName by remember { mutableStateOf("") }
    var pendingPermissionRecordId by remember { mutableStateOf<Long?>(null) }
    var pendingCaptureMode by remember { mutableStateOf(CaptureMode.REALTIME_ASR) }
    var recordMenuExpanded by remember { mutableStateOf(false) }
    var editingRecordCoursePrompt by remember { mutableStateOf(false) }
    var promptDraft by remember { mutableStateOf("") }
    var promptModeDraft by remember { mutableStateOf<String?>(null) }
    var exportContent by remember { mutableStateOf<String?>(null) }
    var confirmDeleteAiContents by remember { mutableStateOf(false) }
    var confirmDeleteTranscripts by remember { mutableStateOf(false) }
    var aiContextMenuExpanded by remember { mutableStateOf(false) }
    var showAiContext by remember { mutableStateOf(false) }
    var aiComposerClearance by remember { mutableStateOf(0.dp) }

    val courseId = backStackEntry?.arguments?.getString("courseId")?.toLongOrNull()
    val recordId = backStackEntry?.arguments?.getString("recordId")?.toLongOrNull()
    val resultId = backStackEntry?.arguments?.getString("resultId")?.toLongOrNull()
    val conversationId = backStackEntry?.arguments?.getString("conversationId")?.toLongOrNull()
    val headerResult by remember(resultId) {
        resultId?.let(ai::result) ?: flowOf<com.cmhr.listen.data.ai.AiResultEntity?>(null)
    }.collectAsStateWithLifecycle(initialValue = null)
    val headerConversation by remember(conversationId) {
        conversationId?.let(ai::conversation) ?: flowOf<com.cmhr.listen.data.ai.AiConversationEntity?>(null)
    }.collectAsStateWithLifecycle(initialValue = null)
    val aiContextSnapshot = headerResult?.sourceTextSnapshot ?: headerConversation?.sourceTextSnapshot
    val selectionMode = route == "record/{recordId}" && recordId != null && aiState.selectionRecordId == recordId
    val contentSelectionMode = when {
        route == "record/{recordId}/ai-results" && recordId != null ->
            aiState.contentSelectionScope == com.cmhr.listen.AiContentSelectionScope(recordId)
        route == "ai" -> aiState.contentSelectionScope == com.cmhr.listen.AiContentSelectionScope(null)
        else -> false
    }
    val currentRecord = courseState.selectedRecord?.takeIf { it.id == recordId }
    val currentCourse = currentRecord?.let { record -> courseState.courses.firstOrNull { it.id == record.courseId } }
    val currentSegments = courseState.detailSegments.filter { it.recordId == recordId }
    val currentSummary by remember(recordId) {
        recordId?.let(courses::recordSummary) ?: flowOf(null)
    }.collectAsStateWithLifecycle(initialValue = null)
    val courseColors by courses.courseColors.collectAsStateWithLifecycle(initialValue = emptyMap())
    // Notes are made once a class is over: after it is filed, or after its recordings are recognized.
    var autoNotesFor by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(recordingState.recognizingAllRecordId) {
        val finished = autoNotesFor
        if (recordingState.recognizingAllRecordId == null && finished != null) ai.autoOrganizeNotes(finished)
        autoNotesFor = recordingState.recognizingAllRecordId
    }
    val copySelection: () -> Unit = {
        val selected = aiState.selectedSegmentIds
        val text = com.cmhr.listen.AiViewModel.orderTranscriptSegments(currentSegments.filter { it.id in selected })
            .joinToString("\n") { it.effectiveText }
        context.getSystemService(android.content.ClipboardManager::class.java)
            ?.setPrimaryClip(android.content.ClipData.newPlainText("课堂文字", text))
        scope.launch { snackbarHostState.showSnackbar("已复制 ${selected.size} 段文字", duration = SnackbarDuration.Short) }
    }
    val openNewConversation = {
        nav.navigate(newAiConversationRoute(sttState.activeRecordId, sttState.isListening))
    }
    LaunchedEffect(route) {
        if (!isAiWorkspaceRoute(route)) aiComposerClearance = 0.dp
    }

    BackHandler(enabled = selectionMode || contentSelectionMode) {
        if (selectionMode) ai.clearSelection() else ai.clearContentSelection()
    }
    LaunchedEffect(settings) {
        settings.messages.collectLatest { message ->
            snackbarHostState.showSnackbar(message.text, duration = SnackbarDuration.Short)
        }
    }
    LaunchedEffect(stt) {
        stt.asrMessages.collectLatest { message ->
            snackbarHostState.showSnackbar(message, duration = SnackbarDuration.Short)
        }
    }
    LaunchedEffect(route) {
        recordMenuExpanded = false
        aiContextMenuExpanded = false
        showAiContext = false
    }
    LaunchedEffect(nav) {
        AppNavigationRequests.recordRequests.collectLatest { requestedRecordId ->
            nav.navigate("record/$requestedRecordId") { launchSingleTop = true }
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        val content = exportContent
        if (uri != null && content != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use { it.write(content) }
            }
        }
        exportContent = null
    }

    // Creates the record only after permissions are granted, so a denial leaves no empty record.
    val beginQuickStart: (Long, CaptureMode) -> Unit = { courseId, mode ->
        courses.createRecord(courseId, null) { recordId ->
            awaitingFilingRecordId = recordId
            filingCaptureSeen = false
            if (mode == CaptureMode.RECORD_ONLY) stt.startRecordOnly(recordId) else stt.startListening(recordId)
            nav.navigate("record/$recordId")
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        val recordId = pendingPermissionRecordId
        val quickStartCourseId = pendingQuickStartCourseId
        val captureMode = pendingCaptureMode
        pendingPermissionRecordId = null
        pendingQuickStartCourseId = null
        val microphoneGranted = grants[Manifest.permission.RECORD_AUDIO]
            ?: (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
        val notificationGranted = Build.VERSION.SDK_INT < 33 || grants[Manifest.permission.POST_NOTIFICATIONS]
            ?: (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
        if (microphoneGranted && quickStartCourseId != null) {
            beginQuickStart(quickStartCourseId, captureMode)
            if (!notificationGranted) stt.reportNotificationPermissionDenied()
        } else if (microphoneGranted && recordId != null) {
            if (captureMode == CaptureMode.RECORD_ONLY) stt.startRecordOnly(recordId) else stt.startListening(recordId)
            if (!notificationGranted) stt.reportNotificationPermissionDenied()
        } else stt.reportPermissionDenied()
    }

    val missingCapturePermissions: () -> List<String> = {
        buildList {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val startCourseId = startCourseOverride?.takeIf { id -> courseState.courseSummaries.any { it.course.id == id } }
        ?: courseState.suggestion?.courseId
    val quickStart: (Long, CaptureMode) -> Unit = { courseId, mode ->
        val permissions = missingCapturePermissions()
        if (permissions.isEmpty()) beginQuickStart(courseId, mode)
        else {
            pendingCaptureMode = mode
            pendingQuickStartCourseId = courseId
            permissionLauncher.launch(permissions.toTypedArray())
        }
    }

    // Once a quick-started capture has actually run and then stopped (panel, FAB or notification),
    // ask where to file it. Waiting for "seen active" avoids firing before capture starts.
    // A pause is not the end of the class: only ask once it is ended (or superseded by another capture).
    LaunchedEffect(sttState.isListening, sttState.activeRecordId, sttState.pausedClass, awaitingFilingRecordId) {
        val awaiting = awaitingFilingRecordId ?: return@LaunchedEffect
        val capturingIt = sttState.isListening && sttState.activeRecordId == awaiting
        val pausedIt = !sttState.isListening && sttState.pausedClass?.recordId == awaiting
        val resumingIt = sttState.resumingRecordId == awaiting
        if (capturingIt) filingCaptureSeen = true
        else if (filingCaptureSeen && !pausedIt && !resumingIt) {
            filingRecordId = awaiting
            awaitingFilingRecordId = null
            filingCaptureSeen = false
        }
    }

    val startCapture: (Long, CaptureMode) -> Unit = { selectedRecordId, mode ->
        pendingCaptureMode = mode
        pendingPermissionRecordId = selectedRecordId
        val permissions = buildList {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (permissions.isEmpty()) {
            pendingPermissionRecordId = null
            if (mode == CaptureMode.RECORD_ONLY) stt.startRecordOnly(selectedRecordId) else stt.startListening(selectedRecordId)
        } else permissionLauncher.launch(permissions.toTypedArray())
    }

    courseCreation?.let { purpose ->
        NameDialog(
            title = "新建课程",
            value = newName,
            update = { newName = it },
            confirm = {
                courses.createCourse(newName) { created ->
                    when (purpose) {
                        CourseCreation.Plain -> Unit
                        is CourseCreation.ThenStart -> quickStart(created, purpose.mode)
                        CourseCreation.ThenPickForStart -> startCourseOverride = created
                        is CourseCreation.ThenFile -> courses.moveRecord(purpose.recordId, created)
                    }
                }
                newName = ""
                courseCreation = null
            },
            dismiss = { courseCreation = null }
        )
    }
    if (pickingStartCourse) CoursePickerDialog(
        title = "这节课是哪门课？",
        message = "只是先选一个，录完还可以改。",
        courses = courseState.courseSummaries,
        initialCourseId = startCourseId,
        confirmLabel = "确定",
        confirm = { startCourseOverride = it; pickingStartCourse = false },
        createNew = { pickingStartCourse = false; newName = ""; courseCreation = CourseCreation.ThenPickForStart },
        dismiss = { pickingStartCourse = false }
    )
    filingRecordId?.let { filing ->
        val summary = courseState.recentSessions.firstOrNull { it.session.id == filing }
        CoursePickerDialog(
            title = "这节课保存到哪门课？",
            message = summary?.let { "「${it.session.name}」· ${formatSessionWhen(it.session.startedAt, it.session.endedAt)}" },
            courses = courseState.courseSummaries,
            initialCourseId = summary?.session?.courseId,
            confirmLabel = "保存",
            confirm = { courseId ->
                courses.moveRecord(filing, courseId)
                startCourseOverride = null
                filingRecordId = null
                ai.autoOrganizeNotes(filing)
            },
            createNew = { filingRecordId = null; newName = ""; courseCreation = CourseCreation.ThenFile(filing); ai.autoOrganizeNotes(filing) },
            dismiss = { filingRecordId = null; ai.autoOrganizeNotes(filing) },
            dismissLabel = "保持不变"
        )
    }
    creatingRecordForCourse?.let { courseId ->
        NameDialog(
            title = "新建课堂记录",
            value = newName,
            update = { newName = it },
            confirm = {
                courses.createRecord(courseId, newName) { created -> nav.navigate("record/$created") }
                newName = ""
                creatingRecordForCourse = null
            },
            dismiss = { creatingRecordForCourse = null },
            allowBlank = true
        )
    }

    if (editingRecordCoursePrompt && currentCourse != null) {
        AsrPromptDialog(
            prompt = promptDraft,
            update = { promptDraft = it },
            modeOverride = promptModeDraft,
            updateMode = { promptModeDraft = it },
            save = {
                courses.updateCourseAsrPrompt(currentCourse.id, promptDraft)
                courses.updateCourseAsrPromptMode(currentCourse.id, promptModeDraft)
                editingRecordCoursePrompt = false
            },
            dismiss = { editingRecordCoursePrompt = false }
        )
    }
    if (confirmDeleteAiContents && contentSelectionMode) TimedDeleteDialog(
        title = "删除 AI 内容",
        message = "将删除选中的 ${aiState.selectedContentKeys.size} 项 AI 结果或对话，原始识别文本不会受影响。",
        confirm = {
                ai.deleteSelectedContents(if (route == "ai") null else recordId)
                confirmDeleteAiContents = false
        },
        dismiss = { confirmDeleteAiContents = false }
    )
    if (confirmDeleteTranscripts && recordId != null) TimedDeleteDialog(
        title = "删除识别片段",
        message = "将删除选中的 ${aiState.selectedSegmentIds.size} 条原始识别片段，并保留用于同步的删除标记。已保存的 AI 冻结快照和输出会保留。",
        confirm = {
            courses.deleteSegments(recordId, aiState.selectedSegmentIds) { ai.clearSelection() }
            confirmDeleteTranscripts = false
        },
        dismiss = { confirmDeleteTranscripts = false }
    )
    if (showAiContext && aiContextSnapshot != null) {
        AiContextBottomSheet(snapshot = aiContextSnapshot, dismiss = { showAiContext = false })
    }

    val nested = route !in MainDestination.entries.map { it.route }.toSet()
    val bottomChrome = bottomChromeLayout()
    // Recording is controlled from the 录音 tab, the class page and the notification; the bottom
    // bar's 录音 icon carries a red dot while capturing, so no floating stop button is needed.
    val fabState: FabState = when {
        route == "courses" -> FabState.NewCourse
        else -> FabState.None
    }

    CompositionLocalProvider(LocalCourseColors provides courseColors) {
    Scaffold(
        // The chat composer is the single owner of IME avoidance. Keeping IME
        // insets out of Scaffold prevents its content padding from changing at
        // the same time as the bottom navigation is removed.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            when {
                contentSelectionMode -> AiContentSelectionTopBar(
                    selectedCount = aiState.selectedContentKeys.size,
                    close = ai::clearContentSelection,
                    export = {
                        if (route == "ai") {
                            ai.buildSelectedGlobalContentsTxt { content ->
                                if (content != null) {
                                    exportContent = content
                                    exportLauncher.launch("Listen_this_lesson-AI内容.txt")
                                }
                            }
                            return@AiContentSelectionTopBar
                        }
                        val record = currentRecord
                        val course = currentCourse
                        if (record != null && course != null) {
                            ai.buildSelectedContentsTxt(record.id, course.name, record.name) { content ->
                                if (content != null) {
                                    exportContent = content
                                    exportLauncher.launch("${record.name} AI结果.txt")
                                }
                            }
                        }
                    },
                    delete = { confirmDeleteAiContents = true }
                )
                selectionMode -> RecordSelectionTopBar(
                    selectedCount = aiState.selectedSegmentIds.size,
                    close = ai::clearSelection,
                    selectAll = { recordId?.let { id -> ai.replaceSelection(id, currentSegments.mapTo(linkedSetOf()) { it.id }) } }
                )
                route == "record/{recordId}" && recordId != null -> RecordNormalTopBar(
                    title = currentSummary?.let { classTitle(it.courseName, it.classNumber, null, it.session.name) }
                        ?: currentRecord?.name ?: "课堂",
                    subtitle = currentRecord?.let { record ->
                        formatSessionWhen(record.startedAt, record.endedAt) + (record.topic?.let { " · $it" } ?: "")
                    },
                    course = currentCourse,
                    menuExpanded = recordMenuExpanded,
                    setMenuExpanded = { recordMenuExpanded = it },
                    back = { nav.popBackStack() },
                    exportTxt = {
                        recordMenuExpanded = false
                        val course = currentCourse
                        if (course != null) {
                            val record = requireNotNull(currentRecord)
                            exportContent = buildTxt(course, record, currentSegments)
                            exportLauncher.launch("${record.name}.txt")
                        }
                    },
                    select = { recordMenuExpanded = false; ai.beginSelection(recordId) },
                    editAsrPrompt = {
                        recordMenuExpanded = false
                        promptDraft = currentCourse?.asrPrompt.orEmpty()
                        promptModeDraft = currentCourse?.asrPromptModeOverride
                        editingRecordCoursePrompt = currentCourse != null
                    }
                )
                route == "record/{recordId}/ai-result/{resultId}" || route == "ai/result/{recordId}/{resultId}" || route == "ai-conversation/{conversationId}" -> TopAppBar(
                    title = { Text(routeTitle(route)) },
                    navigationIcon = {
                        IconButton(onClick = { nav.popBackStack() }) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                        }
                    },
                    actions = {
                        IconButton(onClick = { aiContextMenuExpanded = true }) {
                            Icon(Icons.Outlined.MoreVert, contentDescription = "更多操作")
                        }
                        DropdownMenu(
                            expanded = aiContextMenuExpanded,
                            onDismissRequest = { aiContextMenuExpanded = false },
                            modifier = Modifier.widthIn(min = 220.dp),
                            shape = RoundedCornerShape(20.dp)
                        ) {
                            DropdownMenuItem(
                                text = { Text("课堂原文上下文") },
                                enabled = aiContextSnapshot != null,
                                onClick = {
                                    aiContextMenuExpanded = false
                                    showAiContext = true
                                }
                            )
                        }
                    }
                )
                route == "ai" -> TopAppBar(
                    title = { Text(routeTitle(route)) },
                    actions = {
                        IconButton(onClick = openNewConversation) {
                            Icon(Icons.Outlined.Add, contentDescription = "新建对话")
                        }
                    }
                )
                else -> TopAppBar(
                    title = {
                        // The course page shows its name in its own header.
                        val promptKind = if (route == "settings/ai-prompts/{kind}") {
                            backStackEntry?.arguments?.getString("kind")?.let { name -> AiPromptKind.entries.firstOrNull { it.name == name } }
                        } else null
                        if (route != "course/{courseId}") Text(promptKind?.label ?: routeTitle(route), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                    navigationIcon = {
                        // Through the dispatcher, so a page's BackHandler (unsaved edits) also sees this.
                        if (nested) IconButton(onClick = { backDispatcher?.onBackPressed() ?: nav.popBackStack() }) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                        }
                    },
                    actions = {
                        if (route == MainDestination.RECORD.route || route == MainDestination.COURSES.route) {
                            IconButton(onClick = { nav.navigate("search") }, modifier = Modifier.testTag("open-search")) {
                                Icon(Icons.Outlined.Search, contentDescription = "搜索课堂")
                            }
                        }
                    }
                )
            }
        },
        bottomBar = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(bottomChrome.navigationContainerHeight),
                contentAlignment = Alignment.TopCenter
            ) {
                NavigationBar(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(AppBottomNavigationHeight),
                    windowInsets = WindowInsets(0, 0, 0, 0)
                ) {
                    MainDestination.entries.forEach { destination ->
                        val selected = destination == currentTab
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                nav.navigate(destination.route) {
                                    launchSingleTop = true
                                    popUpTo(MainDestination.RECORD.route) { saveState = true }
                                    // 录音 always opens its own page; other tabs restore where the user was.
                                    restoreState = destination != MainDestination.RECORD
                                }
                            },
                            icon = {
                                val capturing = destination == MainDestination.RECORD && sttState.isListening
                                val paused = destination == MainDestination.RECORD && !sttState.isListening && sttState.pausedClass != null
                                val glyph = when (destination) {
                                    MainDestination.RECORD -> if (capturing) Icons.Filled.Mic else Icons.Outlined.Mic
                                    MainDestination.COURSES -> Icons.Outlined.School
                                    MainDestination.AI -> Icons.Outlined.SmartToy
                                    MainDestination.SETTINGS -> Icons.Outlined.Settings
                                }
                                if (capturing) BadgedBox(badge = { Badge(containerColor = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("capturing-badge")) }) {
                                    Icon(glyph, contentDescription = "${destination.label}（正在录制）")
                                } else if (paused) BadgedBox(badge = { Badge(containerColor = MaterialTheme.colorScheme.tertiary, modifier = Modifier.testTag("paused-badge")) }) {
                                    Icon(glyph, contentDescription = "${destination.label}（已暂停）")
                                } else Icon(glyph, contentDescription = destination.label)
                            },
                            label = { Text(destination.label) }
                        )
                    }
                }
            }
        },
        floatingActionButton = {
            val isComposerRoute = route?.startsWith("ai/new") == true ||
                route?.startsWith("ai-conversation/") == true ||
                route?.contains("ai-result/") == true ||
                route?.startsWith("ai/result/") == true
            val composerFabClearance = if (isComposerRoute) {
                aiComposerClearance.takeIf { it > 0.dp } ?: 112.dp
            } else 0.dp
            Box(Modifier.padding(bottom = composerFabClearance)) {
                AnimatedAppFab(state = fabState) { action ->
                    when (action) {
                        FabState.NewCourse -> { newName = ""; courseCreation = CourseCreation.Plain }
                        is FabState.NewRecord -> { newName = ""; creatingRecordForCourse = action.courseId }
                        FabState.None -> Unit
                    }
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = MainDestination.RECORD.route,
            modifier = Modifier.padding(padding),
            enterTransition = {
                val direction = if (mainDestinationForRoute(targetState.destination.route).ordinal >= mainDestinationForRoute(initialState.destination.route).ordinal)
                    AnimatedContentTransitionScope.SlideDirection.Left else AnimatedContentTransitionScope.SlideDirection.Right
                slideIntoContainer(direction, tween(220))
            },
            exitTransition = {
                val direction = if (mainDestinationForRoute(targetState.destination.route).ordinal >= mainDestinationForRoute(initialState.destination.route).ordinal)
                    AnimatedContentTransitionScope.SlideDirection.Left else AnimatedContentTransitionScope.SlideDirection.Right
                slideOutOfContainer(direction, tween(220))
            },
            popEnterTransition = { slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Right, tween(220)) },
            popExitTransition = { slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Right, tween(220)) }
        ) {
            composable(MainDestination.RECORD.route) {
                LaunchedEffect(Unit) { courses.refreshSuggestion() }
                RecordHomeScreen(
                    recent = courseState.recentSessions,
                    startCourse = courseState.courseSummaries.firstOrNull { it.course.id == startCourseId },
                    suggestion = courseState.suggestion,
                    listening = sttState,
                    processing = recordingState.processing,
                    pendingRecordingCounts = recordingState.pendingCounts,
                    pickCourse = { pickingStartCourse = true },
                    start = { mode ->
                        val courseId = startCourseId
                        if (courseId == null) { newName = ""; courseCreation = CourseCreation.ThenStart(mode) }
                        else quickStart(courseId, mode)
                    },
                    stop = stt::endClass,
                    pause = stt::pause,
                    resume = stt::resume,
                    openRecord = { summary ->
                        courses.selectRecord(summary.session.courseId, summary.session.id)
                        nav.navigate("record/${summary.session.id}")
                    },
                    openActiveRecord = { (sttState.activeRecordId ?: sttState.pausedClass?.recordId)?.let { nav.navigate("record/$it") } }
                )
            }
            composable(MainDestination.COURSES.route) {
                CoursesTabScreen(
                    courses = courseState.courseSummaries,
                    openCourse = { id ->
                        courses.enterCourse(id)
                        nav.navigate("course/$id")
                    },
                    courseMenu = { summary -> CourseMenu(summary.course, sttState, courses) }
                )
            }
            composable("ai") {
                GlobalAiScreen(ai, newConversation = openNewConversation) { key, ownerRecordId ->
                    when (key.kind) {
                        com.cmhr.listen.AiContentKind.RESULT -> ownerRecordId?.let {
                            nav.navigate("ai/result/$it/${key.id}")
                        }
                        com.cmhr.listen.AiContentKind.CONVERSATION -> nav.navigate("ai-conversation/${key.id}")
                    }
                }
            }
            composable("ai/new") {
                NewAiConversationScreen(ai, onComposerClearanceChanged = { aiComposerClearance = it }) { conversation ->
                    nav.navigate("ai-conversation/$conversation") {
                        popUpTo("ai/new") { inclusive = true }
                    }
                }
            }
            composable("ai/new/{recordId}") { entry ->
                val id = entry.arguments?.getString("recordId")?.toLongOrNull() ?: return@composable
                NewAiConversationScreen(
                    ai,
                    recordId = id,
                    onComposerClearanceChanged = { aiComposerClearance = it }
                ) { conversation ->
                    nav.navigate("ai-conversation/$conversation") {
                        popUpTo("ai/new/{recordId}") { inclusive = true }
                    }
                }
            }
            composable("course/{courseId}") { entry ->
                val id = entry.arguments?.getString("courseId")?.toLongOrNull() ?: return@composable
                CourseRecordsScreen(id, courseState, sttState, courses, recordingState.pendingCounts, startClass = { mode -> quickStart(id, mode) }) { record ->
                    courses.selectRecord(id, record)
                    nav.navigate("record/$record")
                }
            }
            composable("record/{recordId}") { entry ->
                val id = entry.arguments?.getString("recordId")?.toLongOrNull() ?: return@composable
                LaunchedEffect(id) { recordings.selectRecord(id) }
                RecordDetailsScreen(
                    recordId = id,
                    state = courseState,
                    listening = sttState,
                    developerMode = settingsState.developerMode,
                    aiState = aiState,
                    aiModel = ai,
                    openResult = { nav.navigate("record/$id/ai-result/$it") },
                    openConversation = { nav.navigate("ai-conversation/$it") },
                    recordings = recordingState,
                    startCapture = { mode -> startCapture(id, mode) },
                    stopCapture = stt::endClass,
                    pauseCapture = stt::pause,
                    resumeCapture = stt::resume,
                    recognizeAll = { recordings.recognizeAll(id) },
                    startOfflineRecognition = recordings::startRecognition,
                    stopOfflineRecognition = recordings::stopRecognition,
                    deleteRecording = recordings::deleteRecording,
                    markMoment = {
                        courses.markMoment(id)
                        scope.launch { snackbarHostState.showSnackbar("已标记重点", duration = SnackbarDuration.Short) }
                    },
                    setMarked = { ids, marked -> courses.setMarked(id, ids, marked) { ai.clearSelection() } },
                    copySelection = copySelection,
                    deleteSelection = { confirmDeleteTranscripts = true },
                    summary = currentSummary
                )
            }
            composable("search") {
                SearchScreen(courses) { courseId, recordId ->
                    courses.selectRecord(courseId, recordId)
                    nav.navigate("record/$recordId")
                }
            }
            composable("settings") {
                SettingsScreen(settingsState, settings,
                    onSttService = { nav.navigate("settings/stt-service") },
                    onAiService = { nav.navigate("settings/ai-service") },
                    onVad = { nav.navigate("settings/vad") },
                    onAiPrompts = { nav.navigate("settings/ai-prompts") },
                    onAsrPromptPolicy = { nav.navigate("settings/asr-prompt-policy") },
                    onAiGeneration = { nav.navigate("settings/ai-generation") },
                    onCloudSync = { nav.navigate("settings/cloud-sync") },
                    onAsrDiagnostics = { nav.navigate("settings/asr-diagnostics") },
                    onAppearance = { nav.navigate("settings/appearance") },
                    vadSummary = sttState.selectedVadPreset?.displayName ?: "自定义（基于「${nearestVadPreset(sttState.configuredVadConfig).displayName}」）"
                )
            }
            composable("settings/stt-service") {
                SttServiceSettingsScreen(settingsState, settings, openAsrPrompt = { nav.navigate("settings/asr-prompt-policy") })
            }
            composable("settings/ai-service") {
                AiServiceSettingsScreen(settingsState, settings, openModels = { nav.navigate("settings/ai-service/models") })
            }
            composable("settings/ai-service/models") { AiModelsScreen(settingsState, settings, done = { nav.popBackStack() }) }
            composable("settings/cloud-sync") { CloudSyncSettingsScreen(settingsState, settings) }
            composable("settings/appearance") { AppearanceSettingsScreen(settingsState, settings) }
            composable("settings/ai-prompts") {
                AiPromptsSettingsScreen(settingsState, settings, open = { nav.navigate("settings/ai-prompts/${it.name}") })
            }
            composable("settings/ai-prompts/{kind}") { entry ->
                val kind = entry.arguments?.getString("kind")?.let { name -> AiPromptKind.entries.firstOrNull { it.name == name } }
                if (kind != null) AiPromptEditScreen(kind, settingsState, settings, done = { nav.popBackStack() })
            }
            composable("settings/asr-prompt-policy") {
                AsrPromptPolicySettingsScreen(
                    settingsState, settings,
                    courses = courseState.courses,
                    saveCourse = { id, prompt, mode ->
                        courses.updateCourseAsrPrompt(id, prompt)
                        courses.updateCourseAsrPromptMode(id, mode)
                    },
                    openThresholds = { nav.navigate("settings/asr-prompt-policy/thresholds") }
                )
            }
            composable("settings/asr-prompt-policy/thresholds") { AsrPromptThresholdsScreen(settingsState, settings) }
            composable("settings/ai-generation") { AiGenerationSettingsScreen(settingsState, settings) }
            composable("settings/asr-diagnostics") {
                if (!settingsState.developerMode) {
                    LaunchedEffect(Unit) { nav.navigate("settings") { popUpTo("settings/asr-diagnostics") { inclusive = true } } }
                } else {
                    val diagnosticRecordId = sttState.activeRecordId ?: courseState.selectedRecord?.id
                    val diagnosticRecordName = if (sttState.activeRecordId != null) {
                        sttState.currentRecordName
                    } else {
                        courseState.selectedRecord?.name
                    }
                    val diagnostics by remember(diagnosticRecordId) {
                        diagnosticRecordId?.let { stt.observeRecentAsrDiagnostics(it) }
                            ?: flowOf(emptyList())
                    }.collectAsStateWithLifecycle(initialValue = emptyList())
                    val diagnosticCount by remember(diagnosticRecordId) {
                        diagnosticRecordId?.let(stt::observeAsrDiagnosticCount) ?: flowOf(0)
                    }.collectAsStateWithLifecycle(initialValue = 0)
                    val activeDiagnostics by remember(diagnosticRecordId) {
                        diagnosticRecordId?.let(stt::observeActiveAsrDiagnostics) ?: flowOf(emptyList())
                    }.collectAsStateWithLifecycle(initialValue = emptyList())
                    val recentCountsSince = remember(diagnosticRecordId) {
                        System.currentTimeMillis() - 24L * 60L * 60L * 1_000L
                    }
                    val recentCounts by remember(diagnosticRecordId, recentCountsSince) {
                        diagnosticRecordId?.let { stt.observeAsrStateCounts(it, recentCountsSince) }
                            ?: flowOf(AsrDiagnosticStateCounts(0, 0, 0))
                    }.collectAsStateWithLifecycle(initialValue = AsrDiagnosticStateCounts(0, 0, 0))
                    val runtimeSummary by remember(diagnosticRecordId) {
                        diagnosticRecordId?.let(stt::observeAsrRuntimeSummary)
                            ?: flowOf(AsrRuntimeSummary(0, 0))
                    }
                        .collectAsStateWithLifecycle(initialValue = AsrRuntimeSummary(0, 0))
                    val vadDiagnostics by stt.vadDiagnosticsState.collectAsStateWithLifecycle()
                    val healthRefreshing by stt.isAsrHealthRefreshing.collectAsStateWithLifecycle()
                    AsrDiagnosticsScreen(
                        state = sttState,
                        vadState = vadDiagnostics,
                        currentRecordId = diagnosticRecordId,
                        currentRecordName = diagnosticRecordName,
                        diagnostics = diagnostics,
                        totalCount = diagnosticCount,
                        events = stt::observeAsrEvents,
                        refreshHealth = stt::refreshAsrHealth,
                        confirmRetryUnknown = stt::confirmRetryUnknown,
                        openHistory = { nav.navigate("settings/asr-diagnostics/history/$it") },
                        activeDiagnostics = activeDiagnostics,
                        recentCounts = recentCounts,
                        runtimeSummary = runtimeSummary,
                        healthRefreshing = healthRefreshing
                    )
                }
            }
            composable("settings/asr-diagnostics/history/{recordId}") { entry ->
                if (!settingsState.developerMode) {
                    LaunchedEffect(Unit) {
                        nav.navigate("settings") {
                            popUpTo("settings/asr-diagnostics/history/{recordId}") { inclusive = true }
                        }
                    }
                } else {
                    val id = entry.arguments?.getString("recordId")?.toLongOrNull() ?: return@composable
                    val diagnostics by remember(id) { stt.observeAsrDiagnostics(id) }
                        .collectAsStateWithLifecycle(initialValue = emptyList())
                    val recordName = courseState.selectedRecord?.takeIf { it.id == id }?.name
                        ?: sttState.currentRecordName?.takeIf { sttState.activeRecordId == id }
                    AsrDiagnosticsHistoryScreen(
                        recordName = recordName,
                        diagnostics = diagnostics,
                        events = stt::observeAsrEvents,
                        confirmRetryUnknown = stt::confirmRetryUnknown
                    )
                }
            }
            composable("settings/vad") { VadSettingsScreen(sttState.configuredVadConfig, sttState.selectedVadPreset, stt) }
            composable("record/{recordId}/ai-results") { entry ->
                val id = entry.arguments?.getString("recordId")?.toLongOrNull() ?: return@composable
                AiResultsScreen(id, ai) { key ->
                    when (key.kind) {
                        com.cmhr.listen.AiContentKind.RESULT -> nav.navigate("record/$id/ai-result/${key.id}")
                        com.cmhr.listen.AiContentKind.CONVERSATION -> nav.navigate("ai-conversation/${key.id}")
                    }
                }
            }
            composable("record/{recordId}/ai-result/{resultId}") { entry ->
                val resultId = entry.arguments?.getString("resultId")?.toLongOrNull() ?: return@composable
                AiResultDetailScreen(
                    resultId,
                    ai,
                    settingsState.developerMode,
                    onComposerClearanceChanged = { aiComposerClearance = it }
                )
            }
            composable("ai/result/{recordId}/{resultId}") { entry ->
                val resultId = entry.arguments?.getString("resultId")?.toLongOrNull() ?: return@composable
                AiResultDetailScreen(
                    resultId,
                    ai,
                    settingsState.developerMode,
                    onComposerClearanceChanged = { aiComposerClearance = it }
                )
            }
            composable("record/{recordId}/ai-conversations") { entry ->
                val id = entry.arguments?.getString("recordId")?.toLongOrNull() ?: return@composable
                AiConversationsScreen(id, ai) { nav.navigate("ai-conversation/$it") }
            }
            composable("ai-conversation/{conversationId}") { entry ->
                val id = entry.arguments?.getString("conversationId")?.toLongOrNull() ?: return@composable
                AiConversationScreen(
                    id,
                    ai,
                    settingsState.developerMode,
                    onComposerClearanceChanged = { aiComposerClearance = it }
                )
            }
        }
    }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RecordSelectionTopBar(
    selectedCount: Int,
    close: () -> Unit,
    selectAll: () -> Unit
) = TopAppBar(
    navigationIcon = {
        IconButton(onClick = close) { Icon(Icons.Outlined.Close, contentDescription = "退出选择") }
    },
    title = { Text("已选 $selectedCount 段") },
    actions = { TextButton(onClick = selectAll, modifier = Modifier.testTag("select-all")) { Text("全选") } },
    colors = TopAppBarDefaults.topAppBarColors(
        containerColor = MaterialTheme.colorScheme.primaryContainer,
        titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        actionIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        navigationIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer
    )
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AiContentSelectionTopBar(
    selectedCount: Int,
    close: () -> Unit,
    export: () -> Unit,
    delete: () -> Unit
) = TopAppBar(
    navigationIcon = { IconButton(onClick = close) { Icon(Icons.Outlined.Close, contentDescription = "退出选择") } },
    title = { Text("已选择 $selectedCount 项") },
    actions = {
        TextButton(onClick = export, enabled = selectedCount > 0) { Text("导出") }
        TextButton(onClick = delete, enabled = selectedCount > 0) { Text("删除") }
    },
    colors = TopAppBarDefaults.topAppBarColors(
        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
        titleContentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        actionIconContentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        navigationIconContentColor = MaterialTheme.colorScheme.onTertiaryContainer
    )
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RecordNormalTopBar(
    title: String,
    subtitle: String?,
    course: com.cmhr.listen.data.course.CourseEntity?,
    menuExpanded: Boolean,
    setMenuExpanded: (Boolean) -> Unit,
    back: () -> Unit,
    exportTxt: () -> Unit,
    select: () -> Unit,
    editAsrPrompt: () -> Unit
) = TopAppBar(
    title = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            course?.let { CourseBadge(it.id, it.name, 32.dp, Modifier.padding(end = 10.dp)) }
            Column {
                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                subtitle?.let {
                    Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    },
    navigationIcon = {
        IconButton(onClick = back) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回") }
    },
    actions = {
        IconButton(onClick = { setMenuExpanded(true) }) {
            Icon(Icons.Outlined.MoreVert, contentDescription = "更多操作")
        }
        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { setMenuExpanded(false) },
            modifier = Modifier.widthIn(min = 220.dp),
            shape = RoundedCornerShape(20.dp)
        ) {
            DropdownMenuItem(text = { Text("选择片段") }, onClick = select)
            DropdownMenuItem(text = { Text("导出 TXT") }, onClick = exportTxt)
            HorizontalDivider()
            DropdownMenuItem(text = { Text("专业词提示") }, onClick = editAsrPrompt)
        }
    }
)

private fun Boolean.toInt() = if (this) 1 else 0

private const val FAB_ANIMATION_DURATION_MS = 250

@Composable
private fun AnimatedAppFab(state: FabState, click: (FabState) -> Unit) {
    if (state == FabState.None) return
    val slideProgress = remember(state) { Animatable(1f) }
    LaunchedEffect(state) {
        slideProgress.animateTo(
            targetValue = 0f,
            animationSpec = tween(FAB_ANIMATION_DURATION_MS, easing = FastOutSlowInEasing)
        )
    }
    Box(
        Modifier.graphicsLayer {
            translationX = (size.width + 24.dp.toPx()) * slideProgress.value
        }
    ) {
        when (state) {
            FabState.None -> Unit
            FabState.NewCourse -> AnimatedFabContent("新建课程", Icons.Outlined.Add) { click(state) }
            is FabState.NewRecord -> AnimatedFabContent("新建课堂记录", Icons.Outlined.Add) { click(state) }
        }
    }
}

@Composable
private fun AnimatedFabContent(
    label: String,
    icon: ImageVector,
    click: () -> Unit
) {
    ExtendedFloatingActionButton(
        modifier = Modifier.testTag("global-fab").height(64.dp),
        text = { Text(label, style = androidx.compose.material3.MaterialTheme.typography.titleMedium) },
        icon = { Icon(icon, contentDescription = label) },
        onClick = click,
        containerColor = androidx.compose.material3.MaterialTheme.colorScheme.primaryContainer,
        contentColor = androidx.compose.material3.MaterialTheme.colorScheme.onPrimaryContainer,
        shape = RoundedCornerShape(22.dp)
    )
}

@Composable
internal fun AppFab(label: String, icon: ImageVector, click: () -> Unit) {
    ExtendedFloatingActionButton(
        modifier = Modifier.testTag("global-fab"),
        text = { Text(label) },
        icon = { Icon(icon, contentDescription = label) },
        onClick = click
    )
}
