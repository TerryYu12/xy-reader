package com.xyreader.feedback

import com.xyreader.BuildConfig
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONException
import org.json.JSONObject

/** 一次上传的结果 */
sealed interface FeedbackResult {
    /** 成功：[id] 是服务端返回的日志编号，用户把它发给开发者即可取回日志 */
    data class Success(val id: String) : FeedbackResult

    /** 失败：[code] 是服务端或客户端错误码，对应的中文提示见 [feedbackErrorMessage] */
    data class Failure(val code: String) : FeedbackResult
}

/** 错误码：服务端（中转 Worker）返回的原样透传，客户端自有的在下半部分 */
object FeedbackErrorCode {
    // ---- 服务端 ----
    const val INVALID_JSON = "invalid_json"
    const val INVALID_APP = "invalid_app"
    const val INVALID_VERSION = "invalid_version"
    const val INVALID_PLATFORM = "invalid_platform"
    const val INVALID_LOG = "invalid_log"
    const val NOTE_TOO_LONG = "note_too_long"
    const val NOT_FOUND = "not_found"
    const val LOG_TOO_LARGE = "log_too_large"
    const val RATE_LIMITED = "rate_limited"
    const val DAILY_QUOTA_EXCEEDED = "daily_quota_exceeded"
    const val GITHUB_UPLOAD_FAILED = "github_upload_failed"
    const val INTERNAL_ERROR = "internal_error"

    // ---- 客户端 ----
    /** 调用超时 */
    const val TIMEOUT = "timeout"

    /** 连接 / 读写失败（离线、DNS、连接被拒、TLS 等） */
    const val NETWORK_ERROR = "network_error"

    /** 端点为空或不是合法 URL */
    const val NOT_CONFIGURED = "not_configured"

    /** 服务器响应不是预期的 JSON */
    const val INVALID_RESPONSE = "invalid_response"

    /** 客户端自己出了意外（不应发生；保底，免得异常一路冒到界面） */
    const val CLIENT_ERROR = "client_error"
}

/**
 * 错误码 → 面向用户的一句中文提示。未知码归入默认的「上传失败（错误码：xxx）」。
 * 纯函数，便于单测。
 */
fun feedbackErrorMessage(code: String): String = when (code) {
    FeedbackErrorCode.INVALID_JSON -> "提交的数据格式有误，请更新应用后重试"
    FeedbackErrorCode.INVALID_APP -> "反馈服务不认识这个应用，请更新应用后重试"
    FeedbackErrorCode.INVALID_VERSION -> "应用版本信息无效，请更新应用后重试"
    FeedbackErrorCode.INVALID_PLATFORM -> "平台信息无效，请更新应用后重试"
    FeedbackErrorCode.INVALID_LOG -> "没有可上传的日志，请稍后重试"
    FeedbackErrorCode.NOTE_TOO_LONG -> "问题描述过长，请精简到 ${FeedbackUploader.MAX_NOTE_CHARS} 字以内"
    FeedbackErrorCode.NOT_FOUND -> "反馈服务地址无效，请更新应用后重试"
    FeedbackErrorCode.LOG_TOO_LARGE -> "日志过大，服务器拒绝接收，请稍后重试"
    FeedbackErrorCode.RATE_LIMITED -> "提交太频繁，请一分钟后再试"
    FeedbackErrorCode.DAILY_QUOTA_EXCEEDED -> "今日反馈名额已用完，请明天再试"
    FeedbackErrorCode.GITHUB_UPLOAD_FAILED -> "服务器保存日志失败，请稍后重试"
    FeedbackErrorCode.INTERNAL_ERROR -> "服务器开小差了，请稍后重试"
    FeedbackErrorCode.TIMEOUT -> "上传超时，请检查网络后重试"
    FeedbackErrorCode.NETWORK_ERROR -> "网络连接失败，请检查网络后重试"
    FeedbackErrorCode.NOT_CONFIGURED -> "反馈服务尚未配置，暂时无法提交"
    FeedbackErrorCode.INVALID_RESPONSE -> "服务器返回了无法识别的内容，请稍后重试"
    else -> "上传失败（错误码：${code.take(MAX_SHOWN_CODE_CHARS)}）"
}

/** 默认文案里最多展示的错误码长度（防止异常服务器塞进超长内容） */
private const val MAX_SHOWN_CODE_CHARS = 64

/**
 * BUG 反馈上传器：把问题描述与脱敏后的运行日志 POST 给中转 Worker（infra/log-relay），换回编号。
 *
 * 请求：`POST <endpoint>`，`Content-Type: application/json`，
 * 体 `{"app","version","platform","note","log"}`；
 * 响应：成功 `{"ok":true,"id":"<编号>"}`，失败 `{"ok":false,"error":"<码>"}`（见 [FeedbackErrorCode]）。
 *
 * @param endpoint 上传端点，默认取 `BuildConfig.FEEDBACK_ENDPOINT`——**全工程只在 app/build.gradle.kts
 *   的 defaultConfig 里定义一次；Worker 部署后若域名与默认值不同，改那里即可**。为空或不是合法 URL
 *   时直接返回 [FeedbackErrorCode.NOT_CONFIGURED]，不发请求。
 * @param client 独立的 OkHttpClient（默认调用超时 30 秒），测试可注入短超时的实例。
 * @param reportSource 日志来源，默认 [FeedbackReport.collect]；在 IO 线程调用，测试可注入。
 */
