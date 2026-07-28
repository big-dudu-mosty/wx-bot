package com.flowbot.agent

import android.graphics.Rect
import com.flowbot.agent.db.MessageEntity
import com.google.mlkit.vision.text.Text
import java.security.MessageDigest

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

        fun computeHash(groupName: String, sender: String, content: String, timestampText: String): String {
            val input = "$groupName|$sender|$content|$timestampText"
            val digest = MessageDigest.getInstance("SHA-256")
            return digest.digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
        }
    }

    fun parse(text: Text): List<ParsedMessage> {
        if (text.textBlocks.isEmpty()) return emptyList()

        val blocks = text.textBlocks
            .filter { it.boundingBox != null }
            .sortedBy { it.boundingBox!!.top }

        // Extract group name from top bar area
        val topThreshold = (screenHeight * 0.10).toInt()
        val groupName = blocks
            .filter { it.boundingBox!!.top < topThreshold }
            .maxByOrNull { it.text.length }
            ?.text?.trim()
            ?.replace(Regex("""[\(（]\d+[\)）]$"""), "")  // Remove member count like "(123)" or "（123）"
            ?.replace(GROUP_NAME_LEADING_NOISE, "")       // Remove leading < arrows, digits
            ?.replace(GROUP_NAME_TRAILING_NOISE, "")      // Remove trailing single char noise
            ?.trim()
            ?.ifEmpty { "unknown_group" }
            ?: "unknown_group"

        // Process remaining blocks (below top bar)
        val chatBlocks = blocks.filter { it.boundingBox!!.top >= topThreshold }

        val messages = mutableListOf<ParsedMessage>()
        var currentTimestamp = ""
        var currentSender = ""
        val contentBuffer = StringBuilder()

        for (block in chatBlocks) {
            val box = block.boundingBox!!
            val blockText = block.text.trim()

            when {
                isTimestamp(blockText, box) -> {
                    // Flush previous message if content exists
                    flushMessage(messages, groupName, currentSender, contentBuffer, currentTimestamp)
                    currentTimestamp = blockText
                    currentSender = ""
                }
                isSenderNickname(block, box) -> {
                    // Flush previous message if content exists
                    flushMessage(messages, groupName, currentSender, contentBuffer, currentTimestamp)
                    currentSender = blockText
                }
                else -> {
                    // Message content
                    if (contentBuffer.isNotEmpty()) contentBuffer.append("\n")
                    contentBuffer.append(blockText)
                }
            }
        }

        // Flush last message
        flushMessage(messages, groupName, currentSender, contentBuffer, currentTimestamp)

        return messages
    }

    fun toEntities(parsedMessages: List<ParsedMessage>, rawText: String): List<MessageEntity> {
        val now = System.currentTimeMillis()
        return parsedMessages.map { msg ->
            MessageEntity(
                groupName = msg.groupName,
                sender = msg.sender,
                content = msg.content,
                timestampText = msg.timestampText,
                rawText = rawText,
                collectedAt = now,
                contentHash = computeHash(msg.groupName, msg.sender, msg.content, msg.timestampText),
            )
        }
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

    private fun isTimestamp(text: String, box: Rect): Boolean {
        val trimmed = text.trim()
        // Check if horizontally centered
        val centerX = (box.left + box.right) / 2
        val screenCenterX = screenWidth / 2
        val tolerance = screenWidth * 0.25  // Slightly more generous tolerance
        val isCentered = Math.abs(centerX - screenCenterX) < tolerance

        // Also check: timestamps are usually short and single-line
        val isShortEnough = trimmed.length <= 20

        return isCentered && isShortEnough && TIME_PATTERN.matches(trimmed)
    }

    private fun isSenderNickname(block: Text.TextBlock, box: Rect): Boolean {
        val text = block.text.trim()
        // Sender nicknames are typically:
        // - Short (less than 20 chars)
        // - Left-aligned (left edge < 40% of screen width)
        // - Small height (single line, height < 3% of screen)
        // - Not matching message-like patterns
        val isShort = text.length in 1..20
        val isLeftAligned = box.left < screenWidth * 0.4
        val isSmallHeight = (box.bottom - box.top) < screenHeight * 0.03
        val hasNoNewlines = !text.contains("\n")

        return isShort && isLeftAligned && isSmallHeight && hasNoNewlines && !TIME_PATTERN.matches(text)
    }
}
