package com.xyreader.data

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.graphics.BitmapFactory
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GroupCoverRepoTest {

    @Test
    fun validCoverIsSampledAndInvalidReplacementKeepsCurrentCover() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val repository = AppGraph.libraryRepository(context)
        val groupId = repository.addGroup("封面验收-${System.currentTimeMillis()}")
        val source = File(context.cacheDir, "picked-group-${System.currentTimeMillis()}.png")
        val image = Bitmap.createBitmap(1600, 2000, Bitmap.Config.ARGB_8888).apply {
            eraseColor(0xFF496A82.toInt())
        }
        FileOutputStream(source).use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        image.recycle()

        assertTrue(repository.importGroupCover(groupId, Uri.fromFile(source)))
        val dao = ArkDatabase.getInstance(context).groupDao()
        val savedPath = dao.getGroupById(groupId)?.coverPath
        assertNotNull(savedPath)
        val savedFile = File(savedPath!!)
        assertTrue(savedFile.isFile)
        assertTrue(savedFile.canonicalPath.contains("group_covers"))
        val savedBitmap = BitmapFactory.decodeFile(savedPath)!!
        assertTrue(savedBitmap.width <= 512)
        assertTrue(savedBitmap.height <= 768)
        savedBitmap.recycle()

        val invalid = File(context.cacheDir, "invalid-group-${System.currentTimeMillis()}.img")
            .apply { writeText("not an image") }
        assertFalse(repository.importGroupCover(groupId, Uri.fromFile(invalid)))
        assertEquals(savedPath, dao.getGroupById(groupId)?.coverPath)
        assertTrue(savedFile.exists())

        assertTrue(repository.setGroupCover(groupId, null))
        assertNull(dao.getGroupById(groupId)?.coverPath)
        assertTrue(savedFile.exists()) // 清除只断开引用，旧文件延迟清理
    }

    @Test
    fun missingGroupCannotReceiveCover() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val repository = AppGraph.libraryRepository(context)
        assertFalse(repository.setGroupCover(Long.MAX_VALUE, null))
    }
}
