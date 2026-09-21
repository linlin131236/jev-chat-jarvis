package com.jev.probe.jev

import android.util.Log
import com.jev.probe.core.Analysis
import com.jev.probe.core.ChatSnapshot
import com.jev.probe.core.Choice
import com.jev.probe.core.RankedReply
import com.jev.probe.core.Score
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Universal OpenAI-compatible LLM client (AbinAPI, DeepSeek, OpenRouter, etc.).
 * Replaces proprietary decision endpoints with standard /v1/chat/completions.
 */
class JevClient(
    private val baseUrl: String,
    private val key: String,
    private val replyModel: String
) {

    constructor(key: String, replyModel: String) : this(
        "https://api.abinapi.com/v1", key, replyModel
    )

    private val chatUrl: String
        get() {
            val clean = baseUrl.trim().trimEnd('/')
            return if (clean.endsWith("/chat/completions")) clean else "$clean/chat/completions"
        }

    /** Analyze conversation intent & emotion via structured prompt */
    fun judge(snapshot: ChatSnapshot, relationship: String): Analysis {
        val start = System.currentTimeMillis()
        try {
            val convo = snapshot.messages.takeLast(10).joinToString("\n") {
                (if (it.side == "me") "我" else "对方") + "：" + it.text
            }
            val sys = "你是一个专业的聊天意图洞察与心理分析专家。请根据最近对话和双方关系，评估对方的真实意图和情绪状态。\n" +
                    "请严格直接返回纯 JSON 对象（不要 Markdown 格式块，不要 ``` 代码块标记，不要任何额外解释）。\n" +
                    "JSON 必须包含以下键：\n" +
                    "- \"true_intent\": 对方意图标签（例如：情绪发泄、试探态度、确认承诺、闲聊求关注、就事论事）\n" +
                    "- \"danger_level\": 0 到 9 的整数，0 代表完全安全，9 代表极度危险/即将爆发冲突\n" +
                    "- \"she_needs\": 对方核心诉求（例如：态度认错、具体行动/方案、情感安抚、无需特殊处理）\n" +
                    "- \"should_reply_now\": 0.0 到 1.0 的浮点数，是否需要立即回复\n" +
                    "- \"best_action\": 建议我方行动（例如：直接认错/顺从、给出具体行动计划、安抚情绪不讲大道理、就事论事直接回答）\n" +
                    "- \"tension_resolved\": 0.0 到 1.0 的浮点数，张力是否已化解\n" +
                    "- \"literal_question\": 0.0 到 1.0 的浮点数，对方是单纯字面提问还是有潜台词"

            val user = "双方关系：$relationship\n\n最近对话：\n$convo\n\n请给出意图分析 JSON。"

            val messages = JSONArray()
                .put(JSONObject().put("role", "system").put("content", sys))
                .put(JSONObject().put("role", "user").put("content", user))

            val body = JSONObject()
                .put("model", replyModel)
                .put("messages", messages)
                .put("temperature", 0.3)

            val resp = postJson(chatUrl, body)
            val content = resp.optJSONArray("choices")?.optJSONObject(0)
                ?.optJSONObject("message")?.optString("content") ?: ""

            val json = extractJson(content)
            val intentStr = json.optString("true_intent", "分析中")
            val dangerNum = json.optDouble("danger_level", 1.0)
            val needsStr = json.optString("she_needs", "正常交流")
            val actionStr = json.optString("best_action", "正常回复")
            val shouldReply = json.optDouble("should_reply_now", 0.8)
            val tension = json.optDouble("tension_resolved", 0.5)
            val literal = json.optDouble("literal_question", 0.5)

            return Analysis(
                trueIntent = Choice(intentStr, 0.9, mapOf(intentStr to 0.9)),
                dangerLevel = Score(dangerNum, 0.9, 9),
                sheNeeds = Choice(needsStr, 0.9, mapOf(needsStr to 0.9)),
                shouldReplyNow = shouldReply,
                bestAction = Choice(actionStr, 0.9, mapOf(actionStr to 0.9)),
                tensionResolved = tension,
                literalQuestion = literal,
                rankedReplies = emptyList(),
                latencyMs = System.currentTimeMillis() - start
            )
        } catch (e: Exception) {
            Log.w(TAG, "judge failed: ${e.message}")
            return Analysis(null, null, null, null, null, null, null, emptyList(),
                System.currentTimeMillis() - start, error = readableError(e))
        }
    }

    /** Draft 3 candidate replies and rank them */
    fun draftAndRank(snapshot: ChatSnapshot, relationship: String): List<RankedReply> {
        val candidates = generateCandidates(snapshot, relationship)
        return candidates.mapIndexed { index, text ->
            RankedReply(text, 1.0 - (index * 0.15))
        }
    }

    /** Convenience for connectivity test: judge + draft */
    fun analyze(snapshot: ChatSnapshot, relationship: String): Analysis {
        val a = judge(snapshot, relationship)
        if (a.error != null) return a
        val ranked = try { draftAndRank(snapshot, relationship) } catch (e: Exception) { emptyList() }
        return a.copy(rankedReplies = ranked)
    }

    private fun generateCandidates(snapshot: ChatSnapshot, relationship: String): List<String> {
        val convo = snapshot.messages.takeLast(10).joinToString("\n") {
            (if (it.side == "me") "我" else "对方") + "：" + it.text
        }
        val sys = "你是高情商中文即时通讯回复助手。只输出一个纯 JSON 字符串数组，含且仅含 3 条候选回复文本，" +
                "三条策略要有明显区别（例如：一条稳妥承接、一条给具体行动或承诺、一条简短低姿态）。" +
                "每条不超过 40 字，口语、自然、像真人在聊天软件里发消息。不要任何解释，不要 Markdown 代码块，直接输出 JSON 数组。"
        val user = "关系：$relationship\n\n最近对话：\n$convo\n\n请给出 3 条候选回复 JSON 数组。"
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", sys))
            .put(JSONObject().put("role", "user").put("content", user))
        val body = JSONObject()
            .put("model", replyModel)
            .put("messages", messages)
            .put("temperature", 0.7)

        val resp = postJson(chatUrl, body)
        val content = resp.optJSONArray("choices")?.optJSONObject(0)
            ?.optJSONObject("message")?.optString("content") ?: ""
        return parseThree(content)
    }

    private fun extractJson(text: String): JSONObject {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start >= 0 && end > start) {
            try {
                return JSONObject(text.substring(start, end + 1))
            } catch (_: Exception) {}
        }
        return JSONObject()
    }

    private fun parseThree(content: String): List<String> {
        val start = content.indexOf('[')
        val end = content.lastIndexOf(']')
        if (start >= 0 && end > start) {
            try {
                val arr = JSONArray(content.substring(start, end + 1))
                val out = ArrayList<String>()
                for (i in 0 until arr.length()) out.add(arr.getString(i).trim())
                if (out.size >= 3) return out.take(3)
                while (out.size < 3) out.add("（稍等，我看下）")
                return out
            } catch (_: Exception) { }
        }
        val lines = content.split("\n").map { it.trim().trimStart('-', '*', '1', '2', '3', '.', ' ', '"') }
            .filter { it.isNotBlank() }
        val out = lines.take(3).toMutableList()
        while (out.size < 3) out.add("（稍等，我看下）")
        return out
    }

    private fun postJson(urlStr: String, body: JSONObject): JSONObject {
        var attempt = 0
        var lastErr: Exception? = null
        while (attempt < 3) {
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 15000
                    readTimeout = 25000
                    doOutput = true
                    setRequestProperty("Authorization", "Bearer $key")
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("User-Agent", "JevAssistant/1.2 (Android)")
                }
                val bytes = body.toString().toByteArray(Charsets.UTF_8)
                conn.outputStream.use { os: OutputStream -> os.write(bytes) }
                val code = conn.responseCode
                if (code == 429 || code == 529) {
                    attempt++
                    Thread.sleep(500L * (1L shl attempt))
                    continue
                }
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val text = BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
                if (code !in 200..299) throw RuntimeException("HTTP $code: ${text.take(160)}")
                return JSONObject(text)
            } catch (e: Exception) {
                lastErr = e
                if (e.message?.contains("HTTP 4") == true) throw e
                attempt++
                if (attempt < 3) Thread.sleep(500L * (1L shl attempt))
            } finally {
                conn?.disconnect()
            }
        }
        throw lastErr ?: RuntimeException("request failed")
    }

    private fun readableError(e: Exception): String {
        val m = e.message ?: e.javaClass.simpleName
        return when {
            m.contains("HTTP 401") -> "密钥无效或未设置（401）"
            m.contains("HTTP 404") -> "接口地址错误（404），请检查 Base URL"
            m.contains("HTTP 403") -> "无权访问该模型（403）"
            m.contains("HTTP 4") -> "请求被拒：$m"
            m.contains("timed out") || m.contains("timeout") -> "网络超时，请检查连接"
            m.contains("Unable to resolve host") || m.contains("Failed to connect") -> "无法连接网络"
            else -> "分析失败：$m"
        }
    }

    companion object { private const val TAG = "JEVASSIST" }
}
