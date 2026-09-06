# 공통 시나리오와 역사적 목록의 종결

현재 검사는 기능별로 가장 작은 실제 경계에 연결한다. G01 요구 판정부터 G12 저장 장애까지의 적용 범위는 [계약 검증](contract-boundary-validation.md)을 따른다. 실행 수는 concrete consumer의 JUnit 결과만 센다.

초기에 작성한 `HarnessConformanceCoreTest` 29개와 `HarnessConformanceCleanupTest` 19개는 구현자가 모든 native 사실을 임의로 일으킬 수 있다는 하나의 거대한 `HarnessFixture`를 전제로 했다. C20/C21과 K13/K14는 앞 단계에서 먼저 실제 요구·수락 검사로 대체했다. 남아 있던 44개 본문도 실제 검사로 옮기거나, 현재 구성의 정직한 기능 거절로 닫거나, Port가 아닌 fixture 가정을 폐기한 뒤 제거했다. 삭제한 본문을 통과 수로 환산하지 않는다.

[기계 판독 목록](../protocol/conformance/scenario-catalog.json)은 원래 C01–C29·K01–K19의 48개 identity와 각 `status`, `replacementEvidence`, `disposition`을 보존한다. 과거 source는 `historicalSource`이며 현재 실행 파일이 아니다.

## 현재 검사 배치

| 경계 | 공통 판정 | 실제 적용 |
|---|---|---|
| 요구 수락 | `HarnessRequirementsConformanceTest` | Codex·Gemini·Koog의 5개 고정 profile, 55개 case, preflight/direct 110회와 support 5회 |
| 작업 수명 | `HarnessLifecycleConformanceTest`, `HarnessRuntimeConformanceTest` | process SDK 경계와 세 native runtime |
| 시작·응답 수락 | `HarnessAcceptedStartConformanceTest`·`HarnessStartAcceptanceConformanceTest`·`HarnessResponseAcceptanceConformanceTest` | Codex·Gemini 시작 경계, Codex 승인 응답, Koog의 동기 handoff |
| 상호작용 | `HarnessInteractionConformanceTest`와 Codex interaction 회귀 | 실제 승인·효과·응답 경쟁, 완료/실패/취소 시 pending 정리 |
| 문맥·영속성 | Context·Persistence suite | Codex·Gemini의 별칭·차단·저장 실패·재개 설정 |
| 산출물·회계 | Outcome·Accounting suite | 세 runtime의 네 outcome, 부분 산출물, unknown/zero, message/work identity |
| 정리·관찰 | CleanupBudget·ObservationLoad suite | 세 runtime의 total budget, 비협조 Koog 도구, 의미/진단 queue 격리 |
| 작업 환경 | Workspace·ExecutionConstraint suite | Codex·Gemini skill 적용, Codex hard upper bound, 나머지 미지원 조합의 사전 거절 |

provider 설정·SDK JSON·모델 서버·실제 파일 효과는 consumer binding이 맡는다. 공통 판정은 provider wire를 알지 않으며 `AgentTask`의 state나 outcome을 직접 덮어쓰지 않는다. 하나의 만능 fault-injection fixture 대신 `RuntimeRequirementsFixture`, `AcceptanceFixture`, `LifecycleFixture`, `WorkspaceFixture`처럼 증명할 경계에 맞는 작은 seam만 둔다.

## 원래 C/K 목적의 처리

아래 표의 “대체”는 원래 본문을 실행했다는 뜻이 아니다. 같은 공개 목적을 더 실제적인 검사로 다시 작성해 실행했다는 뜻이다.

