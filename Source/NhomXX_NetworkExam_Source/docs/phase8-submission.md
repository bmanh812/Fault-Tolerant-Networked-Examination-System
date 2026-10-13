# Phase 8 — Client submission acceptance

Status: DONE. Scope: Client `SUBMIT -> SUBMIT_ACK` and authoritative `AUTO_SUBMIT` push.

## Baseline and verification

- Base commit: `2a1fb60 feat(client): add server-synchronized exam countdown`.
- The initial working tree contained unfinished Phase 8 changes. They were reviewed and completed, not discarded.
- A separate archive of the Phase 7 commit passed **113 tests**, with no failures, errors or skips.
- Completed Phase 8 full suite: **151 tests**, no failures, errors or skips.
- Command: `mvn.cmd test`, with JDK 25 compiling to the existing Java 17 release target.
- Windows sandbox blocks localhost TCP; the successful runs used the approved execution outside that sandbox.
- No Server production code, Protocol v1 code or `docs/protocol.md` changed.
- No reconnect, resync or heartbeat implementation was added.

## Actual frozen contract

Every message retains the four-field v1 envelope: `type`, `protocolVersion`, `requestId`, `payload`.

| Message | Payload (all fields required) | Correlation |
|---|---|---|
| `SUBMIT` | `sessionId: string` | One UUID retained for the logical attempt |
| `SUBMIT_ACK` | `sessionId`, `status: SUBMITTED`, `submittedAt: positive epoch millis` | Same UUID as `SUBMIT` |
| `AUTO_SUBMIT` | `sessionId`, `status: AUTO_SUBMITTED`, `submittedAt: positive epoch millis` | Server-generated UUID; push, not a response |

There is no `serverTime`, `deadline`, score or answer list in submit acknowledgement/push payloads.
Validation checks the frozen payload schema, response type, requestId and current session before finalizing.
Wrong requestId cannot complete the submit future; wrong session/status/type fails without reporting success.

Server behavior was checked in `ClientHandler`, `SubmissionService`, `SessionConnectionRegistry` and `H2SessionRepository`:

- First manual submit before deadline locks/finalizes the session and commits one MANUAL submission before ACK.
- Duplicate manual submit returns the stored final state and original `submittedAt`, with the incoming requestId echoed.
- Persistence idempotency is keyed by **sessionId**, not a requestId lookup table. Client still keeps its logical UUID for retries.
- Submit that first finalizes an expired session creates an AUTO submission, emits the push, and returns `ERROR/EXAM_ENDED`.
- Submit for an already auto-submitted session returns `ERROR/ALREADY_SUBMITTED`.
- Answer updates after finalization return `ALREADY_SUBMITTED`; after deadline while still active they return `EXAM_ENDED`.
- Scheduled deadline, periodic sweep or submit discovering expiry can finalize and publish AUTO_SUBMIT.
- Finalizing a session occurs once. An already auto-submitted session can also be notified when a connection is bound.
- No mismatch against the frozen v1 contract was found.

## State and pending-answer policy

```text
ACTIVE -> SUBMITTING (edits frozen immediately)
             | flush confirmed answers -> send SUBMIT
             | valid SUBMIT_ACK -> SUBMITTED
             | timeout / sync error -> SUBMITTING, retryable while connected
             | socket failure -> DISCONNECTED, identity and drafts retained

ACTIVE or SUBMITTING -> valid AUTO_SUBMIT -> AUTO_SUBMITTED
```

`SUBMITTED` and `AUTO_SUBMITTED` retain the first accepted authoritative final result.
Socket EOF/disconnect does not erase that result. Explicit close still moves the lifecycle to CLOSED, while the result remains inspectable.
The exam snapshot's session status is updated along with the final result, without overwriting answers or inventing confirmed versions.

`beginSubmission()` is local-only and freezes both Core entry points and `ClientExamState.selectAnswer`.
Every unsaved question must reach `confirmedVersion == localVersion` with no pending snapshot before manual SUBMIT.
Unanswered `0/0` questions need no update; submit does not require answering every question.
The flush reuses Phase 5 synchronization, joining an in-flight answer or retrying its immutable snapshot.
If an older snapshot is in flight, it is acknowledged before the latest frozen local version is sent.
Each request uses `client.requestTimeoutMs`; the frozen state allows at most an existing snapshot and then the latest snapshot per question.
Questions flush concurrently. There is no unbounded wait or automatic retry loop.
Failure preserves drafts, keeps edits frozen, and reports that all answers could not be synchronized.

## Logical submit, timeout and races

