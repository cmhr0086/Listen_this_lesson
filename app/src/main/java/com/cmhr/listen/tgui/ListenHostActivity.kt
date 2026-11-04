package com.cmhr.listen.tgui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import com.cmhr.listen.AiViewModel
import com.cmhr.listen.AppNavigationRequests
import com.cmhr.listen.CourseViewModel
import com.cmhr.listen.DemoData
import com.cmhr.listen.ListeningForegroundService
import com.cmhr.listen.PublishViewModel
import com.cmhr.listen.RecordingViewModel
import com.cmhr.listen.SettingsViewModel
import com.cmhr.listen.SttViewModel
import com.cmhr.listen.recording.CaptureMode
import com.cmhr.listen.ui.theme.DarkModePreference
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.telegram.ui.ActionBar.BaseFragment
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.TelegramActivity
import org.telegram.ui.TelegramUI

/** Native Activity host; no ComponentActivity/Compose bridge is required. */
open class ListenHostActivity : TelegramActivity(), LifecycleOwner, ViewModelStoreOwner {
    private val registry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = registry
    override val viewModelStore: ViewModelStore by lazy { (lastNonConfigurationInstance as? ViewModelStore) ?: ViewModelStore() }
    private val models by lazy { ViewModelProvider(this, ViewModelProvider.AndroidViewModelFactory.getInstance(application)) }
    val courses: CourseViewModel get() = models[CourseViewModel::class.java]
    val stt: SttViewModel get() = models[SttViewModel::class.java]
    val settings: SettingsViewModel get() = models[SettingsViewModel::class.java]
    val ai: AiViewModel get() = models[AiViewModel::class.java]
    val publish: PublishViewModel get() = models[PublishViewModel::class.java]
    val recordings: RecordingViewModel get() = models[RecordingViewModel::class.java]
    private var pendingFiles: ((List<Uri>) -> Unit)? = null
    private var pendingCamera: ((Boolean) -> Unit)? = null
    private var pendingCapture: CaptureRequest? = null
    private var restoredRoute: String? = null
    private data class CaptureRequest(val id: Long, val newClass: Boolean, val mode: CaptureMode)

    override fun onCreate(savedInstanceState: Bundle?) {
        restoredRoute = savedInstanceState?.getString("listen.route")
        super.onCreate(savedInstanceState)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        window.decorView.setViewTreeLifecycleOwner(this)
        window.decorView.setViewTreeViewModelStoreOwner(this)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // ActionBar owns the status-bar inset. The host owns side/cutout and navigation insets.
        ViewCompat.setOnApplyWindowInsetsListener(frameLayout) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, 0, bars.right, if (insets.isVisible(WindowInsetsCompat.Type.ime())) 0 else bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(frameLayout)
        lifecycleScope.launch { DemoData.seedIfEmpty(applicationContext) }
        lifecycleScope.launch {
            settings.uiState.map { it.appearance.darkMode }.distinctUntilChanged().collect { applyAppearance() }
        }
        lifecycleScope.launch { settings.messages.collect { Toast.makeText(this@ListenHostActivity, it.text, Toast.LENGTH_SHORT).show() } }
        lifecycleScope.launch { stt.asrMessages.collect { Toast.makeText(this@ListenHostActivity, it, Toast.LENGTH_SHORT).show() } }
        lifecycleScope.launch { AppNavigationRequests.recordRequests.collect { open("record/$it") } }
        lifecycleScope.launch {
            // Observe the actual capture lifecycle so the notification's End action also files
            // notes. A pause/resume keeps the same class open and must not generate early.
            var capturedRecordId: Long? = null
            stt.uiState.collect { state ->
                if (state.isListening) {
                    capturedRecordId = state.activeRecordId
                } else {
                    val completed = capturedRecordId
                    if (completed != null && state.pausedClass?.recordId != completed && state.resumingRecordId != completed) {
                        capturedRecordId = null
                        ai.autoOrganizeNotes(completed)
                    }
                }
            }
        }
        lifecycleScope.launch {
            var lastBatch: Long? = null
            recordings.uiState.map { it.recognizingAllRecordId }.distinctUntilChanged().collect { current ->
                if (current == null) lastBatch?.let(ai::autoOrganizeNotes)
                lastBatch = current
            }
        }
        val route = restoredRoute ?: intent.getStringExtra("listen.route")
        val recordId = intent.getLongExtra(ListeningForegroundService.EXTRA_RECORD_ID, -1L)
        when {
            restoredRoute != null -> if (restoredRoute != "home") open(restoredRoute!!)
            recordId > 0 -> open("record/$recordId")
            route != null && route != "home" -> open(route)
        }
        lifecycleScope.launch {
            if (settings.showSetup.filterNotNull().first() && route == null && recordId <= 0) open("setup")
        }
    }

