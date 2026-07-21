# Personal WeChat Group Daily Bot - Solution Design

> Generated from the confirmed MVP scope. External inspiration/review agents were unavailable in this workspace, so this is a direct engineering plan.

## Overview

**Goal**: Run a project-owned personal WeChat Bot account on a dedicated Android phone, collect new messages from every group the Bot can see, and produce an AI daily summary and action list for the customer.

**Readiness Score**: 84/100

**Generated**: 2026-07-21

**Last confirmed**: 2026-07-21

## Requirements Summary

### Problem Statement

Customers add the supplied Bot account to their WeChat groups. The Bot must continuously collect the new group messages visible to that account and deliver a daily summary, decisions, risks, and actionable to-dos.

### Scope

In scope for the MVP:

- Personal WeChat only; no WeCom support.
- One project-owned Android phone and one Bot account.
- Every group the Bot account belongs to is automatically in scope; no customer-maintained group whitelist.
- New messages after the Bot is deployed, not historical messages from before it joined a group.
- Text messages only. Images, voice, files, recalled messages, and system notices are recorded as typed placeholders, not parsed content.
- Daily Markdown/HTML report with summary, decisions, to-dos, owners, deadlines, risks, and coverage metadata.
- A project-controlled backend receives Bot messages and generates reports.

Out of scope for the MVP:

- Reading customer phones or chats the Bot cannot see.
- Bot private chats; the Agent must ignore them rather than merely omit them from reports.
- Automatic replies, group management, media OCR, voice transcription, and multi-account support.
- Arbitrary JavaScript execution from the production server.

### Success Criteria

- [ ] The Agent installs and runs on a real Android phone without Root.
- [ ] It discovers a newly joined group from Bot-visible traffic and creates one local conversation record.
- [ ] Test-group messages are persisted exactly once despite duplicate notifications or retries.
- [ ] On a network interruption, locally queued messages upload after recovery without duplicates.
- [ ] A scheduled daily run produces a report containing a summary, to-dos, and source coverage.
- [ ] The Agent detects and reports inaccessible WeChat UI, logout, disabled permissions, and lost backend connection.

### Constraints and Assumptions

- The Bot phone, WeChat account, backend, and report delivery channel are controlled by the project.
- The Bot phone is dedicated, powered, networked, and exempted from battery optimization.
- Raw Bot-visible messages are sent to the project backend for summarization. If that changes, the summarizer must run on-device or in a customer-controlled private deployment.
- Group participants are informed of the Bot's collection purpose, scope, retention period, and report usage.
- The legacy `app` / `inrt` runtime remains incomplete because its OCR and terminal modules are absent. The fixed-function `bot` module is independently buildable and reuses only the existing `automator` and `common` libraries.

### Confirmed Implementation Decisions

| Decision | Fixed MVP choice |
|---|---|
| Target chat app | Personal WeChat only |
| Runtime | Project-owned real Android Bot phone; Mac is development/deployment only |
| Collection scope | Every Bot-visible group conversation, discovered automatically |
| Trigger | Notification/UI event enqueues collection work |
| UI access | Accessibility-node text first, OCR only as fallback |
| UI concurrency | One serialized collection queue per Bot device |
| Local store | SQLite message and upload queue |
| Production control | Typed authenticated API; no server-supplied executable JS |
| MVP content | New group text messages and daily report; no media interpretation |

## Architecture

### Approach

Build a fixed-function Android Agent instead of extending the current AutoX.js editor product. Reuse the existing accessibility, notification, scheduling, and image primitives only where they simplify the Agent; replace the existing plaintext, arbitrary-script WebSocket path with typed HTTPS/WSS APIs.

### Key Components

