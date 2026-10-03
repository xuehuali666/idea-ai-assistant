package com.ye.aiassistant.llm

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.ye.aiassistant.settings.AiSettings
import java.io.BufferedReader
import java.io.Closeable
import java.io.InputStream
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

class LlmCancelledException : RuntimeException("已取消")
class LlmException(message: String) : RuntimeException(message)

/** 用来中断正在进行的请求：关闭底层 socket 流。 */
class CancelToken {
    private val cancelled = AtomicBoolean(false)
    private val closers = CopyOnWriteArrayList<Closeable>()

    val isCancelled: Boolean get() = cancelled.get()

    fun register(c: Closeable) {
        closers.add(c)
        if (cancelled.get()) runCatching { c.close() }
    }

    fun throwIfCancelled() {
        if (cancelled.get()) throw LlmCancelledException()
    }

    fun cancel() {
        cancelled.set(true)
        closers.forEach { runCatching { it.close() } }
        closers.clear()
    }
}

data class ToolCall(val id: String, val name: String, val arguments: String)

class ChatMsg(
    val role: String,
    var content: String = "",
    var reasoning: String = "",
    var toolCalls: List<ToolCall> = emptyList(),
    var toolCallId: String? = null,
    var name: String? = null,
) {
    fun toJson(sendReasoning: Boolean): JsonObject = JsonObject().apply {
        addProperty("role", role)
        addProperty("content", content)
        toolCallId?.let { addProperty("tool_call_id", it) }
        name?.let { addProperty("name", it) }
        if (toolCalls.isNotEmpty()) {
            add("tool_calls", JsonArray().apply {
                toolCalls.forEach { tc ->
                    add(JsonObject().apply {
                        addProperty("id", tc.id)
                        addProperty("type", "function")
                        add("function", JsonObject().apply {
                            addProperty("name", tc.name)
                            addProperty("arguments", tc.arguments)
                        })
                    })
                }
            })
        }
        // DeepSeek 要求 assistant 消息回传 reasoning_content
        if (sendReasoning && role == "assistant") addProperty("reasoning_content", reasoning)
    }

    companion object {
        fun system(text: String) = ChatMsg("system", text)
        fun user(text: String) = ChatMsg("user", text)
        fun tool(id: String, name: String, text: String) = ChatMsg("tool", text, toolCallId = id, name = name)
    }
}

class ToolSpec(val name: String, val description: String, val parameters: JsonObject) {
    fun toJson(): JsonObject = JsonObject().apply {
        addProperty("type", "function")
        add("function", JsonObject().apply {
            addProperty("name", name)
            addProperty("description", description)
            add("parameters", parameters)
        })
    }
}

class StreamResult(
    val text: String,
    val reasoning: String,
    val toolCalls: List<ToolCall>,
    val finishReason: String,
)

interface LlmListener {
    fun onText(delta: String) {}
    fun onReasoning(delta: String) {}
}

private class PendingToolCall {
    var id: String = ""
    val name = StringBuilder()
    val args = StringBuilder()
}

/**
 * 极简 OpenAI 兼容客户端：/chat/completions，支持 SSE 流式与 function calling。
 * 不依赖任何额外 SDK，只用 JDK HttpClient + Gson。
 */
class LlmClient(private val cfg: AiSettings.State) {

    /** opencode 网关要求每个会话带一个 id */
    private val sessionId: String = UUID.randomUUID().toString()

