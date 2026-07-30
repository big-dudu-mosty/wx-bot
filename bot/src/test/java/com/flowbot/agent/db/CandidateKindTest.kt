package com.flowbot.agent.db

import org.junit.Assert.assertEquals
import org.junit.Test

class CandidateKindTest {
    @Test
    fun marksOnlyFileAndMediaCardsAsUnsupported() {
        assertEquals(CandidateKind.UNSUPPORTED_MEDIA, CandidateKind.fromContent("报告.pdf / PDF / 1.5 MB未下载"))
        assertEquals(CandidateKind.UNSUPPORTED_MEDIA, CandidateKind.fromContent("分享视频:櫻花草 / 歌手:ALL-RANGE"))
        assertEquals(CandidateKind.TEXT, CandidateKind.fromContent("明天发 PDF 报告给客户"))
    }
}
