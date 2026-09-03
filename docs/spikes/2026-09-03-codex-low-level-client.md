# Spike 8a — `openai-codex` 저수준 client 이관 viability

> 결과: **성공 (코드 수준)**. 실제 App Server 대상 approval fixture는 미확보 — 8b 참조.  
> 대상: `openai-codex==0.147.0` (설치본 검사)  
> 일자: 2026-09-03

## 질문

공개 API만으로 (1) approval 관련 필드를 생략한 `thread/start`, (2) `thread/resume`, (3) `turn/start`와 turn notification 구독, (4) `turn/interrupt`, (5) approval handler 등록이 가능한가.

## 확인 결과

| 항목 | 결과 | 근거 |
|---|---|---|
| 고수준 `Codex`/`AsyncCodex`에 handler 주입 | **불가** | 둘 다 `CodexClient(config=config)`를 handler 없이 생성 (`api.py:82`, `api.py:297`) |
| `thread_start()` 기본 approval | `ApprovalMode.auto_review` 강제 | `api.py:374` 기본 인자. 생략해도 `on-request + auto_review`가 전송됨 |
| `openai_codex.client.CodexClient` | 사용 가능 | 모듈은 underscore 없음, 클래스 docstring 있음. 단 `__all__`에 재수출되지 않음 → **버전 고정 필수** |
| `CodexClient(approval_handler=...)` | 공개 생성자 인자 | `client.py:215-221`. 시그니처 `Callable[[str, JsonObject \| None], JsonObject]` |
| `thread_start(params: JsonObject)` | 필드 생략 가능 | `ThreadStartParams`의 `approval_policy`, `approvals_reviewer` 모두 Optional. dict를 넘기면 없는 키는 전송되지 않음 |
| wire 값 | `approvalPolicy`: `untrusted`/`on-request`/`never`, `approvalsReviewer`: `user`/`auto_review`/`guardian_subagent`, `sandbox`: `read-only`/`workspace-write`/`danger-full-access` | `generated/v2_all.py:319,235,3678` |
| `turn_start(thread_id, input_items, params)` | notification queue를 등록하고 반환 | `client.py:602-620` |
| `next_turn_notification(turn_id)` | blocking; 별도 thread에서 호출 | `client.py:373` |
| `turn_interrupt(thread_id, turn_id)` | 공개 | `client.py:641` |
| handler 호출 위치 | **reader thread 동기 호출** | `client.py:803-811` `_reader_loop` |
| 기본 handler | command/file approval에 **무조건 `accept`** | `client.py:773-779` |
| requestApproval params/response 타입 | generated 모델 **없음** | server→client request는 raw dict로만 전달. 결정 문자열(`accept`, `acceptForSession`, `decline`, `cancel`)은 App Server 문서 기준 — 실제 fixture로 확인 필요 |
| user-input request | 없음 | generated 타입에 `requestUserInput` 계열 없음 |
| Codex 바이너리 | pip 의존성 `openai-codex-cli-bin`에 포함 | `codex_cli_bin/bin/codex` |

## 결정

- B1은 **선호안**으로 확정한다: host를 `CodexClient` 위에 재구성하고 `PROVIDER_DEFAULT`는 approval 필드를 생략한다.
- B1 handler 정책 표를 host에 구현한다. 어떤 정책에서도 자동 `accept`는 없다.
- A1/A2는 8b로 진행한다. 제약: handler는 reader thread를 막으므로 **cancel/close는 pending queue에 결정을 먼저 넣고, handler가 그 결정을 반환했음을 확인한 뒤 `turn_interrupt`/`close`를 호출한다.** 결정 응답의 실제 write는 SDK reader thread가 수행하므로 interrupt 요청과의 wire 순서는 best-effort다.
- 위험: `openai_codex.client`가 재수출되지 않으므로 minor 업데이트에서 바뀔 수 있다. `requirements-codex.txt`의 `==0.147.0` 고정을 유지하고 host test가 stub client로 인터페이스를 고정한다.

## 8b 상태

stub App Server(JSON-RPC over stdio)로 request → Kotlin event → respond → 재개, 미응답 중 cancel/close 순서를 검증한다 (`bridges/tests/test_codex_bridge.py`). 실제 App Server의 `requestApproval` payload와 결정 문자열 fixture는 로그인된 환경에서 캡처해야 하며, 캡처 전까지 `availableDecisions`는 문서 기준 4종을 그대로 노출한다.
