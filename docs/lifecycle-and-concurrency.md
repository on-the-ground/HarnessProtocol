# Lifecycle and concurrency

개정된 `AgentHarness`, `AgentSession`, `AgentTask`의 수명·상태·동시성 계약이다. 현재 코드와 KDoc은 후속 구현에서 맞춘다. [Protocol reference](protocol-reference.md), [Event contract](event-contract.md), [설계 기준](semantic-contract.md)을 함께 따른다.

## 소유와 문맥

```text
application
└─ AgentHarness                 위임 경계와 그 경계에서 얻은 handle
   ├─ AgentSession A            공유 문맥
   │  ├─ AgentTask A-1
   │  └─ AgentTask A-2
   └─ AgentSession B            별도 문맥
```

이 트리는 로컬 process·SDK 객체의 필수 배치를 뜻하지 않는다. 하네스 제공 경계는 실제 실행 수단의 수명을 관리하고 애플리케이션은 사용을 마친 handle을 정리한다.

Session은 문맥 연속성을 제공하며, 영속성은 선택 계약이다. 기본 session의 release 이후 보관을 가정하지 않는다. 영속 보관을 요구한 session은 약속한 범위에서 `reopenSession`할 수 있어야 한다. reopening은 진행 중 Task에 재접속하거나 checkpoint에서 실행을 재개하는 연산이 아니다.

## 작업 시작과 직렬화

`startTask`는 작업을 수락하고 handle을 반환한다. 전체 작업의 종료까지 기다리지 않는다. 반환 시점에 이미 진행 상태나 종결 판정이 생겼을 수 있다.

요청 전 거절과, 요청은 전달했으나 시작 응답을 잃어 수락 여부를 모르는 경우를 구별한다. 후자는 handle을 반환하지 못했어도 실제 작업이 있을 수 있다. 그 불확실성을 호출자에게 알리고, 해소 전 같은 문맥의 다음 시작을 차단한다. 확인되지 않은 요청을 새 작업으로 자동 재전송하지 않는다.

같은 session에서 작업은 순차적으로 시작한다. 진행 중 작업에 대한 중첩 시작은 원자적으로 거절하고 provider에 새 요청을 보내지 않는다. 취소 요청만 전달된 상태도 여전히 진행 중이다.

이전 Task가 `Unresolved`로 종결됐다면 실제 작업은 남아 있을 수 있다. 따라서 handle의 terminal 여부만으로 같은 문맥을 다시 사용하도록 허용하지 않는다. 실제 종료와 문맥 일관성을 확인하기 전에는 그 session의 새 작업을 거절한다. release/reopen을 이 제약의 우회 수단으로 사용해서도 안 된다. 확인·복구 방법과 영속 저장소의 차단 상태 처리는 후속 구현에서 검증한다.

서로 다른 session은 논리적으로 격리한다. adapter가 내부 자원 제약 때문에 직렬화할 수 있으므로 병렬 처리량을 보장하지는 않는다. 한 session을 열면서 다른 유효 handle을 조용히 무효화하지 않는다.

## 상태와 outcome

```text
STARTING → RUNNING ⇄ AWAITING_RESPONSE
    └──────── 각 진행 상태에서 종결 가능 ────────┐
                                                ▼
                 COMPLETED | FAILED | CANCELLED | UNRESOLVED
```

- `COMPLETED`: 실행 종료와 결과를 확인했다. 업무 목표 달성과는 별개다.
- `FAILED`: 작업 실패를 확인했다. 통신 단절만으로 이 상태를 추정하지 않는다.
- `CANCELLED`: 취소에 따른 실제 종료를 확인했다. 이전 효과를 되돌렸다는 뜻은 아니다.
- `UNRESOLVED`: 이 handle의 관찰·제어를 끝내면서 실제 종결 결과를 확인하지 못했다.

상태는 해당 handle의 관찰·종결 판정을 나타낸다. outcome은 정확히 한 번 확정하며 모든 waiter가 같은 판정을 회수한다. terminal 뒤 상태를 바꾸거나 다른 outcome으로 덮어쓰지 않는다. 종료 미확정 이후의 추가 확인·재접속은 별도 계약이며 이 상태만으로 지원을 주장하지 않는다.

