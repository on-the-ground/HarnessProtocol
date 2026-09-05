# 공통 시나리오와 역사적 목록의 종결

현재 검사는 기능별로 가장 작은 실제 경계에 연결한다. 요구 판정은 [G01](requirement-admission-validation.md), 수락 확인 유실은 [G02](acceptance-loss-validation.md), 나머지 계약 경계는 [G03–G12](contract-boundary-validation.md)를 따른다. 실행 수는 concrete consumer의 JUnit 결과만 센다.

초기에 작성한 `HarnessConformanceCoreTest` 29개와 `HarnessConformanceCleanupTest` 19개는 구현자가 모든 native 사실을 임의로 일으킬 수 있다는 하나의 거대한 `HarnessFixture`를 전제로 했다. C20/C21과 K13/K14는 앞 단계에서 먼저 실제 요구·수락 검사로 대체했다. 남아 있던 44개 본문도 실제 검사로 옮기거나, 현재 구성의 정직한 기능 거절로 닫거나, Port가 아닌 fixture 가정을 폐기한 뒤 제거했다. 삭제한 본문을 통과 수로 환산하지 않는다.

[기계 판독 목록](../harness-conformance/scenario-catalog.json)은 원래 C01–C29·K01–K19의 48개 identity와 각 `status`, `replacementEvidence`, `disposition`을 보존한다. 과거 source는 `historicalSource`이며 현재 실행 파일이 아니다.

## 현재 검사 배치

| 경계 | 공통 판정 | 실제 적용 |
|---|---|---|
| 요구 수락 | `HarnessRequirementsConformanceTest` | Codex·Gemini·Koog의 5개 고정 profile, 49개 case, preflight/direct 98회와 support 5회 |
| 작업 수명 | `HarnessLifecycleConformanceTest`, `HarnessRuntimeConformanceTest` | process SDK 경계와 세 native runtime |
| 시작·응답 수락 | `HarnessAcceptanceConformanceTest` | Codex·Gemini 시작 경계, Codex 승인 응답, Koog의 동기 handoff |
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
