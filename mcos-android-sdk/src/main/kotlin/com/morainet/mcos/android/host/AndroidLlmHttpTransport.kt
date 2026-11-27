package com.morainet.mcos.android.host

import com.morainet.mcos.llm.HttpTransportResponse
import com.morainet.mcos.llm.LlmErrorCode
import com.morainet.mcos.llm.LlmStreamFrame
import com.morainet.mcos.llm.LlmTransportException
import com.morainet.mcos.llm.StreamingLlmHttpTransport
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSource

/**
 * Android HTTP transport. Streaming calls use OkHttp so cancellation can close
 * an open response even when the server has stopped sending bytes.
 *
 * The JVM default [com.morainet.mcos.llm.JdkLlmHttpTransport] cannot be used on
 * Android because the `java.net.http` module is not part of the Android
 * runtime. This implementation mirrors its behavior:
 * - timeouts surface as [LlmTransportException] with code `LLM_TIMEOUT`;
 * - [java.net.ConnectException] / [IOException] propagate so the provider's
 *   existing catch clauses map them to `LLM_CONNECT_ERROR` / `LLM_NETWORK_ERROR`.
 */
class AndroidLlmHttpTransport : StreamingLlmHttpTransport {

    override fun postEvents(
        url: String,
        apiKey: String,
        body: String,
        connectTimeoutMs: Long,
        requestTimeoutMs: Long,
    ): Flow<LlmStreamFrame> = flow {
        coroutineScope {
            val client = streamClient.newBuilder()
                .connectTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
                .callTimeout(requestTimeoutMs, TimeUnit.MILLISECONDS)
                .readTimeout(requestTimeoutMs, TimeUnit.MILLISECONDS)
                .build()
            val request = Request.Builder().url(url)
                .header("Authorization", "Bearer $apiKey")
                .header("Accept", "text/event-stream, application/json")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
            val call = client.newCall(request)
            val cancelCall = launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    awaitCancellation()
                } finally {
                    call.cancel()
                }
            }
            try {
                call.execute().use { response ->
                    val responseBody = response.body
                    if (response.isSuccessful && responseBody != null &&
                        responseBody.contentType()?.let { "${it.type}/${it.subtype}" } == "text/event-stream"
                    ) {
                        readEvents(responseBody.source()) { emit(LlmStreamFrame.Data(it)) }
                    } else {
                        emit(LlmStreamFrame.Response(HttpTransportResponse(response.code, responseBody?.string().orEmpty())))
                    }
                }
            } catch (e: IOException) {
                currentCoroutineContext().ensureActive()
                if (e is InterruptedIOException) {
                    throw LlmTransportException(LlmErrorCode.TIMEOUT, "LLM request timed out", true)
                }
                throw e
            } finally {
                cancelCall.cancel()
            }
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun readEvents(source: BufferedSource, onEvent: suspend (String) -> Unit) {
        val data = StringBuilder()
        while (!source.exhausted()) {
            val line = source.readUtf8LineStrict(MAX_EVENT_CHARS.toLong())
            if (line.isEmpty() && data.isNotEmpty()) {
                val value = data.removeSuffix("\n").toString()
                onEvent(value)
                if (value == "[DONE]") return
                data.clear()
            } else if (line.startsWith("data:")) {
                data.append(line.removePrefix("data:").removePrefix(" ")).append('\n')
                if (data.length > MAX_EVENT_CHARS) {
                    throw LlmTransportException("LLM_PARSE_ERROR", "LLM stream event is too large", false)
                }
            }
        }
    }

    override suspend fun postJson(
        url: String,
        apiKey: String,
        body: String,
        connectTimeoutMs: Long,
        requestTimeoutMs: Long,
    ): HttpTransportResponse = withContext(Dispatchers.IO) {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = connectTimeoutMs.toInt()
            conn.readTimeout = requestTimeoutMs.toInt()
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $apiKey")
            conn.doOutput = true
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            HttpTransportResponse(statusCode = status, body = text)
        } catch (e: SocketTimeoutException) {
            throw LlmTransportException(LlmErrorCode.TIMEOUT, "LLM request timed out", true)
        } finally {
            conn.disconnect()
        }
    }

    private companion object {
        const val MAX_EVENT_CHARS = 512 * 1024
        val streamClient = OkHttpClient.Builder().followRedirects(false).build()
    }
}
