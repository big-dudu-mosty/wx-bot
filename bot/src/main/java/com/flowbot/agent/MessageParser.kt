package com.flowbot.agent

import com.google.mlkit.vision.text.Text

/**
 * Parses ML Kit OCR Text results into structured chat messages.
 *
 * Heuristic rules based on WeChat group chat layout:
 * - Top bar area (Y < 8% of screen height): group name
 * - Horizontally centered blocks matching time patterns: timestamps
 * - Small blocks left-aligned following a timestamp or message: sender nickname
 * - Remaining blocks: message content
 */
class MessageParser(private val screenWidth: Int, private val screenHeight: Int) {

    data class LayoutBlock(
        val text: String,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
    )

    data class ParsedMessage(
        val sender: String,
        val content: String,
        val timestampText: String,
        val groupName: String,
    )

    companion object {
        // Matches WeChat timestamp formats:
        // 12:30, 下午2:36, 上午10:05, 昨天下午1:02, 昨天 14:30,
        // 前天上午9:00, 星期三 下午3:20, 7月24日 下午5:55, 2026年7月24日 上午10:00
        private val TIME_PATTERN = Regex(
            """^([上下]午\s*\d{1,2}:\d{2}|\d{1,2}:\d{2}|昨天\s*[上下]午\s*\d{1,2}:\d{2}|昨天\s*\d{1,2}:\d{2}|前天\s*[上下]午?\s*\d{1,2}:\d{2}|星期[一二三四五六日]\s*[上下]午?\s*\d{1,2}:\d{2}|\d{1,2}月\d{1,2}[日号]\s*[上下]午?\s*\d{1,2}:\d{2}|\d{4}年\d{1,2}月\d{1,2}[日号]\s*[上下]午?\s*\d{1,2}:\d{2})$"""
        )

        // Pattern to clean OCR noise from group name (leading arrows, numbers, trailing single chars)
        private val GROUP_NAME_LEADING_NOISE = Regex("""^[<〈\s\d←‹❮]*""")
        private val GROUP_NAME_TRAILING_NOISE = Regex("""[\s][A-Za-z]$""")
        private val GROUP_HEADER_PATTERN = Regex(""".+[\(（]\d+[\)）]$""")

    }

    fun parse(text: Text): List<ParsedMessage> {
        return parse(
            text.textBlocks
            .filter { it.boundingBox != null }
            .map { block ->
                val box = requireNotNull(block.boundingBox)
                LayoutBlock(block.text, box.left, box.top, box.right, box.bottom)
            },
        )
    }

    fun parse(blocks: List<LayoutBlock>): List<ParsedMessage> {
        if (blocks.isEmpty()) return emptyList()

        val orderedBlocks = blocks.sortedBy { it.top }

        val groupName = groupNameHint(orderedBlocks)

        // Process remaining blocks (below top bar)
        val topThreshold = (screenHeight * 0.10).toInt()
        val chatBlocks = orderedBlocks.filter { it.top >= topThreshold }

        val messages = mutableListOf<ParsedMessage>()
        var currentTimestamp = ""
        var currentSender = ""
        var lastContentBottom: Int? = null
        val contentBuffer = StringBuilder()

        chatBlocks.forEachIndexed { index, block ->
            val blockText = block.text.trim()

            when {
                isTimestamp(blockText, block) -> {
                    // Flush previous message if content exists
                    flushMessage(messages, groupName, currentSender, contentBuffer, currentTimestamp)
                    currentTimestamp = blockText
                    currentSender = ""
                    lastContentBottom = null
                }
                isSenderNickname(chatBlocks, index) -> {
                    // Flush previous message if content exists
                    flushMessage(messages, groupName, currentSender, contentBuffer, currentTimestamp)
                    currentSender = blockText
                    lastContentBottom = null
                }
                else -> {
                    if (currentSender.isEmpty() && startsNewBubble(contentBuffer, lastContentBottom, block)) {
                        flushMessage(messages, groupName, currentSender, contentBuffer, currentTimestamp)
                        currentSender = ""
                    }
                    if (contentBuffer.isNotEmpty()) contentBuffer.append("\n")
                    contentBuffer.append(blockText)
                    lastContentBottom = block.bottom
                }
            }
        }

        // Flush last message
        flushMessage(messages, groupName, currentSender, contentBuffer, currentTimestamp)

        return messages
    }