class FeedbackUploader(
    private val endpoint: String = BuildConfig.FEEDBACK_ENDPOINT,
    private val client: OkHttpClient = DEFAULT_CLIENT,
    private val reportSource: () -> FeedbackReport = { FeedbackReport.collect() },
) {

    /**
     * 上传。全程在 [Dispatchers.IO] 执行；协程被取消时会同时取消进行中的 HTTP 调用。
     * 不会抛出（取消除外），所有失败都以 [FeedbackResult.Failure] 返回。
     */
    suspend fun upload(note: String): FeedbackResult {
        val url = endpoint.trim().toHttpUrlOrNull()
            ?: return failure(FeedbackErrorCode.NOT_CONFIGURED, null)
        return withContext(Dispatchers.IO) {
            try {
                val log = reportSource().toUploadLog()
                val body = JSONObject()
                    .put("app", APP_ID)
                    .put("version", BuildConfig.VERSION_NAME)
                    .put("platform", PLATFORM)
                    .put("note", note)
                    .put("log", log)
                    .toString()
                val request = Request.Builder()
                    .url(url)
                    .header("Accept", "application/json")
                    .post(body.toByteArray(Charsets.UTF_8).toRequestBody(JSON_MEDIA_TYPE))
                    .build()
                // 收集日志期间若已被取消，就不要再发请求
                ensureActive()
                val call = client.newCall(request)
                // 协程被取消（如对话框关闭）时立刻掐断 HTTP 调用，而不是等它自己超时。
                // UNDISPATCHED：马上进入 try，即使此刻已被取消，finally 里的 cancel() 也一定会执行
                val canceller = launch(start = CoroutineStart.UNDISPATCHED) {
                    try {
                        awaitCancellation()
                    } finally {
                        call.cancel()
                    }
                }
                try {
                    call.execute().use { response -> parseResponse(response) }
                } finally {
                    canceller.cancel()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: InterruptedIOException) {
                // SocketTimeoutException 与 OkHttp 的调用超时（"timeout"）都是它的子类
                ensureActive() // 用户取消导致的中断不算失败，也不记日志
                failure(FeedbackErrorCode.TIMEOUT, e)
            } catch (e: IOException) {
                ensureActive()
                failure(FeedbackErrorCode.NETWORK_ERROR, e)
            } catch (e: Exception) {
                ensureActive()
                failure(FeedbackErrorCode.CLIENT_ERROR, e)
            }
        }
    }

    private fun parseResponse(response: Response): FeedbackResult {
        // 只读前 64KB：正常响应只有几十字节，异常的 HTML 错误页也不必全读
        val text = response.peekBody(MAX_RESPONSE_BYTES).string()
        return parseUploadResponse(text)
    }

    private fun failure(code: String, cause: Exception?): FeedbackResult.Failure {
        AppLog.w(TAG, "反馈上传失败 code=$code", cause)
        return FeedbackResult.Failure(code)
    }

    companion object {
        /** 应用标识：Worker 的 APPS 映射据此找到对应的日志仓库 */
        const val APP_ID = "xy-reader"

        /** 平台标识 */
        const val PLATFORM = "android"

        /** 问题描述的字数上限（与 Worker 的 note_too_long 一致） */
        const val MAX_NOTE_CHARS = 2000

        /**
         * 把问题描述收敛到 [MAX_NOTE_CHARS] 以内：粘贴超长文本时截断，且不把 emoji 等代理对劈成两半
         * （半个代理对在 UTF-8 编码时会变成 `?`）。输入框用它限制长度。
         */
        fun clampNote(text: String): String {
            if (text.length <= MAX_NOTE_CHARS) return text
            var end = MAX_NOTE_CHARS
            if (Character.isHighSurrogate(text[end - 1])) end--
            return text.substring(0, end)
        }

        private const val TAG = "FeedbackUploader"
        private const val MAX_RESPONSE_BYTES = 64L * 1024L
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()

        /** 独立于其他模块的客户端：整个调用（含上传日志与等待 Worker 写 GitHub）最长 30 秒 */
        private val DEFAULT_CLIENT: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build()

        /**
         * 解析 Worker 的响应体：`{"ok":true,"id":"…"}` → 成功；`{"ok":false,"error":"…"}` → 失败码；
         * 不是 JSON 对象、缺少 id / error 等一律视为 [FeedbackErrorCode.INVALID_RESPONSE]。
         */
        internal fun parseUploadResponse(text: String): FeedbackResult {
            val json = try {
                JSONObject(text)
            } catch (_: JSONException) {
                return FeedbackResult.Failure(FeedbackErrorCode.INVALID_RESPONSE)
            }
            return if (json.optBoolean("ok", false)) {
                val id = if (json.isNull("id")) "" else json.optString("id", "").trim()
                if (id.isEmpty()) {
                    FeedbackResult.Failure(FeedbackErrorCode.INVALID_RESPONSE)
                } else {
                    FeedbackResult.Success(id)
                }
            } else {
                val error = if (json.isNull("error")) "" else json.optString("error", "").trim()
                FeedbackResult.Failure(error.ifEmpty { FeedbackErrorCode.INVALID_RESPONSE })
            }
        }
    }
}
