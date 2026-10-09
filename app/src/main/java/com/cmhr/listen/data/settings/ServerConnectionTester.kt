package com.cmhr.listen.data.settings

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

sealed interface ConnectionTestResult {
    data class Success(val statusCode: Int) : ConnectionTestResult
    data class Failure(val message: String) : ConnectionTestResult
}

class ServerConnectionTester(private val client: OkHttpClient) {
    /**
     * Health check, then an authenticated lookup of a job that cannot exist: the ASR server
     * answers 404 for a valid key and 401/403 for a missing or wrong one, so this proves the
     * key without uploading audio.
     */
    suspend fun verify(baseUrl: String, apiKey: String): ConnectionTestResult {
        val health = test(baseUrl)
        if (health is ConnectionTestResult.Failure) return health
        if (apiKey.isBlank()) return ConnectionTestResult.Failure("服务器可以连上，但还没填 API Key。")
        return withContext(Dispatchers.IO) {
            val root = (baseUrl.trim().trimEnd('/') + "/").toHttpUrlOrNull()
                ?: return@withContext ConnectionTestResult.Failure("服务器地址格式无效。")
            val probe = root.resolve("jobs/$KEY_PROBE_JOB_ID")
                ?: return@withContext ConnectionTestResult.Failure("无法生成鉴权检查地址。")
            try {
                val request = Request.Builder().url(probe).header("Authorization", "Bearer ${apiKey.trim()}").get().build()
                client.newCall(request).execute().use { response ->
                    when (response.code) {
                        401, 403 -> ConnectionTestResult.Failure("API Key 无效（HTTP ${response.code}）。")
                        404 -> ConnectionTestResult.Success(response.code)
                        in 200..299 -> ConnectionTestResult.Success(response.code)
                        else -> ConnectionTestResult.Failure("鉴权检查失败（HTTP ${response.code}）。")
                    }
                }
            } catch (_: SocketTimeoutException) {
                ConnectionTestResult.Failure("连接服务器超时。")
            } catch (_: Exception) {
                ConnectionTestResult.Failure("鉴权检查失败。")
            }
        }
    }

    suspend fun test(baseUrl: String): ConnectionTestResult = withContext(Dispatchers.IO) {
        val root = (baseUrl.trim().trimEnd('/') + "/").toHttpUrlOrNull()
            ?: return@withContext ConnectionTestResult.Failure("服务器地址格式无效。")
        val healthUrl = root.resolve("health")
            ?: return@withContext ConnectionTestResult.Failure("无法生成健康检查地址。")

        try {
            client.newCall(Request.Builder().url(healthUrl).get().build()).execute().use { response ->
                if (response.isSuccessful) ConnectionTestResult.Success(response.code)
                else ConnectionTestResult.Failure("健康检查失败（HTTP ${response.code}）。")
            }
        } catch (_: UnknownHostException) {
            ConnectionTestResult.Failure("无法解析服务器地址。")
        } catch (_: SocketTimeoutException) {
            ConnectionTestResult.Failure("连接服务器超时。")
        } catch (_: ConnectException) {
            ConnectionTestResult.Failure("服务器拒绝连接或当前网络不可达。")
        } catch (error: IOException) {
            val cleartextBlocked = error.message?.contains("CLEARTEXT", ignoreCase = true) == true
            ConnectionTestResult.Failure(if (cleartextBlocked) "Android 安全策略禁止访问该明文 HTTP 地址。" else "网络连接失败。")
        } catch (_: SecurityException) {
            ConnectionTestResult.Failure("系统安全策略阻止了此次连接。")
        } catch (_: Exception) {
            ConnectionTestResult.Failure("连接测试失败。")
        }
    }

    private companion object {
        const val KEY_PROBE_JOB_ID = "listen-key-probe"
    }
}