    override fun createRootFragment(): BaseFragment = HomeFragment()
    fun open(route: String) {
        val parts = route.split('/')
        val fragment: BaseFragment = when {
            route == "home" -> HomeFragment()
            route == "courses" -> CoursesFragment()
            parts.first() == "course" && parts.size == 2 -> CourseFragment(parts[1].toLong())
            parts.first() == "record" && parts.size == 2 -> ClassroomFragment(parts[1].toLong())
            parts.first() == "transcript" -> TranscriptFragment(parts[1].toLong())
            parts.first() == "recordings" -> RecordingsFragment(parts[1].toLong())
            route == "search" -> SearchFragment()
            route == "timetable" -> TimetableFragment()
            route == "publish" -> PublishHubFragment()
            parts.first() == "publish" -> CoursePublishFragment(parts[1].toLong())
            parts.first() == "materials" -> MaterialsFragment(parts[1].toLong(), parts.getOrNull(2)?.toLongOrNull())
            route == "ai" -> AiListFragment()
            parts.take(3) == listOf("ai", "notes", "new") -> AiNoteGenerationFragment(parts[3].toLong())
            route == "ai/new" -> AiChatFragment()
            parts.take(2) == listOf("ai", "new") -> AiChatFragment(recordId = parts[2].toLong())
            parts.first() == "ai-conversation" -> AiChatFragment(conversationId = parts[1].toLong())
            parts.first() == "record" && parts.getOrNull(2) == "ai-conversations" -> AiListFragment(parts[1].toLong())
            parts.first() == "record" && parts.getOrNull(2) == "ai-results" -> AiResultsFragment(parts[1].toLong())
            parts.first() == "record" && parts.getOrNull(2) == "ai-result" -> AiResultFragment(parts[1].toLong(), parts[3].toLong(), parts.getOrNull(4) == "ask")
            parts.take(2) == listOf("ai", "result") -> AiResultFragment(parts[2].toLong(), parts[3].toLong())
            route == "setup" -> SetupFragment()
            parts.first() == "settings" -> SettingsFragment(route)
            else -> return
        }
        presentFragment(fragment)
    }

    fun applyAppearance() {
        val dark = when (settings.uiState.value.appearance.darkMode) {
            DarkModePreference.DARK -> true
            DarkModePreference.LIGHT -> false
            DarkModePreference.SYSTEM -> resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        }
        if (Theme.getCurrentTheme().isDark != dark) TelegramUI.applyTheme(Theme.getTheme(if (dark) "Dark Blue" else "Blue"), window.decorView)
    }