| 원래 ID | 처리 | 근거 |
|---|---|---|
| C01–C05 | Task 수명·terminal·실패 분류 검사로 대체 | R01, S02–S03, S06–S07, G05, G11 |
| C06–C07 | Codex 실제 interaction이 열린 상태의 완료·실패·취소 정리로 대체 | G03, S21, S22 |
| C08–C15 | observer 독립성·overflow·세션 배타성·취소 경쟁으로 대체 | G03, G04, G07, S04–S05, S08–S10, S14–S17, R01–R07 |
| C16–C18 | 무산출물·빈 텍스트·선행 부분 산출물 보존으로 대체 | G05 |
| C19 | 현재 세 구성은 구조화 산출물 요구를 작업 전에 거절 | G01, G10 |
| C20 | 구체적 incompatible case의 실제 사전 거절로 대체 | G01 |
| C21 | 세 구성에는 정직하게 `UNCONFIRMED`인 요구 사례가 없음. 공개 분기는 유지하고 실제 start/response 확인 유실은 별도 검증 | G01, G02 |
| C22 | 요청 전 거절과 handle 이후 실패를 각 실제 경계에서 구별 | G01, G05, R03 |
| C23 | private fixture의 effect counter 단언을 폐기. 공개 TaskId/WorkId 상관관계와 실제 효과 격리로 대체 | G08, G11 |
| C24 | 공백만 있는 `TaskInput`이 세 native model 경계에 그대로 도달 | R09 |
| C25 | process envelope의 null/빈 지시와 Koog 실제 system prompt를 각각 검증 | S01, R10 |
| C26 | 활성 skill 본문 적용, 비활성 skill의 제공 상태, 잘못된 artifact 사전 거절로 대체 | G09 |
| C27–C29 | Failed·Cancelled·Unresolved의 부분 산출물과 사용량 보존으로 대체 | G05, G11 |
| K01–K02, K04–K08 | per-task/total cleanup, 다중 자원, 비협조 효과, 문맥 차단으로 대체 | G04, G06, G12, S11–S12, S16, S18–S19, R05 |
| K03 | “예약한 완료가 cleanup 취소를 이긴다”는 보장을 폐기. 실제로 먼저 확인된 terminal fact가 이기고 이후 outcome은 불변 | G03, S09 |
| K09–K10 | 여러 내부 호출과 관찰 상실을 Task 종결과 구별 | G05, G11, S11–S12 |
| K11–K12 | `ownsRunningWork` boolean을 terminal 증거로 삼는 fixture 가정을 폐기. 확인된 native 실패는 Failed, 종결 증거 없는 stream 상실은 Unresolved | G05, S06, S11–S12 |
| K13–K14 | 요청 전 미전달과 수락 acknowledgement 유실을 실제 제출·수락 identity로 대체 | G02 |
| K15–K17 | 누적 snapshot·구간 delta·unknown·Task/Session 사용량을 실제 provider 측정으로 대체 | G11 |
| K18 | terminal 뒤 늦은 알림·응답·효과가 outcome을 바꾸지 않음을 대체 검증 | G03, G06, G07, S10 |
| K19 | host가 반환한 canonical ID와 실제 persistent reopen을 검증 | G12, S20, R08 |

## 폐기한 가정

- `baseline`, `approval`, `questions`라는 profile 이름과 `cases.first()`를 공통 계약으로 강제하지 않는다.
- 늦은 구독의 첫 이벤트를 terminal로 고정하지 않는다. terminal 보존과 유한 종료를 검사한다.
- 정리 상한을 자원마다 새로 주지 않는다. 진입부터 total budget을 측정한다.
- process 소유 여부나 stream EOF만으로 Failed·Cancelled를 합성하지 않는다.
- 예상 답변, spec 복사본, private counter를 실제 context·효과 증거로 사용하지 않는다.
- 지원하지 않는 질문·구조화 산출물·지속 승인을 가짜 fixture로 성공시키지 않는다.

이 정리는 원래 아이디어를 버린 작업이 아니다. 공개 목적은 실제 경계의 검사로 옮겼고, 특정 구현 제어법만을 요구하던 부분만 제거했다. 외부 실모델 호출과 현재 구성에서 제공하지 않는 선택 기능의 성공 경로는 별도 범위다.

---

**15개 파일 / 19개 추상 클래스 / 57 `@Test` + 1 `@TestFactory`**입니다.

