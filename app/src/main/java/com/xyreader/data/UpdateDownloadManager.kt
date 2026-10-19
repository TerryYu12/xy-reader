package com.xyreader.data

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject

internal enum class UpdateDownloadStatus { DOWNLOADING, READY, FAILED }

internal data class UpdateDownloadSnapshot(
    val info: UpdateInfo,
    val status: UpdateDownloadStatus,
    val progress: Int,
    val file: File? = null,
    val error: String? = null,
)

/** 系统持有下载任务；这里只持久化任务引用、查询进度并准备可分享给安装器的文件。 */
internal class UpdateDownloadManager(context: Context) {
    private val appContext = context.applicationContext
    private val downloads = appContext.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    suspend fun enqueue(info: UpdateInfo): Unit = withContext(Dispatchers.IO) {
        taskMutex.withLock {
            val previous = try {
                readRecord()
            } catch (_: JSONException) {
                // 用户明确重试下载时可替换损坏的记录，不删除原来的系统任务或文件。
                null
            } catch (_: ClassCastException) {
                // SharedPreferences 的键被写成其他类型时采用同样的恢复策略。
                null
            }
            if (previous != null) {
                val state = snapshotLocked(previous)
                // 一次只管理一个活动任务；新版本也不能覆盖仍在下载的任务引用。
                if (state.status == UpdateDownloadStatus.DOWNLOADING ||
                    (state.status == UpdateDownloadStatus.READY &&
                        previous.info.assetUrl == info.assetUrl && previous.info.tag == info.tag)
                ) return@withLock
            }
            val uri = Uri.parse(info.assetUrl ?: throw IOException("该版本没有可下载的 APK"))
            if (uri.scheme != "https" || uri.host.isNullOrBlank()) {
                throw IOException("更新包必须使用 HTTPS 下载地址")
            }
            val directory = appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                ?: throw IOException("更新包存储目录不可用")
            val token = UUID.randomUUID().toString()
            val relativePath = "update/$token.apk"
            val source = File(directory, relativePath)
            val sourceDirectory = source.parentFile!!
            if (!sourceDirectory.isDirectory && !sourceDirectory.mkdirs()) {
                throw IOException("无法创建更新包下载目录")
            }
            val request = DownloadManager.Request(uri)
                .setTitle("XY-READER v${info.version}")
                .setDescription("正在下载更新包")
                .setMimeType("application/vnd.android.package-archive")
                .addRequestHeader("User-Agent", "XY-Reader-Updater")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalFilesDir(appContext, Environment.DIRECTORY_DOWNLOADS, relativePath)
            val id = downloads.enqueue(request)
            val record = Record(id, info, source.absolutePath, null)
            writeRecord(record)
        }
    }

    suspend fun snapshot(): UpdateDownloadSnapshot? = withContext(Dispatchers.IO) {
        taskMutex.withLock { readRecord()?.let { snapshotLocked(it) } }
    }

    /** 仅隐藏应用内的任务记录，系统任务和已有文件继续保留。 */
    suspend fun forget(): Unit = withContext(Dispatchers.IO) {
        taskMutex.withLock {
            if (!prefs.edit().remove(KEY_RECORD).commit()) throw IOException("无法保存下载状态")
        }
    }