    override fun onStart() { super.onStart(); registry.handleLifecycleEvent(Lifecycle.Event.ON_START) }
    override fun onResume() { super.onResume(); registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME) }
    override fun onPause() { registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE); super.onPause() }
    override fun onStop() { registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP); super.onStop() }
    override fun onDestroy() {
        mainFragmentsStack.toList().forEach { it.onFragmentDestroy() }
        registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        if (!isChangingConfigurations) viewModelStore.clear()
        super.onDestroy()
    }
    override fun onRetainNonConfigurationInstance(): Any = viewModelStore
    override fun onConfigurationChanged(newConfig: Configuration) { super.onConfigurationChanged(newConfig); applyAppearance() }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent)
        intent.getLongExtra(ListeningForegroundService.EXTRA_RECORD_ID, -1L).takeIf { it > 0 }?.let { open("record/$it") }
        intent.getStringExtra("listen.route")?.let(::open)
    }
    override fun onSaveInstanceState(outState: Bundle) {
        val route = when (val fragment = lastFragment) {
            is AiChatFragment -> fragment.savedRoute
            is ListenListFragment -> fragment.route
            is TranscriptFragment -> fragment.route
            else -> null
        }
        route?.takeIf { it.isNotBlank() }?.let { outState.putString("listen.route", it) }
        super.onSaveInstanceState(outState)
    }

    fun startCapture(id: Long, mode: CaptureMode, newClass: Boolean = false) {
        val current = stt.uiState.value
        if (current.isListening || current.pausedClass != null || current.resumingRecordId != null) {
            Toast.makeText(this, "请先结束当前课堂", Toast.LENGTH_SHORT).show(); return
        }
        pendingCapture = CaptureRequest(id, newClass, mode)
        val missing = buildList {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (missing.isEmpty()) beginCapture() else requestPermissions(missing.toTypedArray(), 203)
    }
    private fun beginCapture() {
        val request = pendingCapture ?: return
        pendingCapture = null
        val begin: (Long) -> Unit = { id ->
            if (request.mode == CaptureMode.RECORD_ONLY) stt.startRecordOnly(id) else stt.startListening(id)
            open("record/$id")
        }
        if (request.newClass) courses.createRecord(request.id, null, begin) else begin(request.id)
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != 203) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            beginCapture()
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) stt.reportNotificationPermissionDenied()
        } else { pendingCapture = null; stt.reportPermissionDenied() }
    }

    fun pickDocument(mime: String = "*/*", multiple: Boolean = false, onResult: (List<Uri>) -> Unit) {
        pendingFiles = onResult
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type = mime; addCategory(Intent.CATEGORY_OPENABLE); putExtra(Intent.EXTRA_ALLOW_MULTIPLE, multiple) }, 201)
    }
    fun chooseDirectory(onResult: (Uri) -> Unit) {
        pendingFiles = { it.firstOrNull()?.let(onResult) }
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION), 201)
    }
    fun createDocument(name: String, mime: String, onResult: (Uri) -> Unit) {
        pendingFiles = { it.firstOrNull()?.let(onResult) }
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply { type = mime; addCategory(Intent.CATEGORY_OPENABLE); putExtra(Intent.EXTRA_TITLE, name) }, 201)
    }
    fun capturePhoto(uri: Uri, onResult: (Boolean) -> Unit) {
        pendingCamera = onResult
        try {
            startActivityForResult(Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                putExtra(MediaStore.EXTRA_OUTPUT, uri)
                clipData = android.content.ClipData.newRawUri("照片", uri)
                addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, 202)
        } catch (_: android.content.ActivityNotFoundException) { pendingCamera = null; onResult(false) }
    }
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 202) { val callback = pendingCamera; pendingCamera = null; callback?.invoke(resultCode == Activity.RESULT_OK) }
        if (requestCode == 201) {
            val callback = pendingFiles; pendingFiles = null
            val uris = if (resultCode == Activity.RESULT_OK) data?.clipData?.let { clips -> (0 until clips.itemCount).map { clips.getItemAt(it).uri } } ?: listOfNotNull(data?.data) else emptyList()
            uris.forEach { uri -> runCatching { contentResolver.takePersistableUriPermission(uri, (data?.flags ?: 0) and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)) } }
            callback?.invoke(uris)
        }
    }
}
