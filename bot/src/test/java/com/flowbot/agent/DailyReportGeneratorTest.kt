package com.flowbot.agent

import com.flowbot.agent.db.CandidateDigest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

class DailyReportGeneratorTest {
    @Test
    fun keepsCoverageAndSeparatesTodoAndRiskSignals() {
        val report = DailyReportGenerator.generate(
            listOf(
                digest("需要小明跟进接口", "项目群"),
                digest("风险：接口可能超时", "项目群"),
                digest("1020.6 KB 未下载：按住说话", "项目群"),
                digest("测试", "(O屏幕共享"),
            ),
            Date(0),
        )

        assertTrue(report.contains("覆盖：1 个群，2 条去重文本"))
        assertTrue(report.contains("待办线索：\n- [项目群] 小明：需要小明跟进接口"))
        assertTrue(report.contains("风险线索：\n- [项目群] 小明：风险：接口可能超时"))
        assertFalse(report.contains("1020.6 KB"))
        assertFalse(report.contains("屏幕共享"))
    }

    private fun digest(content: String, group: String) = CandidateDigest(
        sender = "小明",
        content = content,
        timestampText = "",
        groupNameHint = group,
        confidence = 0.8f,
        firstCapturedAt = 0,
        lastCapturedAt = 0,
        seenCount = 1,
    )
}
