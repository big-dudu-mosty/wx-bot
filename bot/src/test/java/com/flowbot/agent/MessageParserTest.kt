package com.flowbot.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageParserTest {
    @Test
    fun preservesTwentyMessagesIncludingRepeatedAndShortText() {
        val parser = MessageParser(screenWidth = 1_080, screenHeight = 5_000)
        val texts = listOf(
            "OK", "哈哈", "收到", "今天 15:00 开会", "我来跟进", "OK", "需求已确认", "明早发版本",
            "辛苦了", "链接在这里", "待会儿同步", "收到", "测试通过", "需要补文档", "我看一下", "今天完成",
            "风险是接口超时", "我来处理", "OK", "谢谢",
        )
        val blocks = mutableListOf(MessageParser.LayoutBlock("测试群 (20)", 160, 100, 920, 170))
        blocks += MessageParser.LayoutBlock("09:00", 470, 520, 610, 570)
        texts.forEachIndexed { index, content ->
            val top = 620 + index * 180
            blocks += MessageParser.LayoutBlock("成员${index % 3}", 80, top, 190, top + 35)
            blocks += MessageParser.LayoutBlock(content, 210, top + 45, 890, top + 85)
        }

        val messages = parser.parse(blocks)

        assertEquals("测试群", messages.first().groupName)
        assertEquals(texts, messages.map { it.content })
        assertEquals(3, messages.count { it.content == "OK" })
        assertTrue(messages.all { it.sender.startsWith("成员") })
    }

    @Test
    fun splitsSeparatedBubblesOnBothSides() {
        val parser = MessageParser(screenWidth = 1_080, screenHeight = 2_772)
        val blocks = listOf(
                MessageParser.LayoutBlock("测试群 (5)", 180, 100, 900, 170),
                MessageParser.LayoutBlock("17:57", 470, 300, 610, 340),
                MessageParser.LayoutBlock("OK", 850, 400, 940, 445),
                MessageParser.LayoutBlock("重复测试", 650, 510, 940, 555),
                MessageParser.LayoutBlock("重复测试", 650, 620, 940, 665),
                MessageParser.LayoutBlock("测试结束", 680, 730, 940, 775),
                MessageParser.LayoutBlock("蔡云轩", 80, 880, 180, 915),
                MessageParser.LayoutBlock("今天做测试", 140, 925, 430, 975),
                MessageParser.LayoutBlock("蔡云轩", 80, 1_080, 180, 1_115),
                MessageParser.LayoutBlock("晚饭吃啥", 140, 1_125, 400, 1_175),
        )
        val messages = parser.parse(blocks)

        assertEquals(listOf("OK", "重复测试", "重复测试", "测试结束", "今天做测试", "晚饭吃啥"), messages.map { it.content })
        assertEquals(listOf("unknown", "unknown", "unknown", "unknown", "蔡云轩", "蔡云轩"), messages.map { it.sender })
        assertTrue(parser.isGroupScreen(blocks))
    }
}