- `SubmissionAttempt` retains the immutable ProtocolMessage and fixed requestId/sessionId. Repeated calls while in progress share the same future.
- A flag records that SUBMIT may have been sent. Timeout is uncertainty, not evidence that persistence failed.
- Retry uses the same message. It cannot unlock answers or create a new logical submit UUID.
- On transport failure, `submitRequestId()`, `submitRequestSent()`, answer snapshots and sync issues remain available for the later recovery phase.
- Pending transport requests and timeouts are removed normally. Timeout removal checks the exact pending entry, so an old timeout cannot remove a newer retry using the same UUID.
- AUTO_SUBMIT bypasses the pending-response map, validates current session, freezes edits and completes a waiting manual submit with the authoritative AUTO result.
- The superseded manual transport request is removed and its timeout cancelled. A late ACK cannot change AUTO_SUBMITTED.
- If manual ACK is accepted first, a later push cannot replace SUBMITTED. Duplicate events do not notify/finalize twice.
- A pending answer may receive its existing ACK after auto-submit; it cannot initiate another answer version/update or manual submit afterward.
- Final-state checks and the SUBMIT write share the lifecycle lock. UI edits and reader callbacks use the existing synchronized/immutable state model.

## UI and timer

- Swing -> controller -> gateway -> ExamClient -> transport remains the boundary.
- A local choice is recorded before its network work is queued. Immediate submit therefore includes the option just selected.
- Confirmation calls the local freeze immediately; socket work remains on the network executor.
- Queued answer sends after freeze are owned by the submission flush, avoiding late edits or writes after submission.
- The UI shows `Đang đồng bộ đáp án...`, then `Đang nộp bài...`, followed by the authoritative final result.
- Timeout/error reports uncertainty and enables a same-logical-request retry only while connected. Polling cannot unlock options while submitting.
- Final events disable options/Submit and stop countdown exactly once in the presentation layer.
- Local zero still displays the Phase 7 waiting-for-Server state; it never calls submit or decides auto-finalization.
- The countdown calculation and timer classes themselves were not changed.

## New tests

| Suite | Phase 8 cases |
|---|---|
| `ExamClientSubmissionTest` | 25 new tests: payload/state, flush, old/new in-flight versions, edit freeze, snapshot retry, same submit UUID, shared calls, invalid request/session/type/status, timeout/busy, duplicate ACK/push, auto during answer/submit, event order, EOF/close and pending cleanup |
| `ExamUiControllerTest` | 6 added tests: submit off UI thread, timer stop for manual/auto, retry, queued selection/freeze/polling, late error after auto, explicit failure |
| `Phase8ClientSubmissionIntegrationTest` | 5 new tests: manual flush and DB final state, online auto push, exact duplicate TCP submit, gated v3 answer-vs-submit, real persistence followed by lost ACK and same-UUID retry |
| `Phase8SwingSmokeTest` | 2 new automated smoke tests using real entry points, Swing controls, dialog confirmation, scheduled expiry, file H2 and timer/option assertions |

The real-ACK-loss test drops only the first SUBMIT_ACK at a test transport wrapper. Server commits remain real H2 transactions.
The deterministic answer gate is test-only and delegates the actual write to the real H2 repository.
Tests use futures/latches and short request deadlines; no long sleeps or assertion disabling were introduced.

## Smoke and cleanup

Both smoke scenarios run `DatabaseInitMain -> ServerMain -> SwingExamFrame/ExamUiController` against a temporary file database.
Manual smoke selects Q1/Q2, waits for saved statuses, clicks Submit, confirms the real dialog, and checks the final UI and DB.
Auto smoke uses an isolated six-second exam deadline and checks the Server's scheduled push, AUTO row and frozen UI.
Both verify that the actual countdown ticker stopped. Child processes, sockets, executors and windows are closed afterward.
These are automated desktop smoke checks, not a claim that a human manually clicked through the demo.

## Files

Production:
`ClientState.java`, `ClientExamState.java`, `ExamClient.java`, `ExamSubmission.java`, `SubmitException.java`,
`ui/CoreExamClientGateway.java`, `ui/ExamClientGateway.java`, `ui/ExamUiController.java`, `ui/ExamUiView.java`,
`ui/SubmissionDisplayStatus.java`, `ui/SwingExamFrame.java`.

Tests:
`client/ExamClientSubmissionTest.java`, `client/Phase8TestClient.java`, `client/ui/ExamUiControllerTest.java`,
`client/ui/Phase8SwingSmokeTest.java`, `server/Phase8ClientSubmissionIntegrationTest.java`.

Documentation: `README.md`, this file.

Commit message: `feat(client): add submit and auto-submit handling`.
Phase 9 has not started. The preserved submission identity/outcome, immutable answer snapshots, final session status,
connection status and submission callback are the handoff points for that later phase.