구조 — 두 층으로 나뉘어 있습니다. `src/main`의 fixture 인터페이스는 **adapter가 구현해야 할 seam**(실제 runtime을 붙잡고 관찰하는 수단)이고, `src/testFixtures`가 **그 seam을 구동하는 시나리오**입니다. 그리고 거의 모든 시나리오가 `observation.hold()/release()`로 **실제 모델 경계**를 제어합니다 — `HarnessLifecycleConformanceTest`만 유일하게 `TaskLifecycleControl`로 합성 증거를 주입합니다.

---

## 시작·응답 수락 — `HarnessAcceptanceConformanceTest.kt` (3 클래스, 7개)

| 시나리오 | 목적 |
|---|---|
| `accepted start yields a usable identity while real work is held` | 수락 직후 handle이 유효(sessionId 일치·non-terminal)하고 입력이 실제 모델에 도달함 |
| `confirmed nondelivery permits retry without a native start` | **확정** 미전달이면 native 시작이 없었음(submissions 1 / accepted 0)이고 세션이 깨끗해 재시도 가능 |
| `lost acknowledgement after native acceptance blocks resubmission` | 수락됐지만 ack 유실 → 차단 |
| `lost acknowledgement without native acceptance still blocks resubmission` | 수락 안 됐어도 ack 유실 → **동일하게** 차단. caller가 둘을 구별할 수 없기 때문 |
| `confirmed response nondelivery leaves the native request retryable` | 응답 확정 미전달이면 pending 유지·effect 0·재응답 가능 |
| `response acceptance loss after native acceptance prevents duplicate effects` | ack 유실 시 pending 제거 + `RESPONSE_UNCONFIRMED` 정리 + 재응답 거절로 이중 effect 방지 |
| `response acceptance loss before native acceptance prevents duplicate submission` | 같은 처리를 하되 **Task는 종결이 아님** (응답 유실 ≠ 취소) |

차단이 그 문맥에만 걸리고 다른 session은 정상 진행한다는 것, `UnconfirmedStart.requestId`가 실제 제출된 요청과 일치한다는 것도 함께 고정합니다.

## 사용량 회계 — `HarnessAccounting*.kt` (2 파일, 3개)

| 시나리오 | 목적 |
|---|---|
| `task accounting resets across tasks including measured zero and retains the last snapshot` | Task별 usage 독립, **측정된 0과 미측정 구별**, `outcome.usage` == 마지막 `UsageChanged` |
| `cancellation before any measurement preserves unknown instead of zero` | 측정 전 취소는 unknown 유지, 0으로 합성 금지 |
| `unmeasured inner segment keeps the task total unknown through subsequent measured zero` | 미측정 구간이 끼면 이후 측정돼도 합계는 unknown(17/null/null). 두 내부 도구 호출이 별도 WorkId로 중복·유실 없이 관찰 |

## 승인 범위 — `HarnessApprovalScopeConformanceTest.kt` (3개)

| 시나리오 | 목적 |
|---|---|
| `opaque native session approval is not exposed as an unbounded grant` | 집행 가능한 범위를 만들 수 없으면 `sessionGrant=null`·`APPROVE_FOR_SESSION` 미제공·시도 시 전달 전 거절. **거절이 Task 실패를 뜻하지 않음** |
| `one shot approval does not authorize the next task on the same context` | 일회 승인이 다음 Task로 안 샘 |
| `one shot approval does not leak into another session` | 일회 승인이 다른 session으로 안 샘 |

## 정리 예산 — `HarnessCleanupBudgetConformanceTest.kt` (3개)

| 시나리오 | 목적 |
|---|---|
| `close settles several active native sessions within one advertised total budget` | 자원 4개라고 예산이 합산되지 않음. 공개한 `cleanupBudget.total` 안에서 종료 + 전부 terminal·pending 비움·terminal 1회·이후 시작 차단 |
| `cancelling the release caller still settles its native work and closes the handle` | `release()` 호출자의 coroutine을 취소해도 정리는 끝까지 진행되고 판정이 남음 |
| `cleanup preserves already completed outcomes alongside active work` | 이미 확정된 결과를 정리가 덮어쓰지 않음 |

