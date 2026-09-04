# Event contract

이 문서는 공개 `TaskEvent`와 상태·산출물·진단의 관계를 정의한다. 세 adapter는 이 이벤트·진단 타입을 사용하며 실행 검증 범위는 [native 검증 기록](native-port-validation.md)을 따른다. [Protocol reference](protocol-reference.md)와 [Lifecycle](lifecycle-and-concurrency.md)이 종결·개입의 의미를 정한다.

## 관찰과 실행의 독립성

- 작업 진행, pending interaction, state와 outcome은 event collector에 의존하지 않는다.
- 이벤트는 실시간 관찰 통로이며 영속 업무 이력이나 재개 저장소가 아니다.
- 의미를 관찰한 경우 번역하되 관찰하지 않은 진행·효과·사용량을 만들어내지 않는다.
- 기본 업무 의미와 공급자 진단을 분리한다. 원본 payload 보존은 선택적인 `ProviderDiagnostic`의 책임이다.

## 전달과 순서

작업마다 소유 harness 안에서 구별되는 `TaskId`로 이벤트를 격리한다. WorkId와 InteractionId는 Task 범위의 식별자이며, 서로 다른 Task의 같은 하위 ID를 혼동하지 않는다. 순서는 adapter가 관찰해 확정한 순서이며 서로 다른 process·모델·도구의 전역 인과관계를 추가로 보장하지 않는다.

각 observer의 buffering은 bounded하고 한 observer의 지연이 다른 observer나 lifecycle을 막지 않는다. 살아 있는 구독에서 유실이 생기면 `ObservationGap`으로 알린다. 전송 단절과 consumer queue 유실을 같은 사실로 취급하지 않는다.

한 handle에는 정확히 하나의 terminal 의미 이벤트를 확정하고 마지막에 전달한다. 진행 이벤트 overflow 때문에 terminal을 버리지 않는다. terminal 뒤 도착한 provider 알림으로 새 작업 이벤트나 다른 outcome을 만들지 않는다. 늦은 구독의 과거 이벤트 replay 범위는 기본 보장이 아니며 state·pending·outcome으로 현재 사실을 회수한다.

구독 시작 시점이나 flow 완료 여부가 결과 판정의 기준은 아니다. collector별 snapshot과 event 수신 사이에 전역 원자성을 가정하지 않는다.

## 메시지와 산출물

`MessageDelta`와 `MessageCompleted`는 소비자가 진행 중 메시지를 표시하고 같은 메시지의 완료 snapshot으로 표시를 확정하는 계약이다. 작업의 `TaskOutput`과 같은 개념으로 취급하지 않는다. 여러 설명과 중간 메시지를 보낸 작업도 하나의 종결 판정과 산출물을 갖는다.

