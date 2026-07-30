package com.flowbot.agent.db

object CandidateKind {
    const val TEXT = "TEXT"
    const val UNSUPPORTED_MEDIA = "UNSUPPORTED_MEDIA"

    private val UI_LABELS = setOf("小程序", "设为群待办", "收藏")

    fun fromContent(content: String): String {
        val trimmed = content.trim()
        if (trimmed in UI_LABELS) return UNSUPPORTED_MEDIA
        if (trimmed.contains("分享视频:") || trimmed.contains("QQ音乐")) {
            return UNSUPPORTED_MEDIA
        }
        val hasFileType = trimmed.contains("pdf", ignoreCase = true) || trimmed.contains("mp3", ignoreCase = true)
        val hasFileSize = Regex("(?i)\\d+(?:\\.\\d+)?\\s*(kb|mb)").containsMatchIn(trimmed)
        return if (hasFileSize && (hasFileType || trimmed.contains("未下载"))) UNSUPPORTED_MEDIA else TEXT
    }
}