    private val http: HttpClient by lazy {
        val builder = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL)
        proxyAddress()?.let { builder.proxy(ProxySelector.of(it)) }
        builder.build()
    }

    private fun proxyAddress(): InetSocketAddress? {
        val raw = cfg.proxyUrl.trim()
        if (raw.isEmpty()) return null
        return runCatching {
            val uri = URI.create(if (raw.contains("://")) raw else "http://$raw")
            InetSocketAddress(uri.host, if (uri.port > 0) uri.port else 7897)
        }.getOrNull()
    }

    fun endpoint(): String {
        val base = cfg.baseUrl.trim().trimEnd('/')
        return if (base.endsWith("/chat/completions")) base else "$base/chat/completions"
    }

    /** 流式请求。返回完整文本 + tool calls。 */
    fun stream(
        messages: List<ChatMsg>,
        tools: List<ToolSpec> = emptyList(),
        cancel: CancelToken = CancelToken(),
        listener: LlmListener = object : LlmListener {},
    ): StreamResult {
        val body = buildBody(messages, tools, stream = true)
        val response = send(body)
        response.body().use { raw ->
            cancel.register(raw)
            val reader = BufferedReader(InputStreamReader(raw, StandardCharsets.UTF_8))
            val pending = LinkedHashMap<Int, PendingToolCall>()
            val text = StringBuilder()
            val reasoning = StringBuilder()
            var finish = ""
            while (true) {
                cancel.throwIfCancelled()
                val line = try {
                    reader.readLine()
                } catch (e: Exception) {
                    if (cancel.isCancelled) throw LlmCancelledException() else throw LlmException("读取响应失败: ${e.message}")
                } ?: break
                val trimmed = line.trim()
                if (trimmed.isEmpty() || !trimmed.startsWith("data:")) continue
                val payload = trimmed.removePrefix("data:").trim()
                if (payload == "[DONE]") break
                val obj = runCatching { JsonParser.parseString(payload).asJsonObject }.getOrNull() ?: continue
                obj.get("error")?.takeIf { !it.isJsonNull }?.let { throw LlmException(errorText(it)) }
                val choices = obj.array("choices") ?: continue
                if (choices.size() == 0) continue
                val choice = choices[0].takeIf { it.isJsonObject }?.asJsonObject ?: continue
                choice.obj("delta")?.let { delta ->
                    str(delta, "content")?.takeIf { it.isNotEmpty() }?.let { text.append(it); listener.onText(it) }
                    str(delta, "reasoning_content")?.takeIf { it.isNotEmpty() }?.let { reasoning.append(it); listener.onReasoning(it) }
                    delta.array("tool_calls")?.forEach { element ->
                        val tc = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
                        val index = tc.get("index")?.asInt ?: 0
                        val pendingCall = pending.getOrPut(index) { PendingToolCall() }
                        str(tc, "id")?.let { pendingCall.id = it }
                        tc.obj("function")?.let { fn ->
                            str(fn, "name")?.let { pendingCall.name.append(it) }
                            str(fn, "arguments")?.let { pendingCall.args.append(it) }
                        }
                    }
                }
                str(choice, "finish_reason")?.let { finish = it }
            }
            val calls = pending.values.mapIndexed { i, p ->
                ToolCall(p.id.ifEmpty { "call_$i" }, p.name.toString(), p.args.toString())
            }
            return StreamResult(text.toString(), reasoning.toString(), calls, finish)
        }
    }

    /** 非流式请求，用于抽取问题 / 测试连通性。 */
    fun complete(
        messages: List<ChatMsg>,
        tools: List<ToolSpec> = emptyList(),
        maxTokens: Int? = null,
        cancel: CancelToken = CancelToken(),
    ): StreamResult {
        val body = buildBody(messages, tools, stream = false, maxTokensOverride = maxTokens)
        val response = send(body)
        // 注册到 cancel 上：否则「停止」按钮对这条非流式请求无效（Q&A 抽取 / 测试连接都走这里）
        val text = response.body().use { stream ->
            cancel.register(stream)
            cancel.throwIfCancelled()
            val bytes = try {
                stream.readAllBytes()
            } catch (e: Exception) {
                if (cancel.isCancelled) throw LlmCancelledException() else throw LlmException("读取响应失败: ${e.message}")
            }
            String(bytes, StandardCharsets.UTF_8)
        }
        val obj = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull()
            ?: throw LlmException("响应不是合法 JSON: ${text.take(300)}")
        obj.get("error")?.takeIf { !it.isJsonNull }?.let { throw LlmException(errorText(it)) }
        val choice = obj.array("choices")?.firstOrNull()?.takeIf { it.isJsonObject }?.asJsonObject
            ?: throw LlmException("响应里没有 choices: ${text.take(300)}")
        val message = choice.obj("message") ?: JsonObject()
        val calls = message.array("tool_calls")?.mapNotNull { el ->
            val tc = el.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val fn = tc.obj("function") ?: return@mapNotNull null
            ToolCall(str(tc, "id") ?: "call", str(fn, "name").orEmpty(), str(fn, "arguments").orEmpty())
        }.orEmpty()
        return StreamResult(
            str(message, "content").orEmpty(),
            str(message, "reasoning_content").orEmpty(),
            calls,
            str(choice, "finish_reason").orEmpty(),
        )
    }

    /** 额外请求头：每行一条 "Key: Value"，用于自建网关等特殊要求。 */
    private fun extraHeaders(): List<Pair<String, String>> =
        cfg.extraHeaders.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .mapNotNull { line ->
                val sep = line.indexOfFirst { it == ':' || it == '=' }
                if (sep <= 0) null else line.substring(0, sep).trim() to line.substring(sep + 1).trim()
            }
            .toList()

    private fun isOpencodeGateway(): Boolean =
        runCatching { URI.create(endpoint()).host?.contains("opencode.ai") == true }.getOrDefault(false)

    private fun send(body: String): HttpResponse<InputStream> {
        val uri = try {
            URI.create(endpoint())
        } catch (e: Exception) {
            throw LlmException("Base URL 不合法：${endpoint()}（需要形如 https://api.deepseek.com 或 https://xxx/v1）")
        }
        if (uri.host.isNullOrBlank()) {
            throw LlmException("Base URL 缺少主机名：${endpoint()}（别忘了 http:// 或 https://）")
        }
        val builder = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(cfg.timeoutSeconds.coerceAtLeast(30).toLong()))
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream, application/json")
            .header("User-Agent", "idea-ai-assistant/0.1")
        if (cfg.apiKey.isNotBlank()) builder.header("Authorization", "Bearer ${cfg.apiKey.trim()}")
        val extra = extraHeaders()
        extra.forEach { (k, v) -> runCatching { builder.header(k, v) } }
        val declared = extra.map { it.first.lowercase() }
        if (isOpencodeGateway()) {
            if ("x-opencode-session" !in declared) builder.header("x-opencode-session", sessionId)
            if ("x-opencode-client" !in declared) builder.header("x-opencode-client", "idea-ai-assistant")
        }
        val request = builder.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build()

        // 429 / 5xx 基本都是上游临时抽风，重试一次比直接把错误甩给用户好
        var lastError = ""
        repeat(2) { attempt ->
            if (attempt > 0) Thread.sleep(RETRY_DELAY_MS)
            val response = try {
                http.send(request, HttpResponse.BodyHandlers.ofInputStream())
            } catch (e: Exception) {
                lastError = "请求失败: ${e.message}（检查 base url / 代理设置）"
                return@repeat
            }
            if (response.statusCode() in 200..299) return response
            val err = runCatching {
                response.body().use { String(it.readAllBytes(), StandardCharsets.UTF_8) }
            }.getOrDefault("")
            val code = response.statusCode()
            lastError = "HTTP $code: ${err.take(600)}"
            if (code !in RETRYABLE_CODES) throw LlmException(lastError)
        }
        throw LlmException(lastError)
    }

    private companion object {
        /** 上游临时故障，值得重试一次的状态码 */
        val RETRYABLE_CODES = setOf(429, 500, 502, 503, 504)
        const val RETRY_DELAY_MS = 1200L
    }

    private fun buildBody(
        messages: List<ChatMsg>,
        tools: List<ToolSpec>,
        stream: Boolean,
        maxTokensOverride: Int? = null,
    ): String {
        val root = JsonObject()
        root.addProperty("model", cfg.model)
        root.addProperty("stream", stream)
        root.addProperty("temperature", cfg.temperature)
        val maxTokens = maxTokensOverride ?: cfg.maxTokens
        if (maxTokens > 0) root.addProperty("max_tokens", maxTokens)
        root.add("messages", JsonArray().apply { messages.forEach { add(it.toJson(cfg.sendReasoningContent)) } })
        if (tools.isNotEmpty()) {
            root.add("tools", JsonArray().apply { tools.forEach { add(it.toJson()) } })
            root.addProperty("tool_choice", "auto")
        }
        return root.toString()
    }

    private fun errorText(element: JsonElement): String {
        if (element.isJsonObject) {
            val obj = element.asJsonObject
            return str(obj, "message") ?: obj.toString()
        }
        return element.toString()
    }

    /** getAsJsonArray / getAsJsonObject 遇到 JsonNull 会直接抛 ClassCastException，这里统一做保护。 */
    private fun JsonObject.array(key: String): JsonArray? = get(key)?.takeIf { it.isJsonArray }?.asJsonArray

    private fun JsonObject.obj(key: String): JsonObject? = get(key)?.takeIf { it.isJsonObject }?.asJsonObject

    private fun str(obj: JsonObject, key: String): String? =
        obj.get(key)?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString
}
