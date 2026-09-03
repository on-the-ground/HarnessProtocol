# Bridge protocol

Kotlin adapter와 SDK host process 사이의 NDJSON 프로토콜이다. 내부 계약이며 소비자에게 노출되지 않지만, host를 다른 언어로 다시 쓰거나 새 provider를 붙일 때의 기준이다.

## Transport

- host는 stdin으로 요청을, stdout으로 응답과 이벤트를 한 줄에 하나의 JSON 객체로 주고받는다. stderr는 진단용이며 Kotlin이 마지막 32KB를 보관해 실패 메시지에 붙인다.
- Kotlin은 host를 lazy하게 시작한다. host가 예기치 않게 종료되면 pending 요청과 진행 중 execution을 모두 `TRANSPORT` 실패로 끝내고, 그 bridge는 이후 요청을 거절한다. **재시작하지 않는다.**

## 메시지

### Kotlin → host

```json
{"kind":"request","id":1,"method":"create_session","params":{...}}
```

| method | params | result |
|---|---|---|
| `create_session` | spec envelope | `{sessionId}` |
| `resume_session` | `{sessionId, spec}` | `{sessionId}` — host/provider가 정규화한 ID |
| `release_session` | `{sessionId}` | `{}` |
| `start_execution` | `{sessionId, input: {type:"text", text}}` | `{executionId}` |
| `cancel_execution` | `{executionId}` | `{}` |
| `respond_interaction` | `{executionId, interactionId, response: {decision}}` | `{}`; unknown/duplicate는 error |

spec envelope: `instructions?`, `model?`, `workingDirectory?`, `skills: [{name, path}]`, `filesystem`, `additionalWritableRoots`, `network`, `approval`. 값이 없는 필드는 키를 생략한다(`null`과 `""`를 구분하기 위해). Gemini host는 policy 필드를 받지 않는다(validate가 앞에서 거절).

decision: `approve_once`, `approve_for_session`, `decline`, `cancel`.

### host → Kotlin

```json
{"kind":"response","id":1,"result":{...}}
{"kind":"response","id":1,"error":{"type":"ValueError","message":"..."}}
{"kind":"event","executionId":"turn-1","payload":{...}}
```

이벤트 payload는 provider 원본을 그대로 담되(Codex: `{method, payload}`, Gemini: `{type, value}`), host가 합성하는 이벤트가 있다.

| host 합성 이벤트 | 의미 |
|---|---|
| Codex `interaction_requested` `{interactionId, kind:"approval", effect, workId, prompt, availableDecisions, detail}` | approval request가 caller에게 넘어갔다 |
| Codex `interaction_resolved` `{interactionId, resolution: {type:"responded", decision} \| {type:"cleared", reason}}` | request가 닫혔다 |
| Codex `warning` `{kind, message}` | host 판정 경고 (예: 정책상 거절한 승인 요청) |
| Codex `error` `{error: {message, type}}` | host의 stream 실패 |
| Gemini `execution_started`, `execution_completed`, `execution_failed`, `execution_cancelled` | SDK가 turn 단위 terminal을 주지 않아 host가 합성 |

## 규칙

1. execution당 terminal은 정확히 하나다. Kotlin은 첫 terminal 뒤의 이벤트를 무시한다.
2. Kotlin은 `start_execution` 응답 전에 도착한 이벤트도 버리지 않는다(execution별 mailbox를 필요 시 만든다). release된 execution의 이벤트는 protocol error로 기록만 한다.
3. host는 approval request에 답을 전달하기 전에 pending entry를 지우지 않는다. 중복 응답과 이미 정리된 request는 error 응답이다.
4. cancel/release/close는 열린 approval을 먼저 `cancel` 결정으로 풀고(`interaction_resolved` cleared), handler가 반환한 것을 확인한 뒤 provider를 중단한다. Codex SDK의 handler가 reader thread를 막기 때문이다.
5. host는 approval을 스스로 `accept`하지 않는다.
