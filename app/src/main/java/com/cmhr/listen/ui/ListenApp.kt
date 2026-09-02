package com.cmhr.listen.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
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
import com.cmhr.listen.SttViewModel
import com.cmhr.listen.data.ai.AiActionType
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flowOf

private enum class MainDestination(val route: String, val label: String) {
    COURSES("courses", "课程"),
    SETTINGS("settings", "设置")
}

private sealed interface FabState {
    data object None : FabState
    data object NewCourse : FabState
    data class NewRecord(val courseId: Long) : FabState
    data class StartListening(val recordId: Long) : FabState
}

private fun isSettingsRoute(route: String?): Boolean = route == "settings" || route?.startsWith("settings/") == true
private fun isAiWorkspaceRoute(route: String?): Boolean =
    route?.contains("ai-results") == true || route?.contains("ai-result/") == true || route?.startsWith("ai-conversation/") == true

private fun routeTitle(route: String?): String = when (route) {
    "courses" -> "课程"
    "course/{courseId}" -> "课堂记录"
    "record/{recordId}" -> "记录详情"
    "settings" -> "设置"
    "settings/stt-service" -> "STT 服务器"
    "settings/vad-parameters" -> "VAD 参数"
    "settings/vad-presets" -> "VAD 预设"
    "settings/ai-service" -> "AI 配置"
    "settings/ai-prompts" -> "AI 提示词"
    "settings/asr-prompt-policy" -> "ASR 提示词模式"
    "settings/ai-generation" -> "AI 生成参数"
    "record/{recordId}/ai-results" -> "AI 结果"
    "record/{recordId}/ai-result/{resultId}" -> "AI 结果详情"
    "record/{recordId}/ai-conversations" -> "AI 对话"
    "ai-conversation/{conversationId}" -> "课堂问答"
    else -> "课堂提问识别助手"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListenApp(
    stt: SttViewModel = viewModel(),
    courses: CourseViewModel = viewModel(),
    settings: SettingsViewModel = viewModel(),
    ai: AiViewModel = viewModel()
) {
    val nav = rememberNavController()
    val backStackEntry by nav.currentBackStackEntryAsState()
    val route = backStackEntry?.destination?.route ?: "courses"
    val sttState by stt.uiState.collectAsStateWithLifecycle()
    val courseState by courses.uiState.collectAsStateWithLifecycle()
    val settingsState by settings.uiState.collectAsStateWithLifecycle()
    val aiState by ai.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    var creatingCourse by remember { mutableStateOf(false) }
    var creatingRecordForCourse by remember { mutableStateOf<Long?>(null) }
    var newName by remember { mutableStateOf("") }
    var pendingPermissionRecordId by remember { mutableStateOf<Long?>(null) }
    var recordMenuExpanded by remember { mutableStateOf(false) }
    var showSelectionAiActions by remember { mutableStateOf(false) }
    var editingRecordCoursePrompt by remember { mutableStateOf(false) }
    var promptDraft by remember { mutableStateOf("") }
    var promptModeDraft by remember { mutableStateOf<String?>(null) }
    var exportContent by remember { mutableStateOf<String?>(null) }
    var requestedFullAiAction by remember { mutableStateOf<AiActionType?>(null) }
    var confirmDeleteAiContents by remember { mutableStateOf(false) }
    var confirmDeleteTranscripts by remember { mutableStateOf(false) }
    var aiContextMenuExpanded by remember { mutableStateOf(false) }
    var showAiContext by remember { mutableStateOf(false) }

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
    val contentSelectionMode = route == "record/{recordId}/ai-results" && recordId != null && aiState.contentSelectionRecordId == recordId
    val currentRecord = courseState.selectedRecord?.takeIf { it.id == recordId }
    val currentCourse = currentRecord?.let { record -> courseState.courses.firstOrNull { it.id == record.courseId } }
    val currentSegments = courseState.detailSegments.filter { it.recordId == recordId }

    BackHandler(enabled = selectionMode || contentSelectionMode) {
        if (selectionMode) {
            showSelectionAiActions = false
            ai.clearSelection()
        } else ai.clearContentSelection()
    }
    LaunchedEffect(settings) {
        settings.messages.collectLatest { message ->
            snackbarHostState.showSnackbar(message.text, duration = SnackbarDuration.Short)
        }
    }
    LaunchedEffect(route) {
        recordMenuExpanded = false
        aiContextMenuExpanded = false
        showAiContext = false
        if (!selectionMode) showSelectionAiActions = false
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

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        val recordId = pendingPermissionRecordId
        pendingPermissionRecordId = null
        val microphoneGranted = grants[Manifest.permission.RECORD_AUDIO]
            ?: (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
        val notificationGranted = Build.VERSION.SDK_INT < 33 || grants[Manifest.permission.POST_NOTIFICATIONS]
            ?: (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
        if (microphoneGranted && recordId != null) {
            stt.startListening(recordId)
            if (!notificationGranted) stt.reportNotificationPermissionDenied()
        } else stt.reportPermissionDenied()
    }

    if (creatingCourse) NameDialog(
        title = "新建课程",
        value = newName,
        update = { newName = it },
        confirm = { courses.createCourse(newName); newName = ""; creatingCourse = false },
        dismiss = { creatingCourse = false }
    )
    creatingRecordForCourse?.let { courseId ->
        NameDialog(
            title = "新建课堂记录",
            value = newName,
            update = { newName = it },
            confirm = { courses.createRecord(courseId, newName); newName = ""; creatingRecordForCourse = null },
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
    if (confirmDeleteAiContents && recordId != null) TimedDeleteDialog(
        title = "删除 AI 内容",
        message = "将删除选中的 ${aiState.selectedContentKeys.size} 项 AI 结果或对话，原始识别文本不会受影响。",
        confirm = {
                ai.deleteSelectedContents(recordId)
                confirmDeleteAiContents = false
        },
        dismiss = { confirmDeleteAiContents = false }
    )
    if (confirmDeleteTranscripts && recordId != null) TimedDeleteDialog(
        title = "删除识别片段",
        message = "将永久删除选中的 ${aiState.selectedSegmentIds.size} 条原始识别片段。已保存的 AI 冻结快照和输出会保留。",
        confirm = {
            courses.deleteSegments(recordId, aiState.selectedSegmentIds) { ai.clearSelection() }
            confirmDeleteTranscripts = false
        },
        dismiss = { confirmDeleteTranscripts = false }
    )
    if (showAiContext && aiContextSnapshot != null) {
        AiContextBottomSheet(snapshot = aiContextSnapshot, dismiss = { showAiContext = false })
    }

    val nested = route != "courses" && route != "settings"
    val fabState: FabState = when {
        isAiWorkspaceRoute(route) -> FabState.None
        sttState.isListening -> FabState.None
        route == "courses" -> FabState.NewCourse
        route == "course/{courseId}" && courseId != null -> FabState.NewRecord(courseId)
        route == "record/{recordId}" && recordId != null && !selectionMode -> FabState.StartListening(recordId)
        else -> FabState.None
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            when {
                contentSelectionMode -> AiContentSelectionTopBar(
                    selectedCount = aiState.selectedContentKeys.size,
                    close = ai::clearContentSelection,
                    export = {
                        val record = currentRecord
                        val course = currentCourse
                        if (record != null && course != null) {
                            ai.buildSelectedContentsTxt(recordId, course.name, record.name) { content ->
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
                    aiEnabled = aiState.selectedSegmentIds.isNotEmpty() && !aiState.isBusy,
                    close = ai::clearSelection,
                    process = { showSelectionAiActions = true },
                    delete = { confirmDeleteTranscripts = true }
                )
                route == "record/{recordId}" && recordId != null -> RecordNormalTopBar(
                    menuExpanded = recordMenuExpanded,
                    setMenuExpanded = { recordMenuExpanded = it },
                    back = { nav.popBackStack() },
                    summary = {
                        recordMenuExpanded = false
                        requestedFullAiAction = AiActionType.SUMMARY
                    },
                    organizeNotes = {
                        recordMenuExpanded = false
                        requestedFullAiAction = AiActionType.ORGANIZE_NOTES
                    },
                    exportTxt = {
                        recordMenuExpanded = false
                        val course = currentCourse
                        if (course != null) {
                            val record = requireNotNull(currentRecord)
                            exportContent = buildTxt(course, record, currentSegments)
                            exportLauncher.launch("${record.name}.txt")
                        }
                    },
                    openResults = { recordMenuExpanded = false; nav.navigate("record/$recordId/ai-results") },
                    select = { recordMenuExpanded = false; ai.beginSelection(recordId) },
                    editAsrPrompt = {
                        recordMenuExpanded = false
                        promptDraft = currentCourse?.asrPrompt.orEmpty()
                        promptModeDraft = currentCourse?.asrPromptModeOverride
                        editingRecordCoursePrompt = currentCourse != null
                    }
                )
                route == "record/{recordId}/ai-result/{resultId}" || route == "ai-conversation/{conversationId}" -> TopAppBar(
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
                else -> TopAppBar(
                    title = { Text(routeTitle(route)) },
                    navigationIcon = {
                        if (nested) IconButton(onClick = { nav.popBackStack() }) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                        }
                    }
                )
            }
        },
        bottomBar = {
            NavigationBar {
                MainDestination.entries.forEach { destination ->
                    val selected = if (destination == MainDestination.SETTINGS) isSettingsRoute(route) else !isSettingsRoute(route)
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            nav.navigate(destination.route) {
                                launchSingleTop = true
                                popUpTo("courses") { saveState = true }
                                restoreState = true
                            }
                        },
                        icon = {
                            Icon(
                                if (destination == MainDestination.COURSES) Icons.Outlined.School else Icons.Outlined.Settings,
                                contentDescription = destination.label
                            )
                        },
                        label = { Text(destination.label) }
                    )
                }
            }
        },
        floatingActionButton = {
            AnimatedAppFab(state = fabState) { action ->
                when (action) {
                    FabState.NewCourse -> { newName = ""; creatingCourse = true }
                    is FabState.NewRecord -> { newName = ""; creatingRecordForCourse = action.courseId }
                    is FabState.StartListening -> {
                        val microphoneGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                        val notificationGranted = Build.VERSION.SDK_INT < 33 ||
                            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                        if (microphoneGranted && notificationGranted) {
                            stt.startListening(action.recordId)
                        } else {
                            pendingPermissionRecordId = action.recordId
                            val permissions = buildList {
                                if (!microphoneGranted) add(Manifest.permission.RECORD_AUDIO)
                                if (Build.VERSION.SDK_INT >= 33 && !notificationGranted) add(Manifest.permission.POST_NOTIFICATIONS)
                            }
                            permissionLauncher.launch(permissions.toTypedArray())
                        }
                    }
                    FabState.None -> Unit
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = "courses",
            modifier = Modifier.padding(padding),
            enterTransition = {
                val direction = if (isSettingsRoute(targetState.destination.route).toInt() >= isSettingsRoute(initialState.destination.route).toInt())
                    AnimatedContentTransitionScope.SlideDirection.Left else AnimatedContentTransitionScope.SlideDirection.Right
                slideIntoContainer(direction, tween(220))
            },
            exitTransition = {
                val direction = if (isSettingsRoute(targetState.destination.route).toInt() >= isSettingsRoute(initialState.destination.route).toInt())
                    AnimatedContentTransitionScope.SlideDirection.Left else AnimatedContentTransitionScope.SlideDirection.Right
                slideOutOfContainer(direction, tween(220))
            },
            popEnterTransition = { slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Right, tween(220)) },
            popExitTransition = { slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Right, tween(220)) }
        ) {
            composable("courses") {
                CoursesScreen(courseState, sttState, courses) { id ->
                    courses.enterCourse(id)
                    nav.navigate("course/$id")
                }
            }
            composable("course/{courseId}") { entry ->
                val id = entry.arguments?.getString("courseId")?.toLongOrNull() ?: return@composable
                CourseRecordsScreen(id, courseState, sttState, courses) { record ->
                    courses.selectRecord(id, record)
                    nav.navigate("record/$record")
                }
            }
            composable("record/{recordId}") { entry ->
                val id = entry.arguments?.getString("recordId")?.toLongOrNull() ?: return@composable
                RecordDetailsScreen(
                    recordId = id,
                    state = courseState,
                    listening = sttState,
                    developerMode = settingsState.developerMode,
                    aiState = aiState,
                    aiModel = ai,
                    showAiActions = showSelectionAiActions,
                    dismissAiActions = { showSelectionAiActions = false },
                    requestedFullAction = requestedFullAiAction,
                    consumeFullAction = { requestedFullAiAction = null },
                    openResult = { nav.navigate("record/$id/ai-result/$it") },
                    openConversation = { nav.navigate("ai-conversation/$it") },
                    stopListening = stt::stopListening
                )
            }
            composable("settings") {
                SettingsScreen(settingsState, settings,
                    onSttService = { nav.navigate("settings/stt-service") },
                    onAiService = { nav.navigate("settings/ai-service") },
                    onVadParameters = { nav.navigate("settings/vad-parameters") },
                    onVadPresets = { nav.navigate("settings/vad-presets") },
                    onAiPrompts = { nav.navigate("settings/ai-prompts") },
                    onAsrPromptPolicy = { nav.navigate("settings/asr-prompt-policy") },
                    onAiGeneration = { nav.navigate("settings/ai-generation") }
                )
            }
            composable("settings/stt-service") { SttServiceSettingsScreen(settingsState, settings) }
            composable("settings/ai-service") { AiServiceSettingsScreen(settingsState, settings) }
            composable("settings/ai-prompts") { AiPromptsSettingsScreen(settingsState, settings) }
            composable("settings/asr-prompt-policy") { AsrPromptPolicySettingsScreen(settingsState, settings) }
            composable("settings/ai-generation") { AiGenerationSettingsScreen(settingsState, settings) }
            composable("settings/vad-parameters") { VadParametersScreen(sttState.configuredVadConfig, stt) }
            composable("settings/vad-presets") { VadPresetsScreen(sttState.selectedVadPreset, stt) }
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
                AiResultDetailScreen(resultId, ai, settingsState.developerMode)
            }
            composable("record/{recordId}/ai-conversations") { entry ->
                val id = entry.arguments?.getString("recordId")?.toLongOrNull() ?: return@composable
                AiConversationsScreen(id, ai) { nav.navigate("ai-conversation/$it") }
            }
            composable("ai-conversation/{conversationId}") { entry ->
                val id = entry.arguments?.getString("conversationId")?.toLongOrNull() ?: return@composable
                AiConversationScreen(id, ai, settingsState.developerMode)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RecordSelectionTopBar(
    selectedCount: Int,
    aiEnabled: Boolean,
    close: () -> Unit,
    process: () -> Unit,
    delete: () -> Unit
) = TopAppBar(
    navigationIcon = {
        IconButton(onClick = close) { Icon(Icons.Outlined.Close, contentDescription = "退出选择") }
    },
    title = { Text("已选择 $selectedCount 条") },
    actions = {
        IconButton(onClick = delete, enabled = selectedCount > 0) { Icon(Icons.Outlined.Delete, contentDescription = "删除") }
        TextButton(onClick = process, enabled = aiEnabled) { Text("AI 处理") }
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
    menuExpanded: Boolean,
    setMenuExpanded: (Boolean) -> Unit,
    back: () -> Unit,
    summary: () -> Unit,
    organizeNotes: () -> Unit,
    exportTxt: () -> Unit,
    openResults: () -> Unit,
    select: () -> Unit,
    editAsrPrompt: () -> Unit
) = TopAppBar(
    title = { Text("记录详情") },
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
            modifier = Modifier.widthIn(min = 240.dp),
            shape = RoundedCornerShape(20.dp)
        ) {
            DropdownMenuItem(text = { Text("总结") }, onClick = summary)
            DropdownMenuItem(text = { Text("整理成笔记") }, onClick = organizeNotes)
            HorizontalDivider()
            DropdownMenuItem(text = { Text("导出 TXT") }, onClick = exportTxt)
            DropdownMenuItem(text = { Text("AI 结果") }, onClick = openResults)
            HorizontalDivider()
            DropdownMenuItem(text = { Text("选择") }, onClick = select)
            DropdownMenuItem(text = { Text("ASR 提示词") }, onClick = editAsrPrompt)
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
            is FabState.StartListening -> AnimatedFabContent("开始监听", Icons.Outlined.Mic) { click(state) }
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