- **Agent bootstrap and health**: Starts after boot, checks permissions, WeChat login state, network, and service health, and reports a heartbeat.
- **Notification ingress**: Receives new-message hints and inserts conversation work into a single local queue.
- **Conversation collector**: Opens the target conversation, reads accessibility nodes, scrolls only as needed, and detects the prior collection cursor.
- **Message normalizer and store**: Converts UI fragments into messages, creates a stable local conversation fingerprint, deduplicates, and stores an upload queue in SQLite.
- **Sync client**: Uses per-device credentials to batch-upload records and retains unsent work until the backend acknowledges it.
- **Control plane**: Supplies typed configuration such as collection enablement and report schedule; it never supplies executable scripts.
- **Ingestion and report service**: Validates uploads, stores messages, aggregates by group/day, calls the LLM, and stores the structured report.
- **Report delivery**: Sends or exposes the daily report to the configured recipient/channel.
- **Operations console**: Shows device health, group discovery, collection coverage, report status, and errors.

### Data Flow

```text
WeChat group message visible to Bot
  -> notification/UI event
  -> Android Agent conversation queue
  -> accessibility-based UI collection
  -> SQLite message + upload queue
  -> authenticated HTTPS/WSS upload
  -> backend message store
  -> nightly per-group aggregation
  -> LLM structured summary
  -> daily report + delivery
```

### Core Data Entities

- **Device**: Bot phone identity, app version, health, last seen.
- **BotAccount**: WeChat login state and its associated Device.
- **Conversation**: Local stable fingerprint, current display name, type (`group`), discovery time, latest cursor.
- **Message**: Conversation, sender display name, content, message type, displayed time, collection time, content fingerprint.
- **UploadBatch**: Local messages, retry state, acknowledgment token.
- **DailyReport**: Conversation/day coverage, structured summary, to-dos, report state, delivery result.

## Implementation Plan

### Locked implementation order

| Order | Gate | Do not start next phase until |
|---:|---|---|
| 1 | Build baseline | A debug APK installs and launches on the real Bot phone |
| 2 | Agent shell | Permissions, foreground health check, and device heartbeat work without a Mac connection |
| 3 | One-group collector | One test group stores each new text message once in local SQLite |
| 4 | Reliable collector | Group discovery, serialized UI work, cursor, deduplication, restart, and offline recovery work |
| 5 | Backend and daily report | Device uploads, backend idempotency, AI summary, and one report delivery work end-to-end |
| 6 | Soak test | The Bot runs continuously and reports failures instead of silently missing data |

### Step 0: Restore a buildable Android baseline

- **Actions**: Inventory the missing Gradle modules and create a minimal fixed-function Agent module that imports only the required automation libraries.
- **Deliverables**: Reproducible Mac build command, debug APK, successful installation on the physical Bot phone.
- **Dependencies**: Android Studio, SDK matching the project, USB debugging, real test phone.

**Current status (2026-07-21)**: The `bot` module builds successfully with JDK 11 and Android SDK API 32. The generated APK is `bot/build/outputs/apk/debug/bot-debug.apk`; installation and launch on the real Bot phone are still required to close this gate. The reproducible build command is:

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@11/libexec/openjdk.jdk/Contents/Home \
  bash gradlew --no-daemon :bot:assembleDebug --console=plain