    private fun snapshotLocked(record: Record): UpdateDownloadSnapshot {
        fun failed(message: String) = UpdateDownloadSnapshot(
            record.info, UpdateDownloadStatus.FAILED, -1, error = message,
        )
        return try {
            downloads.query(DownloadManager.Query().setFilterById(record.id)).use { cursor ->
                if (cursor == null || !cursor.moveToFirst()) {
                    return failed("系统下载任务已不存在，请重新下载")
                }
                val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                val total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                val downloaded = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                when (status) {
                    DownloadManager.STATUS_PENDING, DownloadManager.STATUS_RUNNING, DownloadManager.STATUS_PAUSED -> {
                        val progress = if (total > 0L) {
                            (downloaded.toDouble() / total * 100).toInt().coerceIn(0, 100)
                        } else -1
                        UpdateDownloadSnapshot(record.info, UpdateDownloadStatus.DOWNLOADING, progress)
                    }
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        val source = File(record.sourcePath)
                        if (!source.isFile) return failed("下载的更新包已丢失，请重新下载")
                        validateDownloadSizes(record.info.assetSize, total, source.length())
                        val cached = record.cachePath?.let(::File)
                        val file = if (cached != null && cached.isFile && cached.length() == source.length()) {
                            cached
                        } else {
                            val cacheDirectory = File(appContext.cacheDir, "update")
                            if (!cacheDirectory.isDirectory && !cacheDirectory.mkdirs()) {
                                throw IOException("无法创建更新包缓存目录")
                            }
                            val target = File(cacheDirectory, "${UUID.randomUUID()}.apk")
                            source.copyTo(target)
                            validateDownloadSizes(record.info.assetSize, total, target.length())
                            writeRecord(record.copy(cachePath = target.absolutePath))
                            target
                        }
                        UpdateDownloadSnapshot(record.info, UpdateDownloadStatus.READY, 100, file)
                    }
                    DownloadManager.STATUS_FAILED -> {
                        val reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                        failed(downloadFailureMessage(reason))
                    }
                    else -> failed("无法识别系统下载状态：$status")
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed(e.message ?: "无法读取更新包下载状态")
        }
    }

    private fun readRecord(): Record? {
        val raw = prefs.getString(KEY_RECORD, null) ?: return null
        val json = JSONObject(raw)
        val info = json.getJSONObject("info")
        return Record(
            id = json.getLong("id"),
            info = UpdateInfo(
                version = info.getString("version"),
                tag = info.getString("tag"),
                notes = info.getString("notes"),
                assetUrl = info.optString("assetUrl").takeIf { it.isNotBlank() },
                assetSize = info.getLong("assetSize"),
                htmlUrl = info.getString("htmlUrl"),
            ),
            sourcePath = json.getString("sourcePath"),
            cachePath = json.optString("cachePath").takeIf { it.isNotBlank() },
        )
    }

    private fun writeRecord(record: Record) {
        val info = record.info
        val json = JSONObject()
            .put("id", record.id)
            .put("sourcePath", record.sourcePath)
            .put("cachePath", record.cachePath ?: "")
            .put("info", JSONObject()
                .put("version", info.version)
                .put("tag", info.tag)
                .put("notes", info.notes)
                .put("assetUrl", info.assetUrl ?: "")
                .put("assetSize", info.assetSize)
                .put("htmlUrl", info.htmlUrl))
        if (!prefs.edit().putString(KEY_RECORD, json.toString()).commit()) {
            throw IOException("无法保存下载状态")
        }
    }

    private data class Record(val id: Long, val info: UpdateInfo, val sourcePath: String, val cachePath: String?)

    private companion object {
        const val PREFS_NAME = "update_download"
        const val KEY_RECORD = "task"
        // 不同 Activity/管理器实例也共享锁，避免重复点击提交多个任务。
        val taskMutex = Mutex()
    }
}

private fun downloadFailureMessage(reason: Int): String = when (reason) {
    DownloadManager.ERROR_INSUFFICIENT_SPACE -> "存储空间不足，无法下载更新包"
    DownloadManager.ERROR_DEVICE_NOT_FOUND -> "更新包存储位置不可用"
    DownloadManager.ERROR_FILE_ERROR -> "无法写入更新包文件"
    DownloadManager.ERROR_HTTP_DATA_ERROR -> "更新包网络传输失败，请重新下载"
    DownloadManager.ERROR_TOO_MANY_REDIRECTS -> "更新包下载重定向次数过多"
    in 400..599 -> "更新包下载失败：HTTP $reason"
    else -> "系统下载失败（错误码 $reason），请重新下载"
}
