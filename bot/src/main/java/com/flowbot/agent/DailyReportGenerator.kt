package com.flowbot.agent

import com.flowbot.agent.db.CandidateDigest
import com.flowbot.agent.db.CandidateKind
import java.text.DateFormat
import java.util.Date

object DailyReportGenerator {
    private val TODO_MARKERS = listOf("待办", "跟进", "需要", "请", "截止", "会议", "完成")
    private val RISK_MARKERS = listOf("风险", "超时", "问题", "异常", "阻塞")

    fun generate(candidates: List<CandidateDigest>, generatedAt: Date = Date()): String = buildString {
        val reportable = candidates.filter(::isReportable)
        val groups = reportable.map { it.groupNameHint }.distinct()
        append("日报草稿\n")
        append("生成时间：").append(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(generatedAt)).append('\n')
        append("覆盖：").append(groups.size).append(" 个群，").append(reportable.size).append(" 条去重文本\n")
        if (reportable.isEmpty()) {
            append("\n暂无可用于日报的文本。\n")
            return@buildString
        }
        reportable.groupBy { it.groupNameHint }.forEach { (group, candidates) ->
            append("\n【").append(group).append("】\n")
            section("关键消息", candidates.take(MAX_ITEMS), this)
            section("待办线索", candidates.filter { it.content.containsAny(TODO_MARKERS) }.take(MAX_ITEMS), this)
            section("风险线索", candidates.filter { it.content.containsAny(RISK_MARKERS) }.take(MAX_ITEMS), this)
        }
    }

    private fun section(title: String, candidates: List<CandidateDigest>, output: StringBuilder) {
        output.append('\n').append(title).append("：\n")
        if (candidates.isEmpty()) {
            output.append("- 无\n")
            return
        }
        candidates.forEach { candidate ->
            val repeats = if (candidate.seenCount > 1) "（同屏出现 ${candidate.seenCount} 次）" else ""
            output.append("- [").append(candidate.groupNameHint).append("] ")
                .append(candidate.sender).append("：")
                .append(candidate.content.replace(Regex("\\s+"), " ").take(MAX_CONTENT_LENGTH))
                .append(repeats).append('\n')
        }
    }

    private fun String.containsAny(markers: List<String>) = markers.any(::contains)

    private fun isReportable(candidate: CandidateDigest): Boolean =
        !candidate.groupNameHint.contains("屏幕共享") &&
            !candidate.content.contains("按住说话") &&
            CandidateKind.fromContent(candidate.content) == CandidateKind.TEXT

    private const val MAX_ITEMS = 5
    private const val MAX_CONTENT_LENGTH = 180
}
