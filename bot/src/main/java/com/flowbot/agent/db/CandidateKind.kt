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

    fun classify(contents: List<String>): List<String> {
        val kinds = contents.map(::fromContent).toMutableList()
        // ponytail: bridge only short titles between two detected cards; add OCR block geometry if layouts require wider association.
        for (index in 1 until kinds.lastIndex) {
            if (kinds[index] == TEXT && contents[index].trim().length <= 80 &&
                kinds[index - 1] == UNSUPPORTED_MEDIA && kinds[index + 1] == UNSUPPORTED_MEDIA) {
                kinds[index] = UNSUPPORTED_MEDIA
            }
        }
        return kinds
    }
}
