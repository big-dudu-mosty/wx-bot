package com.flowbot.agent.db

import com.flowbot.agent.MessageParser
import java.security.MessageDigest

object CollectionStore {
    // ponytail: identical viewport within five seconds only; add conversation-aware overlap after group identity exists.
    private const val OBSERVATION_DEDUP_WINDOW_MS = 5_000L

    fun saveObservation(
        database: MessageDatabase,
        traceId: String,
        rawText: String,
        groupNameHint: String,
        parsed: List<MessageParser.ParsedMessage>,
        capturedAt: Long = System.currentTimeMillis(),
    ): SaveResult {
        val viewportHash = hash(rawText)
        var result = SaveResult(duplicate = false, candidateCount = 0)

        database.runInTransaction {
            val dao = database.collectionDao()
            if (dao.findRecentObservation(viewportHash, capturedAt - OBSERVATION_DEDUP_WINDOW_MS) != null) {
                dao.insertEvent(event(traceId, "OBSERVATION", "SKIPPED", "DUPLICATE_VIEWPORT", "same viewport in dedup window"))
                result = SaveResult(duplicate = true, candidateCount = 0)
                return@runInTransaction
            }

            val confidence = parsed.map(::confidence).average().toFloat().takeIf { !it.isNaN() } ?: 0f
            val observationId = dao.insertObservation(
                ObservationEntity(
                    groupNameHint = groupNameHint,
                    ocrText = rawText,
                    viewportHash = viewportHash,
                    capturedAt = capturedAt,
                    parseConfidence = confidence,
                ),
            )
            val candidates = parsed.map {
                MessageCandidateEntity(
                    observationId = observationId,
                    sender = it.sender,
                    content = it.content,
                    timestampText = it.timestampText,
                    groupNameHint = it.groupName,
                    confidence = confidence(it),
                    fingerprint = hash("${it.groupName}|${it.sender}|${it.content}|${it.timestampText}"),
                    kind = CandidateKind.fromContent(it.content),
                )
            }
            if (candidates.isNotEmpty()) dao.insertCandidates(candidates)
            dao.insertEvent(event(traceId, "PERSIST", "SUCCESS", null, "observation=$observationId candidates=${candidates.size}"))
            result = SaveResult(duplicate = false, candidateCount = candidates.size)
        }
        return result
    }

    fun recordEvent(database: MessageDatabase, traceId: String, stage: String, outcome: String, errorCode: String?, detail: String) {
        database.collectionDao().insertEvent(event(traceId, stage, outcome, errorCode, detail))
    }

    private fun event(traceId: String, stage: String, outcome: String, errorCode: String?, detail: String) = CollectionEventEntity(
        traceId = traceId,
        stage = stage,
        outcome = outcome,
        errorCode = errorCode,
        detail = detail,
        createdAt = System.currentTimeMillis(),
    )

    private fun confidence(message: MessageParser.ParsedMessage): Float = when {
        message.sender == "unknown" -> 0.4f
        message.timestampText.isBlank() -> 0.6f
        else -> 0.8f
    }

    private fun hash(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }

    data class SaveResult(val duplicate: Boolean, val candidateCount: Int)
}