```

### Step 1: Create the fixed-function Agent shell

- **Actions**: Add startup/health checks, foreground service, structured logs, configuration storage, and a device registration flow.
- **Deliverables**: Agent that starts, stays healthy, exposes no script editor, and reports device/permission/login state.
- **Dependencies**: Step 0.

**Current status (2026-07-21)**: The initial shell exists in `bot/`: a status page, manual accessibility-settings entry point, a foreground health notification, and a WeChat-package-only accessibility heartbeat. It deliberately does not collect or persist message content yet.

### Step 2: Implement single-group message collection

- **Actions**: Inspect real personal-WeChat UI nodes on the test phone; implement navigation, visible-message parsing, message normalization, and local deduplication for one stable test group.
- **Deliverables**: A local SQLite database containing uniquely collected test-group messages.
- **Dependencies**: Step 1, real WeChat test account and test group.

### Step 3: Add automatic group discovery and collection state machine

- **Actions**: Handle notification-triggered work, classify group versus private conversations, serialize navigation, persist cursors, scroll to catch up, and recover from common popups and failed navigation.
- **Deliverables**: All Bot-visible group conversations are discovered and their new messages are collected with observable coverage.
- **Dependencies**: Step 2.

### Step 4: Build reliable backend synchronization

- **Actions**: Define versioned device registration, heartbeat, message upload, acknowledgment, and error APIs; implement TLS, device credentials, local retry/backoff, and server-side idempotency.
- **Deliverables**: Messages survive offline periods and arrive exactly once logically at the backend.
- **Dependencies**: Step 2 local store; backend environment.

### Step 5: Build the daily-summary pipeline

- **Actions**: Aggregate messages by conversation and local day; chunk long histories; ask the LLM for a validated structured result; render Markdown/HTML; record source time range and message count.
- **Deliverables**: One daily report per conversation with summary, decisions, to-dos, owners, deadlines, risks, and coverage.
- **Dependencies**: Step 4, LLM provider/private deployment, report template.

### Step 6: Deliver reports and add operations visibility

- **Actions**: Implement the first delivery channel, report viewing, device/group/report status, alerting for stalled collection, and manual report regeneration.
- **Deliverables**: Customer receives a daily report and operators can diagnose a failed Bot without ADB access.
- **Dependencies**: Step 5.

### Step 7: Soak test and harden before expansion

- **Actions**: Run the Bot continuously with test groups, simulate network loss, low battery, app restarts, duplicate notifications, renamed groups, and WeChat login/UI changes.
- **Deliverables**: A release checklist, recovery playbook, compatibility matrix, and prioritized fixes.
- **Dependencies**: Steps 1-6.

## Technical Considerations

- Prefer accessibility-node text over screenshots and OCR; use OCR only when the view hierarchy lacks usable text.
- The collector must be single-threaded at the UI-navigation level. Message ingestion and uploads may run in background queues.
- Conversation membership defines product scope, but a durable local conversation fingerprint is still needed for deduplication and per-group reporting.
- A notification is a trigger, not authoritative message content. The collector should verify content in the conversation UI.
- Treat missing timestamps, system notifications, recalled messages, images, and voice messages as explicit message types rather than forcing them into text.
- Do not rely on a Mac or ADB connection after installation; the phone must run independently.
- Update this document in the same commit whenever the collection scope, message schema, Agent/API contract, report output, or rollout order changes.

## Risk Management

| Risk | Impact | Likelihood | Mitigation |
|---|---|---:|---|
| Current project cannot build | High | High | Resolve missing Gradle modules before product development. |
| Personal WeChat UI changes | High | High | Version selectors, capture diagnostics, test each supported app version, fail visibly. |
| Missed/duplicate messages | High | Medium | Notification trigger + UI verification + cursor + idempotency key + coverage metrics. |
| Android kills background work | High | Medium | Dedicated powered device, foreground service, battery exclusions, heartbeat and restart checks. |
| Unsafe remote control | High | High | Remove arbitrary remote-script execution; use authenticated typed commands over TLS. |
| LLM invents details | Medium | Medium | Require structured output with source references; mark uncertain owners/deadlines; support regeneration. |
| Data exposure | High | Medium | Minimize permissions, use TLS, encrypt backend storage, define retention/deletion, audit access. |
| Bot cannot recover from UI state | High | Medium | Explicit state machine, timeout/retry, screenshot/node-tree diagnostic capture, operator alert. |

## Acceptance Criteria

- [ ] One dedicated Android phone runs the Agent continuously without a Mac connection.
- [ ] Adding the Bot to a test group causes that group to be discovered automatically.
- [ ] New text messages in test groups appear once in the backend within the configured sync window.
- [ ] A deliberate restart and temporary offline period do not cause loss or duplication.
- [ ] The report contains only messages collected from Bot-visible conversations during its declared reporting window.
- [ ] Operators can see why a Bot is unhealthy and can recover it without sending arbitrary JavaScript.

## Alternative Approaches Considered

- **Continue using the current remote-script WebSocket protocol**: rejected for production because it allows arbitrary executable code and uses plaintext WebSocket construction.
- **Use OCR as the main collector**: rejected because accessibility text is more structured, cheaper, and more reliable when available.
- **Require a customer-maintained group whitelist**: rejected for the MVP because Bot membership is the confirmed product boundary.
- **Run the Bot in an emulator/cloud phone**: rejected for production; retain only for development testing.
