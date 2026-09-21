package com.jev.probe.core

import android.content.Context

class Prefs(context: Context) {

    private val sp = context.getSharedPreferences("jev_assistant", Context.MODE_PRIVATE)

    /** API Base URL (OpenAI compatible, e.g. https://api.abinapi.com/v1) */
    var apiBaseUrl: String
        get() {
            val v = sp.getString(K_BASE_URL, DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL
            return v.trim().trimEnd('/')
        }
        set(v) = sp.edit().putString(K_BASE_URL, v.trim().trimEnd('/')).apply()

    /** API Key (AbinAPI, OpenAI or OpenRouter key) */
    var apiKey: String
        get() {
            val k = sp.getString(K_API_KEY, "") ?: ""
            if (k.isNotBlank()) return k
            return sp.getString("openrouter_key", "") ?: ""
        }
        set(v) = sp.edit().putString(K_API_KEY, v.trim()).apply()

    var openRouterKey: String
        get() = apiKey
        set(v) { apiKey = v }

    /** Generative model for drafting candidate replies and intent analysis */
    var replyModel: String
        get() = sp.getString(K_REPLY_MODEL, DEFAULT_REPLY_MODEL) ?: DEFAULT_REPLY_MODEL
        set(v) = sp.edit().putString(K_REPLY_MODEL, v.trim()).apply()

    var relationship: String
        get() = sp.getString(K_REL, DEFAULT_REL) ?: DEFAULT_REL
        set(v) = sp.edit().putString(K_REL, v).apply()

    var enabled: Boolean
        get() = sp.getBoolean(K_ENABLED, true)
        set(v) = sp.edit().putBoolean(K_ENABLED, v).apply()

    var whitelist: Set<String>
        get() = sp.getStringSet(K_WHITELIST, emptySet()) ?: emptySet()
        set(v) = sp.edit().putStringSet(K_WHITELIST, v).apply()

    var overlayOpacity: Int
        get() = sp.getInt(K_OPACITY, 92).coerceIn(60, 100)
        set(v) = sp.edit().putInt(K_OPACITY, v.coerceIn(60, 100)).apply()

    var bubbleY: Int
        get() = sp.getInt(K_BUBBLE_Y, -1)
        set(v) = sp.edit().putInt(K_BUBBLE_Y, v).apply()

    var bubbleX: Int
        get() = sp.getInt(K_BUBBLE_X, -1)
        set(v) = sp.edit().putInt(K_BUBBLE_X, v).apply()

    var autoAnalyze: Boolean
        get() = sp.getBoolean(K_AUTO, true)
        set(v) = sp.edit().putBoolean(K_AUTO, v).apply()

    fun isAllowed(title: String?): Boolean {
        val wl = whitelist
        if (wl.isEmpty()) return true
        if (title == null) return false
        return wl.any { title.contains(it) }
    }

    fun hasKey(): Boolean = apiKey.isNotBlank()

    companion object {
        private const val K_BASE_URL = "api_base_url"
        private const val K_API_KEY = "api_key"
        private const val K_REPLY_MODEL = "reply_model"
        private const val K_REL = "relationship"
        private const val K_ENABLED = "enabled"
        private const val K_WHITELIST = "whitelist"
        private const val K_OPACITY = "overlay_opacity"
        private const val K_BUBBLE_Y = "bubble_y"
        private const val K_BUBBLE_X = "bubble_x"
        private const val K_AUTO = "auto_analyze"

        const val DEFAULT_BASE_URL = "https://api.abinapi.com/v1"
        const val DEFAULT_REPLY_MODEL = "deepseek-v4-flash-0731"
        const val DEFAULT_REL = "对方是我的伴侣；from=me 的是我发的，from=other 的是对方发的"
    }
}