## 문맥 차단·격리 — `HarnessContextConformanceTest.kt` (3개)

| 시나리오 | 목적 |
|---|---|
| `reopened aliases share active work exclusion and retain released handle boundaries` | 같은 문맥의 alias끼리 중첩 시작 배제를 공유(native 제출 없이 거절). release된 handle만 막히고 alias는 계속 사용 가능 |
| `unconfirmed start blocks every alias and recreation without blocking a fresh context` | 미확정 차단이 모든 alias와 **harness 재생성 후 reopen까지** 전파되되, 새 문맥은 막지 않음 |
| `release of one active context leaves another native context progressing` | 한 문맥 정리가 다른 문맥의 실제 진행·내용을 건드리지 않음 |

## 실행 제약 — `HarnessExecutionConstraintConformanceTest.kt` (3개)

실제 파일 쓰기와 loopback HTTP 관찰로 상한을 검사합니다 — "작업이 완료됐다"를 집행 증거로 쓰지 않습니다.

| 시나리오 | 목적 |
|---|---|
| `read only execution with deny all exposes no actual write` | 실제 쓰기 0 |
| `workspace and denied network upper bounds are not exceeded by actual effects` | 상한 밖 쓰기 없음 + 네트워크 요청 0 |
| `allowing network does not widen the required filesystem boundary` | network ALLOWED가 파일 상한을 넓히지 않음 |

## Interaction 경쟁 — `HarnessInteractionConformanceTest.kt` (5개)

| 시나리오 | 목적 |
|---|---|
| `wrong response type and unavailable decision never reach native delivery` | 잘못된 종류·미제공 결정은 전달 **전** 거절 |
| `cancelled response caller retains the single submission gate` | 응답 호출자 취소가 이중 제출을 만들지 않음 |
| `cancellation clears pending before returning and prevents an unapproved effect` | 취소가 pending을 먼저 정리하고 미승인 effect를 막음 |
| `terminal while response is in flight stays immutable after late delivery` | 응답 비행 중 종결되면 늦은 전달이 판정을 못 바꿈 |
| `completed approval rejects late duplicate responses without repeating the effect` | 중복 응답 거절 + effect 반복 없음 |

## Lifecycle 최소 보장 — `HarnessLifecycleConformanceTest.kt` (9개)

유일하게 합성 증거(`TaskLifecycleControl`)로 구동합니다. SDK 경계의 기본기입니다.

`state is terminal before awaitOutcome returns` · `completes without an event collector` · `slow collector does not block lifecycle` · `failure is reported through state and awaitOutcome` · `completion wins the race against cancel` · `terminal is exactly once and last` · `different sessions execute concurrently` · `harness close without native termination evidence settles unresolved` · `overflow is explicit and terminal survives`

## 관찰 부하 — `HarnessObservationLoadConformanceTest.kt` (1개)

| 시나리오 | 목적 |
|---|---|
| `stalled semantic and diagnostic readers have independent counted gaps and retained terminal` | 의미·진단 두 채널이 **독립 큐**를 갖고, 유실이 정확히 한 번씩 계수됨(느린 구독자 합계 == 빠른 구독자 합계, 256 초과). terminal 1회·마지막 유지 |

## Outcome 산출물 — `HarnessOutcomeConformanceTest.kt` (6개)

| 시나리오 | 목적 |
|---|---|
| `completed / failed / cancelled / unresolved runtime preserves captured (partial) output` | **네 outcome 모두** 확보한 부분 산출물을 회수. COMPLETED가 아니면 `complete=false`. `usage`는 마지막 관찰 스냅샷과 일치. 재호출해도 같은 판정 |
| `response without content preserves native termination and output absence` | 산출물 없음을 `null`로 보존 |
| `empty model response preserves native output presence` | 실제 빈 문자열과 없음을 구별 |