    fun groupNameHint(text: Text): String = groupNameHint(
        text.textBlocks.filter { it.boundingBox != null }.map { block ->
            val box = requireNotNull(block.boundingBox)
            LayoutBlock(block.text, box.left, box.top, box.right, box.bottom)
        },
    )

    fun isGroupScreen(text: Text): Boolean = isGroupScreen(
        text.textBlocks.filter { it.boundingBox != null }.map { block ->
            val box = requireNotNull(block.boundingBox)
            LayoutBlock(block.text, box.left, box.top, box.right, box.bottom)
        },
    )

    fun isGroupScreen(blocks: List<LayoutBlock>): Boolean {
        val topThreshold = (screenHeight * 0.10).toInt()
        return blocks.any { it.top < topThreshold && GROUP_HEADER_PATTERN.matches(it.text.trim()) }
    }

    fun isScreenShareProtected(ocrText: String): Boolean =
        ocrText.contains("屏幕共享") && ocrText.contains("防护中")

    private fun groupNameHint(blocks: List<LayoutBlock>): String {
        val topThreshold = (screenHeight * 0.10).toInt()
        return blocks
            .filter { it.top < topThreshold }
            .maxByOrNull { it.text.length }
            ?.text?.trim()
            ?.replace(Regex("""[\(（]\d+[\)）]$"""), "")
            ?.replace(GROUP_NAME_LEADING_NOISE, "")
            ?.replace(GROUP_NAME_TRAILING_NOISE, "")
            ?.trim()
            ?.ifEmpty { "unknown_group" }
            ?: "unknown_group"
    }

    private fun flushMessage(
        messages: MutableList<ParsedMessage>,
        groupName: String,
        sender: String,
        contentBuffer: StringBuilder,
        timestampText: String,
    ) {
        if (contentBuffer.isNotEmpty()) {
            messages.add(
                ParsedMessage(
                    sender = sender.ifEmpty { "unknown" },
                    content = contentBuffer.toString(),
                    timestampText = timestampText,
                    groupName = groupName,
                )
            )
            contentBuffer.clear()
        }
    }

    private fun isTimestamp(text: String, block: LayoutBlock): Boolean {
        val trimmed = text.trim()
        // Check if horizontally centered
        val centerX = (block.left + block.right) / 2
        val screenCenterX = screenWidth / 2
        val tolerance = screenWidth * 0.25  // Slightly more generous tolerance
        val isCentered = Math.abs(centerX - screenCenterX) < tolerance

        // Also check: timestamps are usually short and single-line
        val isShortEnough = trimmed.length <= 20

        return isCentered && isShortEnough && TIME_PATTERN.matches(trimmed)
    }

    private fun startsNewBubble(content: StringBuilder, lastBottom: Int?, block: LayoutBlock): Boolean {
        if (content.isEmpty() || lastBottom == null) return false
        // ponytail: one TextBlock normally maps to one bubble; tune only with a failed device sample.
        return block.top - lastBottom > maxOf(16, (screenHeight * 0.01).toInt())
    }

    private fun isSenderNickname(blocks: List<LayoutBlock>, index: Int): Boolean {
        val block = blocks[index]
        val text = block.text.trim()
        // Sender nicknames are typically:
        // - Short (less than 20 chars)
        // - Left-aligned (left edge < 40% of screen width)
        // - Small height (single line, height < 3% of screen)
        // - Not matching message-like patterns
        val isShort = text.length in 1..20
        val isLeftAligned = block.left < screenWidth * 0.4
        val isSmallHeight = (block.bottom - block.top) < screenHeight * 0.03
        val hasNoNewlines = !text.contains("\n")
        val looksLikeContent = text.contains("【") || text.contains("：") || text.contains(":")
        val next = blocks.getOrNull(index + 1)
        // A short left-side block is ambiguous. Treat it as a nickname only when the
        // next OCR block is an indented bubble immediately below it.
        val hasFollowingBubble = next != null &&
            next.top - block.bottom in 0..(screenHeight * 0.05).toInt() &&
            next.left > block.left + (screenWidth * 0.02).toInt()

        return isShort && isLeftAligned && isSmallHeight && hasNoNewlines && !looksLikeContent && hasFollowingBubble && !TIME_PATTERN.matches(text)
    }
}