Outcome waiter를 완료하기 전에 terminal state를 확정하고 모든 열린 interaction을 정리해 pending snapshot을 비운다. 이는 정상 완료·실패·취소·종료 미확정 모두에 적용된다. 이미 답한 요청을 다시 정리하지 않고 종결 뒤의 응답을 거절한다. 별도 flow의 collector callback 순서를 강제하는 의미는 아니다.

## 취소 요청과 경쟁

`requestCancellation()`은 중단을 요청한다. 반환이 실제 중단 확인을 뜻하지 않는다. 요청 전달 실패도 구별해 보고해야 한다.

열린 interaction을 정리하고 실행의 응답 대기를 풀어야 하는 경우 실제 중단 호출과 순서를 맞춘다. 요청 정리는 취소 요청·작업 종결·provider 철회 등 실제 원인을 나타내며 `Cancelled`를 미리 확정하지 않는다.

완료·실패·취소가 경쟁하면 먼저 확인하여 확정한 outcome을 보존한다. terminal 이후의 취소 요청은 no-op이다. 반복 요청으로 중복 효과를 만들지 않으며 자동 재시도로 작업 전체를 다시 시작하지 않는다.

## close와 release

`close`는 harness의 handle과 자원을, `release`는 session handle과 관련 자원을 정리한다. 정리를 여러 번 호출해도 추가 작업을 시작하지 않는다. 정리 중이거나 닫힌 handle에서 새 작업을 시작하지 않는다.

1. 해당 범위의 새 작업을 차단한다.
2. pending interaction을 실제 사유로 정리하고 진행 중 작업의 취소를 요청한다.
3. 제한된 시간 동안 실제 종료 결과를 확인한다.
4. 확인된 outcome을 전달한다. 확인하지 못했다면 `Unresolved`로 handle을 종결한다.
5. 해제할 수 있는 자원·참조를 정리한다. 남은 작업 가능성을 숨기지 않는다.

유예 시간과 자원별 정리 방식은 adapter의 명시된 운영 설정이다. 공통 계약에 특정 process나 2초 유예를 고정하지 않는다. 유예 만료를 취소 성공으로 바꾸지 않는다. 이미 종결된 작업을 close가 덮어쓰지 않는다.

정리 호출은 bounded하게 끝나고, 정리 대상 Task의 `awaitOutcome`도 판정을 회수할 수 있어야 한다. 살아 있는 정상 작업의 전체 실행 시간에 공통 timeout을 강제하는 것은 아니다. 자원 정리 오류 전달과 Task outcome은 구별하며, 예외 때문에 waiter가 방치되지 않게 한다.

## 연결·관찰 상실

Host 종료, 네트워크 단절, terminal 없는 stream 종료는 먼저 관찰·제어 수단의 실패다. 실제 작업도 실패·종료했다는 증거가 있으면 그 결과를 전달하고, 확인할 수 없으면 `Unresolved`로 구별한다. 연결된 process가 죽었다는 사실이 그 process가 요청한 외부 작업까지 중단됐다는 증거는 아니다.

복구가 명시적으로 지원되지 않은 adapter는 새 연결이나 새 작업을 조용히 만들어 재시도하지 않는다. 지원하는 재접속의 식별·정합성·중복 방지 범위는 별도 계약으로 검증한다.

## Coroutine과 관찰

`awaitOutcome` waiter의 coroutine을 취소해도 작업이 자동 취소되는 것은 아니다. 작업도 멈추려면 `requestCancellation`을 명시적으로 호출한다. 애플리케이션 timeout은 기다림의 종료와 실제 작업 종료를 구별해야 한다.

State와 pending snapshot의 정확성은 event 수집과 독립이다. 느리거나 없는 observer가 작업·interaction·outcome 진행을 막지 않는다. 서로 다른 flow의 collector가 갱신을 관찰하는 순간 사이에 전역 순서를 가정하지 않는다.

Consumer는 작업 scope에 observer를 두고 outcome을 회수한 뒤 observer를 정리한다. event flow가 끝났다는 사실만으로 완료를 판단하지 않는다.

## 구현 전환 항목

현재 구현의 close 강제 취소, host death 일괄 transport 실패, 기본 영속 session, terminal만 검사하는 session gate는 이 계약에 맞춰 수정해야 한다. [Testing](testing.md)의 정상·경쟁·종료 미확정 시나리오와 함께 구현하고 KDoc도 같은 단계에서 갱신한다.