## 영속 저장 실패 — `HarnessPersistenceFailureConformanceTest.kt` (3개)

| 시나리오 | 목적 |
|---|---|
| `changed configuration cannot silently replace a live handle's declared configuration` | live handle이 있는 동안 설정 변경 reopen 거절, 원래 설정이 계속 **실제로** 적용됨. release 후에는 지원하는 구성만 반영 |
| `unknown persistent reference is rejected without creating replacement context` | 모르는 참조가 조용한 새 문맥이 되지 않음 |
| `unavailable native history fails reopen and restoration recovers the original context` | 저장소 장애가 reopen 실패로 드러나고, 복구되면 원 문맥이 돌아옴 |

## 요구 수락 행렬 — `HarnessRequirementsConformanceTest.kt` (`@TestFactory`, 동적 생성)

profile마다 support 표 1개 + case마다 preflight/direct 2회를 동적으로 만듭니다. 고정하는 규율:

- **preflight가 모델을 호출하지 않을 것** (session·task 양쪽)
- **거절이 session을 오염시키지 않을 것** — 거절 직후 정상 작업이 성공해야 함
- 수락된 입력은 실제 runtime 문맥에 도달할 것
- persistence 요구가 수락되면 `PersistentSessions` + `persistentRef` 존재, diagnostics면 `TaskDiagnostics` 구현
- profile은 **모든 `Capability`를 명시 선언**해야 하고(Unknown 포함), 정상 작업 수락 사례가 반드시 있어야 함

## 실제 runtime 기본 — `HarnessRuntimeConformanceTest.kt` (3 클래스, 9개)

| 계층 | 시나리오 | 목적 |
|---|---|---|
| base | `native task completes without an observer...` | observer 없이 완료 + 입력·지시가 실제 모델 구성에 도달 |
| | `whitespace-only caller input reaches the native model without trimming` | 공백 입력 무보정 전달 |
| | `same session carries prior native context while a new session stays isolated` | 이전 입력·응답이 후속 모델 문맥에 실재, 새 session은 상속 안 함 |
| | `overlap is rejected and cancelling one waiter does not cancel native work` | 중첩 거절이 모델에 도달 안 함 + waiter 취소가 native를 안 죽임 |
| | `close bounds active native work and settles the waiter` | close가 선언 예산 안에서 bound |
| | `explicit cancellation waits for native termination and leaves the session reusable` | 명시 취소는 native 종료를 기다리고 session 재사용 가능 |
| Profile | `unsupported task requirements are rejected before a native model call` | 미지원 요구는 모델 호출 전 거절 |
| | `independent semantic and diagnostic observers finish and late subscription retains terminal` | 두 observer 독립 종료 + 늦은 구독도 terminal 회수 |
| Persistence | `persistent reopen preserves actual context and desired instructions across harness recreation` | harness 재생성을 넘어 실제 문맥과 desired instructions 보존 |

## 작업 공간·skill — `HarnessWorkspaceConformanceTest.kt` (2개)

| 시나리오 | 목적 |
|---|---|
| `configured workspace and activated skill body reach each actual native task` | `activate=true` skill **본문**이 매 Task 실제 문맥에 들어가고, 제공만 한 skill 본문은 안 들어감. workingDirectory가 문맥에 식별되고 사용자 입력은 변형 안 됨 |
| `a missing skill artifact is rejected before native work` | 읽을 수 없는 artifact는 native 작업 전 거절 |

---

전체를 관통하는 규율이 하나 있습니다 — **공개 Port의 값에서 역산해 통과시키지 않는다.** `ProfileFixture` KDoc이 "`AgentTask`의 state/outcome을 직접 조작해 통과시키면 부적합"이라고 못박고, 실제로 모든 관찰이 fixture가 독립 선언한 값(`measurements`, `activeSkillBody`, `writtenTargets()`, `observedContexts`)과 대조됩니다.
