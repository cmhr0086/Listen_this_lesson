package com.cmhr.listen.data.device

import com.cmhr.listen.data.settings.AppSettingsRepository
import com.cmhr.listen.data.settings.SECTION_AI
import com.cmhr.listen.data.settings.SECTION_PUBLISH
import com.cmhr.listen.data.settings.SECTION_STT
import com.cmhr.listen.data.settings.SECTION_SYNC
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The sections that differ between two copies of this phone's settings. */
internal fun changedSections(before: CloudConfig, now: CloudConfig): Set<String> = buildSet {
    if (before.stt != now.stt) add(SECTION_STT)
    if (before.ai != now.ai) add(SECTION_AI)
    if (before.sync != now.sync) add(SECTION_SYNC)
    if (before.publish != now.publish) add(SECTION_PUBLISH)
}

/** Sends this phone's edits up and takes the account's settings back. */
class CloudConfigSync(
    private val settings: AppSettingsRepository,
    private val client: DeviceApiClient = DeviceApiClient()
) {
    private val running = Mutex()

    suspend fun sync() = running.withLock {
        val token = settings.readDeviceToken() ?: return@withLock
        val dirty = settings.settings.first().device.dirty
        val offered = if (settings.isOfferPending()) settings.configuredSections() else emptySet()
        val changed = settings.readCloudSections(dirty)
        val request = ConfigSyncRequest(changed = changed, offered = settings.readCloudSections(offered - dirty))
        val response = try {
            client.syncConfig(token, request)
        } catch (error: DeviceApiException) {
            if (error.httpCode == 401) settings.forgetRemovedDevice()
            throw error
        }
        settings.applyCloudConfig(response.config, dirty, changedSections(changed, settings.readCloudSections(dirty)))
    }

    /** Best effort on the server's side: the phone stops following the cloud either way. */
    suspend fun unbind() = running.withLock {
        settings.readDeviceToken()?.let { token -> runCatching { client.unbind(token) } }
        settings.unbindDevice()
    }
}
