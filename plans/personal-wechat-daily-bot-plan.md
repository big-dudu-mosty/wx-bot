# Personal WeChat Group Daily Bot - Solution Design

**Status**: Architecture baseline; implementation is not production-ready.

**Last confirmed**: 2026-07-29

## 1. Goal and release boundary

The project supplies a dedicated Android phone and a project-owned personal WeChat Bot account. A customer adds the Bot to a group; the Bot collects new text visible to that account, produces a daily report, and sends that report to the recipient by Bot private chat.

This document separates the desired product flow from what has been proven:

| Item | Status |
|---|---|
| Dedicated Redmi K80 can run the Agent | Verified |
| A visible WeChat group screen can be captured and OCR'd | Not currently viable on the Redmi K80: HyperOS protected the MediaProjection frame |
| Continuous capture session and local Room message storage exist in code | Implemented; needs data-correctness testing |
| Correct group-only collection, multi-group navigation, deduplication, upload, report, and delivery | Header-based group candidate filtering implemented; not verified as a complete solution |
| Customer delivery using personal-WeChat automation | Blocked pending a platform-authorization decision |

The personal-WeChat route is an internal technical validation only until the project obtains a suitable authorization decision. It must not be represented as a stable customer-delivery capability before that gate is resolved.

## 2. Locked MVP choices

| Decision | Choice |
|---|---|
| Chat product | Personal WeChat only for the current technical validation |
| Bot identity | Project-owned WeChat account on a project-owned real Android phone |
| Scope | Every Bot-visible conversation confirmed to be a group; no manual group whitelist. Current candidate filter accepts only an OCR title with a member count, such as `测试群(5)`; it stores nothing when the title cannot be confirmed. |
| Exclusions | Private chats, customer phones, historical messages before collection starts, user-uploaded images/voice/files |
| Message content | Text first; media is represented only as an unsupported message type |
| UI reading | Screenshot OCR was the candidate route because accessibility nodes do not expose message text. On the 2026-07-30 Redmi K80 test, HyperOS supplied the projection only with the `screen sharing / protected` overlay rather than WeChat content; do not bypass this device protection. |
| Screenshot retention | Source image exists only in memory for OCR and is never persisted by default |
| Local persistence | Android private SQLite database |
| Report delivery | Bot WeChat account privately sends the daily report to the configured user |
| Remote control | Typed APIs only; no server-supplied executable scripts |

## 3. End-to-end logic

```text
New WeChat signal
  -> CollectionJob
  -> health/session check
  -> locate conversation
  -> classify GROUP / PRIVATE / UNKNOWN
  -> capture visible UI and OCR in memory
  -> Observation
  -> MessageCandidate
  -> deduplicate and confirm Message
  -> local upload outbox
  -> backend ingestion
  -> per-recipient daily aggregation
  -> report generation with coverage metadata
  -> Bot private-chat delivery and delivery status
```

Notifications and accessibility events are only hints that work may be needed. They are not authoritative message records. The rendered Bot-visible conversation UI is the evidence source.

If the conversation cannot be confirmed as a group, it remains `UNKNOWN` and must not create a formal message record. The system must never silently treat a private chat as a group.

## 4. Capture session and operational states

The agent has explicit states:

```text
READY -> CAPTURE_SESSION_ACTIVE -> COLLECTING -> PARSING -> LOCAL_STORED
      -> PENDING_UPLOAD -> UPLOADED

Any failure -> BLOCKED(reason) or RETRY_PENDING(reason)
```

`BLOCKED` is a visible state, not a missing log. Required reasons include:

- `CAPTURE_SESSION_STOPPED`
- `ACCESSIBILITY_DISABLED`
- `WECHAT_LOGGED_OUT`
- `WECHAT_NOT_FOREGROUND`
- `CHAT_TYPE_UNKNOWN`
- `OCR_EMPTY`
- `PARSER_LOW_CONFIDENCE`
- `DATABASE_WRITE_FAILED`
- `NETWORK_UNAVAILABLE`
- `UPLOAD_FAILED`
- `REPORT_SEND_FAILED`

The phone must not attempt to bypass screen-capture consent. If the capture session ends, collection stops, the reason is recorded, and an operator must re-authorize before collection resumes.

## 5. Data model

### Current implementation snapshot

Commit `addf249` created a one-table `messages` prototype containing OCR-derived `group_name`, `sender`, `content`, `timestamp_text`, `raw_text`, `collected_at`, and a unique content hash. The current implementation upgrades the Room schema to version 2: it preserves the legacy table and adds separate observation, candidate, confirmed-message, conversation, and collection-event tables.

This is a collection prototype, not the final model:

- `group_name` is OCR text and is not a stable conversation identity.
- One OCR page is duplicated in `raw_text` for every parsed message.
- OCR output is stored as a candidate; it does not create a formal message until conversation classification and deduplication are available.
- The content hash can incorrectly remove legitimate repeated messages.

### Target local model