- 메시지 identity와 관찰 가능한 역할·phase를 보존한다. 역할이 불명확하면 최종 답변으로 꾸미지 않는다.
- delta와 완료 snapshot이 같은 내용을 담으면 두 번 이어 붙이지 않는다.
- 완료된 최종 메시지 이후 commentary가 왔다고 산출물을 commentary로 덮어쓰지 않는다.
- 이벤트 유실로 소비자가 조립한 텍스트가 불완전해도 adapter가 전달할 canonical 산출물은 별도로 관리한다.
- 확보한 업무 산출물은 [모든 outcome의 회수 계약](protocol-reference.md#산출물과-부가-관찰)을 따른다. 부분 산출물의 존재가 `Completed`나 업무 성공의 근거는 아니다.

Provider가 호출자에게 공개하는 설명·요약은 메시지 표시 목적에 포함한다. `MessageId`와 `MessageRole`의 ANSWER·COMMENTARY·EXPLANATION·UNKNOWN으로 관찰 가능한 의미를 보존한다. `ReasoningDelta`를 별도의 기본 이벤트로 유지하지 않으며 설명을 최종 산출물로 오인하거나 비공개 추론을 합성하지 않는다. 내부 추론 진단은 선택적인 ProviderDiagnostic에 속한다.

## 도구와 외부 효과

`ToolCallChanged`는 이름 있는 도구 수행을, `EffectChanged`는 관찰한 외부 효과 또는 명시된 효과 시도를 나타낸다. 하나의 실제 작업이 두 이벤트에 대응할 수 있고 동일 `WorkId`로 관계를 나타낼 수 있다. `TaskId`와 하위 작업 ID를 혼동하지 않는다.

| 하위 작업 상태 | 의미 |
|---|---|
| `STARTED` | 도구 수행이나 효과 시도가 시작됐다. 변경 완료를 뜻하지 않는다. |
| `UPDATED` | 실제 추가 진행·출력을 관찰했다. |
| `COMPLETED` | 해당 작업의 완료를 확인했다. |
| `FAILED` | 해당 작업의 실패를 확인했다. |
| `DECLINED` | 승인·정책 거절로 대상 행위를 수행하지 않았다. |
| `CANCELLED` | 해당 작업의 취소에 따른 종료를 확인했다. |

부모 Task의 취소 요청이나 `Unresolved`를 근거로 하위 작업을 전부 CANCELLED로 바꾸지 않는다. 승인 대기 중인 효과 시도에 STARTED가 있어도 실제 변경이 이뤄졌다는 뜻은 아니다. 승인이 요구된 행위의 실제 효과는 승인 전에 발생하지 않아야 한다.

Provider가 완료 snapshot만 제공하면 시작 이벤트를 합성할 필요는 없다. 관찰 상실이나 Task 종결 때문에 하위 작업의 terminal 이벤트가 없을 수도 있다. Task의 terminal과 모든 하위 효과의 완료를 동일시하지 않는다.

동일 작업의 상관관계는 Task 범위의 native call ID, runtime 호출 객체의 identity, 또는 adapter가 해당 호출의 시작부터 결과까지 유지하는 일관된 대응으로 입증한다. 그 대응이 있으면 같은 WorkId를 사용하고, 없으면 충돌하지 않는 ID로 구별하며 관계를 미확정으로 남긴다. 이름·인자·시간 근접성만으로 두 작업을 합치지 않는다. tool arguments/result의 vendor별 schema는 업무 산출물의 공통 schema가 아니다.

효과가 존재한다는 근거는 있으나 기존 분류에 없으면 `OTHER`로 표현할 수 있다. 모르는 도구 이름만 보고 효과가 있었다고 추정하지 않는다. 내부 context 관리 자체는 선택 진단으로 다루며 외부 효과 분류와 중복시키지 않는다.

## Interaction 이벤트와 snapshot

`InteractionRequested`는 외부 판단·정보 요청이 열렸음을, `InteractionResolved`는 응답되거나 응답 없이 정리됐음을 알린다. Approval과 Question 모두 같은 lifecycle을 사용하되 응답 타입은 구별한다.

현재 답해야 할 요청은 `pendingInteractions` snapshot으로 확인한다. 놓친 요청 이벤트를 기다리느라 응답이 불가능해져서는 안 된다. `respond`는 요청 ID·종류·허용 값과 응답의 일회 처리를 검증한다.

응답 없이 정리할 때 취소 요청, 작업 종결, provider 철회, supersede 등 실제 원인을 전달한다. `CANCELLATION_REQUESTED`는 실제 취소 완료와 다르다. `TASK_ENDED`는 handle의 종결에 따른 정리이며 미확정 작업의 물리적 종료를 주장하지 않는다. `RESPONSE_UNCONFIRMED`는 수락 여부를 모르기 때문에 재응답 대상에서 제외한 것으로 provider의 거절을 뜻하지 않는다.

종결 시 snapshot 정리와 늦은 응답 거절은 [Lifecycle](lifecycle-and-concurrency.md#상태와-outcome), 수락 확인 유실은 [응답 수락 계약](protocol-reference.md#interaction)을 따른다. 정리 의미 이벤트는 terminal 앞에 놓는다. 느린 observer가 이 이벤트를 놓쳐도 snapshot과 응답 거절 규칙은 유지된다.

## Context, usage, 경고

기존 `ContextManaged`가 표현한 내부 문맥 정리 관찰은 ProviderDiagnostic으로 이동한다. 현재 근거로는 별도 업무 판단에 사용할 공통 보장을 식별하지 못했으므로 기본 이벤트에서 제거한다. 문맥 상한 집행·영속 저장 등의 결과가 필요하면 해당 선택 계약에서 의미를 정의하고 검증해야 한다. 내부 compaction 알림을 그 보장의 증거로 사용하지 않는다.

`UsageChanged`는 **그 시점까지의 Task 누적 snapshot**이다. 소비자는 이전 snapshot에 더하지 않고 새 값으로 갱신한다. provider의 증분은 adapter에서 누적하고, 이미 누적인 값은 반복 합산하지 않는다. Session 누적은 실제 관찰할 수 있을 때 별도 범위로 전달한다. 이전 Task의 사용량을 현재 Task에 섞지 않는다.

일부 필드만 알려지면 알려진 필드만 제공한다. unknown을 0으로 만들거나 provider가 제공하지 않은 비용을 계산한 사실처럼 보고하지 않는다. provider 통계 reset·누락으로 Task 누적을 확정할 수 없으면 그 불확실성을 보존한다. outcome의 사용량은 같은 측정 범위의 최종 관찰과 일치해야 한다. AgentUsage.plus는 겹치지 않는 구간의 합이며 한쪽이 unknown이면 합도 unknown이다. 누적 snapshot은 교체하고, 실제 0을 아는 baseline에만 AgentUsage.Zero를 사용한다. 필수 측정을 요구한 선택 계약이 있다면 unknown으로 대체해 그 계약을 통과하지 않는다.

경고는 유효한 요구가 유지되는 가운데 전달할 부가 정보다. 지원 불가, 필수 의미 손실, 확인된 실패를 warning으로 낮추지 않는다.

## Terminal과 진단

`TaskCompleted`, `TaskFailed`, `TaskCancelled` 또는 종료 미확정에 대응하는 terminal 이벤트는 `awaitOutcome`과 같은 판정을 나타낸다. 종료 미확정 이벤트의 목표 명칭은 `TaskUnresolved`다. `COMPLETED`도 자연 종료·한도 중단 등의 사유를 보존한다.

`ProviderDiagnostic`은 별도의 선택 관찰 경로다. 원본 SDK 객체 전체를 JSON으로 바꾸거나 모든 vendor 알림을 보존하는 것은 기본 적합성 조건이 아니다. 지원한 진단의 범위·buffering·민감정보 처리·업무 이벤트와의 상관관계는 해당 진단 계약에서 명시한다.

공개 선언은 `TaskDiagnostics.diagnostics: Flow<DiagnosticEvent>`다. `ProviderDiagnostic`과 진단 유실의 `DiagnosticGap`은 TaskEvent에 속하지 않는다. 진단 요구를 수락한 Task는 이 선택 interface를 제공하며 의미 이벤트와 queue·유실 정책을 분리한다. 진단 과다·observer 지연이 기본 이벤트·state·pending·outcome을 방해하지 않아야 한다.

알 수 없는 실패는 공통 메시지와 알려진 사실로 전달한다. 소비자에게 raw payload를 읽어야만 종결 판정을 해석할 수 있도록 요구하지 않는다.

실패 이벤트의 분류도 [종결 판정과 실패 분류](protocol-reference.md#상태와-종결-판정)를 따른다.

## 이벤트를 남기는 목적

이 표는 발생 빈도가 아니라 소비 목적을 기준으로 한 판정이다. 특정 작업에 도구나 interaction이 없어서 해당 이벤트가 발생하지 않는 것과, 추가 보장을 선택적으로 지원하는 것은 구별한다.

| 이벤트 | 소비 목적 / 배치 |
|---|---|
| MessageDelta / MessageCompleted | 진행 표시와 메시지 확정. 관찰 가능한 공개 설명도 역할·phase를 보존해 포함 |
| ToolCallChanged / EffectChanged | 수행 중인 기능과 관찰된 변경·시도를 구별하고 개입 대상과 연결 |
| InteractionRequested / InteractionResolved | 외부 판단·정보 요청의 표시와 정리. 현재 응답 대상은 snapshot으로 회수 |
| UsageChanged | 관찰한 소비량을 같은 Task 누적 기준으로 표시·판단. 측정 지원·예산 집행까지 암묵적으로 보장하지 않음 |
| ObservationGap | 진행 표시·집계의 누락을 인지하고 state·pending·outcome으로 필요한 사실 회수 |
| TaskStarted | 위임 작업의 진행 시작을 관찰. 대응하는 outcome은 없으며 종결 판정과 혼동하지 않음 |
| TaskCompleted / TaskFailed / TaskCancelled / TaskUnresolved | 위임 작업의 종결을 관찰. `awaitOutcome`이 회수하는 판정과 같은 의미 |
| Warning | 요구가 그대로 유지되는 상태에서 호출자·운영자가 구성이나 사용 방식을 고칠 수 있는 사실을 전달. 예로 해당 정책에서는 오지 않아야 할 승인 요청이 도착해 거절한 경우가 있다. 실패·미지원의 대체 경로가 아님 |
| 기존 ContextManaged | 내부 관리 관찰이므로 선택 진단으로 이동 |
| 기존 ReasoningDelta | 공개 설명의 메시지 목적에 통합. 독립적인 기본 이벤트 종류 제거 |

## 전환·검증

새 adapter는 `TaskEvent`와 별도 진단 경로를 사용하며 실제 native 검사에서 두 observer의 독립 종료를 확인했다. 기존 message 조립·usage·work ID 검사는 현재 mapper와 production task runtime으로 이전했다. 느린 observer overflow, 사용량 reset·누락, 하위 작업 상관관계 등 남은 조건은 [공통 검사 기준](testing.md)에 따라 새 Port에서 검증한다.
