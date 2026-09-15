package com.cmhr.listen

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cmhr.listen.data.ai.AiAttachmentKind
import com.cmhr.listen.data.ai.AiAttachmentStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AiCameraCaptureTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun cameraTargetUsesFileProviderAndCapturedPhotoUsesExistingImagePipeline() = runBlocking {
        val store = AiAttachmentStore(context)
        val target = store.createCameraCaptureTarget()
        val cameraFile = File(target.absolutePath)
        try {
            assertEquals("content", target.uri.scheme)
            assertTrue(cameraFile.isFile)
            cameraFile.outputStream().use { output ->
                Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888).run {
                    assertTrue(compress(Bitmap.CompressFormat.JPEG, 90, output))
                    recycle()
                }
            }

            val prepared = store.prepare(target.uri, AiAttachmentKind.IMAGE)
            try {
                assertEquals(AiAttachmentKind.IMAGE, prepared.kind)
                assertEquals("image/jpeg", prepared.mimeType)
                assertEquals(64, prepared.width)
                assertEquals(48, prepared.height)
                assertTrue(File(prepared.absolutePath).isFile)
            } finally {
                store.discard(prepared)
            }
        } finally {
            store.discardCameraCapture(target)
        }
        assertFalse(cameraFile.exists())
    }
}