| Entity | Required fields | Purpose |
|---|---|---|
| `Conversation` | stable local ID, display name, type, verification state, discovered time | Separates group identity from changing OCR labels |
| `Observation` | ID, conversation reference when known, OCR page text, viewport hash, captured time, confidence | One OCR page of evidence; no source image file |
| `MessageCandidate` | observation ID, sender text, content text, visible time, layout evidence, confidence | Holds uncertain OCR parsing without polluting formal records |
| `Message` | conversation ID, candidate ID, sender, content, visible/collected time, dedup fingerprint, confidence | The only message entity eligible for upload and reporting |
| `UploadOutbox` | message ID, attempt count, next retry, acknowledgment state | Makes offline upload recoverable |
| `CollectionEvent` | trace ID, stage, outcome, error code, timestamp | Makes failures queryable without logging chat text |
| `ReportDelivery` | recipient, report ID, send attempt, provider result, timestamps | Separates generated from actually sent reports |

No original screenshot is stored. OCR page text belongs once to `Observation`; it must not be copied into every `Message`.

### Deduplication rules

1. De-duplicate identical `Observation` pages only inside a short capture window.
2. De-duplicate messages only when overlapping observations provide the same sender, content, visible time, and layout evidence.
3. When visible time or sender is unknown, prefer `possible_duplicate` over deletion.
4. A person repeating the same text later is a valid new message and must be retained.

## 6. Parsing and report rules

OCR is not a message protocol. The parser emits candidates with confidence, not certainty.

- A missing sender remains `unknown`; it is never guessed.
- A missing visible timestamp remains null; `collected_at` is not substituted as the sender's timestamp.
- Low-confidence candidates do not create decisive owners, deadlines, or conclusions in the report.
- The report includes coverage: groups covered, collection window, message count, and known gaps.

The daily report is one report per configured recipient and local day, with group sections. It contains summary, decisions, to-dos, owners, deadlines, risks, and coverage. Bot private-chat delivery must record `generated`, `attempted`, `sent`, or `failed` separately.

## 7. Diagnosability requirements

Every collection job receives a `trace_id`. Each stage writes one structured `CollectionEvent` containing the trace ID, stage, outcome, error code, timestamp, and safe metadata such as a screen class name or retry count.

Do not put OCR text, sender names, or message bodies into logs. They remain in the database records only.

Required developer workflow:

- Debug builds expose the current state, last trace ID, last error code, queue size, and recent collection events.
- A breakpoint can be placed at job creation, capture completion, parser output, deduplication decision, and database write without changing production behavior.
- Exceptions at a storage, OCR, parsing, upload, or delivery boundary are caught once at that boundary, converted to a named error event, and surfaced in the status screen.
- No broad catch-and-ignore paths; an error must either retry with a bounded policy or end in a visible `BLOCKED` state.

## 8. Implementation gates

| Gate | Deliverable | Acceptance check |
|---|---|---|
| 0 | Platform authorization decision | No customer deployment before a positive decision |
| 1 | Stable `Observation` capture from one test group | A real test message creates exactly one observation with traceable success/failure state |
| 2 | Target local schema | Conversation, observation, candidate, message, event, and outbox are separately persisted |
| 3 | Parser and dedup test | Twenty known test messages: no private records, no missed messages, no screenshot duplicates, and no deleted valid repeated text |
| 4 | Multi-group collection | Group classification, serialized jobs, cursors, bounded catch-up, and coverage reporting |
| 5 | Backend and report | Idempotent HTTPS upload, daily aggregation, report generation, and Bot private-chat send status |
| 6 | Soak test | Lock, restart, lost projection, offline, WeChat logout, and send failure all produce visible recovery states |

## 9. Current work order

1. Run the version-1-to-version-2 Room migration on the Redmi device and confirm the diagnostic view shows observations, candidates, trace ID, and errors. The migration was installed successfully on 2026-07-30; the first run found that current Redmi WeChat reports an open group window as `LauncherUI`. After adding that trigger and re-enabling the accessibility service, the Redmi created two observations and seven candidates with `PERSIST/SUCCESS` and no error. A later accessibility-service reconnect exposed a redundant foreground-state guard: WeChat events arrived but were not captured when the app was already foreground. That guard was removed. A subsequent test found foreground-service crashes before projection initialization; capture requests are now ignored until the user-authorized projection session is active. This passes the one-group capture check only; parsing correctness still requires Gate 3.
2. The later 2026-07-30 Redmi test is blocked earlier: a `B3` group message triggered capture, but every projection frame OCR'd only the HyperOS `screen sharing / protected` overlay. The database recorded `CHAT_TYPE_UNKNOWN`, not the group message. Waiting for a fresh frame and then a settled latest frame removed the app-side capture races but did not change the protected content. Do not implement a bypass; select an authorized data source or a device/OS configuration that permits the intended capture before repeating Gate 3.
3. Only after that prerequisite and Gate 3, implement group classification, multi-group collection, backend synchronization, report generation, and Bot delivery.

## 10. Deferred decisions

- One Bot account per recipient or multiple recipients/customers per Bot account.
- Daily report cutoff time and time zone.
- OCR observation and backend message retention periods.
- The exact platform authorization path for customer delivery.

## 11. Non-goals for this MVP

- Reading customer phones.
- Parsing image, voice, or file payloads.
- Automatic replies, group management, or marketing actions.
- Remote arbitrary-code execution.
- Claiming coverage or delivery success when the underlying collection or send attempt failed.
