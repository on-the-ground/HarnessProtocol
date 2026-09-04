# 공개 모델과 리뷰 반영

기준일: 2026-09-04. [설계 선언](../AHP_CHARTER.md) → [Semantic contract](semantic-contract.md) → 상세 계약을 따른다. 선언은 harness-protocol의 dev.harnessprotocol에, 적합성 fixture는 harness-conformance에 있다.

기존 adapter는 dev.harnessprotocol.legacy를 사용한다. 이번 결과는 공개 모델·KDoc·fixture의 수정이며 세 adapter 구현 완료가 아니다. 기존 타입 제거와 adapter 전환은 [후속 단계](port-revision-plan.md#2-공개-port와-kdoc-전환)에서 수행한다.

## 공개 형태

| 영역 | 결정과 의미 |
|---|---|
| 요구와 선택 연산 | 요구는 SessionRequirements·TaskRequirements로 직접 전달한다. 영속 재개 연산은 PersistentSessions, 진단 관찰은 TaskDiagnostics로 분리한다. 조회 없이 요구할 수 있다. |
| 구성 | SessionSpec은 지속 설정·session 요구, TaskRequest는 입력·작업 요구와 provider별 reasoning effort 선택이다. `null` effort는 session/provider 기본값을 유지하며 adapter는 지정된 값을 보존하거나 작업 시작 전에 거절한다. ExecutionConstraint의 filesystem과 network는 서로 독립적으로 요구하며 둘 다 생략하려면 ProviderDefault를 사용한다. |
| 종결과 산출물 | awaitOutcome은 sealed TaskOutcome을 반환한다. 네 outcome 모두 nullable output·usage·sessionUsage를 제공한다. Completed에서도 산출물이 없을 수 있으며 null을 빈 텍스트로 합성하지 않는다. |
| 조건별 검증 | CompatibilityReport.status는 COMPATIBLE·INCOMPATIBLE·UNCONFIRMED다. 진단 kind는 ADVISORY·UNSUPPORTED·UNCONFIRMED이며 확인된 미지원이 있으면 거절하되 나머지 미확인 이유도 보존한다. |
| 검증 fixture | 구성 profile과 구체적인 요구 사례를 제공한다. 명령은 native 경계에 사실을 유도하고, 입력·문맥·효과는 실제 경계에서 독립 관찰한다. public state/outcome을 직접 설정해서 통과시키지 않는다. |

## 전달과 개입

| 상황 | 공개 표현 / 보장 |
|---|---|
| 요구 이행 불가 | IncompatibleRequirementException. 작업 수락 전에 거절한다. |
| 요구 이행 여부 미확인 | RequirementUnconfirmedException. 확정적인 미지원과 구별한다. validate의 미확인을 실제 경계에서 추가 확인해 수락하는 것은 가능하다. |
| 시작 수락 확인 유실 | TaskStartUnconfirmedException(UnconfirmedStart). session과 요청 identity를 보존하고 같은 문맥의 중복 시작을 막는다. |
| 응답 수락 확인 유실 | InteractionResponseUnconfirmedException(UnconfirmedResponse). TaskId·InteractionId를 보존한다. pending에서 제외하고 RESPONSE_UNCONFIRMED로 정리하며 같은 요청에 다시 응답하지 않는다. provider의 거절·수락이나 Task 종결을 합성하지 않는다. |
| 확정적인 응답 미전달 | HarnessTransportException. 수락 미확정과 같은 실패로 표시하지 않는다. |
| 문맥 차단 | SessionBlockedException. 기본 조정은 같은 harness의 동일 문맥이며 영속성은 선언한 재개·조정 범위에서 차단을 유지하거나 복구를 입증한다. release·독립된 새 session과 기존 문맥 재사용을 구별한다. |

응답 성공은 전달·수락 확인이며, 모든 interaction 해결이나 Task 재개 완료까지 뜻하지 않는다. coroutine 취소도 미전달 증거가 아니므로 adapter는 caller의 기다림과 독립적으로 불확실한 응답의 재전송을 막아야 한다.

## 세션 승인 결정

사용자가 세션 승인을 유지하되 허용 범위를 명시하는 방안을 선택했다. APPROVE_FOR_SESSION을 제공할 때는 SessionApprovalGrant가 필수다.

- ApprovalScopeId는 현재 논리 session 안의 같은 대상·조건만 가리킨다. 같은 ID를 더 넓은 권한으로 재해석하지 않는다.
- description은 반복 허용할 대상·조건을 caller가 판단할 수 있게 설명한다. provider 원본 해석을 전제하지 않는다.
- 적용 기간은 현재 논리 session의 종료 또는 grant 철회까지다. 범위 밖 행위는 별도 승인이 필요하며 다른 session으로 자동 확장하지 않는다.
- Adapter가 범위를 설명·집행할 수 없으면 지속 승인 선택지를 제공하지 않는다. 일회 승인·거절은 유지할 수 있다.
- 영속 문맥 재개만으로 승인 보관까지 지원한다고 주장하지 않는다.

임의의 범용 권한 DSL을 도입하지 않았다. scope identity·명시적 설명·적용 기간과 실제 집행을 계약으로 삼고, fixture의 대상 집합에 속하는 효과와 범위 밖 효과를 각각 검사한다.

## 결과·관찰의 구체화

| 영역 | 결정 |
|---|---|
| 종료 미확정 | UnresolvedReason 네 종류와 known 설명으로 확인한 사실을 보존한다. 충분한 종결 근거를 미확정으로 낮추지 않는다. |
| 부분 산출물 | TaskOutput.complete와 Structured.validation을 보존한다. Completed를 포함해 output=null은 확보한 산출물이 없다는 뜻이며 실제 빈 텍스트와 다르다. |
| 사용량 | plus는 겹치지 않는 구간의 합이고 한쪽이 unknown이면 합도 unknown이다. Zero는 실제 0을 아는 baseline에만 쓴다. minus는 unknown·counter reset을 null로 보존한다. 누적 snapshot은 교체하며 plus로 누적하지 않는다. |
| 메시지 | MessageId와 MessageRole로 delta·완료·역할을 구별한다. 공개 설명은 EXPLANATION, 불명은 UNKNOWN이다. |
| 진단 | TaskDiagnostics.diagnostics의 DiagnosticEvent(ProviderDiagnostic·DiagnosticGap)를 사용한다. TaskEvent에는 원본 진단을 넣지 않으며 buffering·유실도 분리한다. |
| 정리 | cleanupBudget의 perTask·total·자원 간 합산 여부를 공개한다. 유예 만료를 취소 성공으로 바꾸지 않는다. |
| 명명 | Task 전체의 타입·종결 이벤트만 Task*를 사용한다. 반복 한도는 ITERATION_LIMIT이며 내부 turn을 보편 단위로 만들지 않는다. |

ContextManaged와 ReasoningDelta를 독립적인 기본 이벤트로 되살리지 않았다.

## 독립 검증 경계

HarnessFixture.profiles()는 고정한 runtime 구성별 expectedSupport와 RequirementCase를 제공한다. 단순 supported() 집합으로 조건부·미확인 지원을 축소하지 않는다. 기대값을 adapter.support나 validate에서 복사하는 것도 금지한다.

각 case에는 SessionSpec·TaskRequest, 사전 검증과 실제 create/start의 기대 판정이 있다. 전체 profile에는 기본 작업을 수락하는 사례가 있어야 하며, 조건부 지원의 수락·거절·미확인을 해당 구체적 사례로 검사한다.

| 시나리오 | fixture 수단 |
|---|---|
| 문맥 연속성·지시·skill | observedInput / observedInstructions / observedActivatedSkills / observedContextContains. 실제 runtime 경계에서 관찰하며 예정 답변이나 spec으로 역산하지 않는다. |
| 시작·응답 확인 유실 | StartControl·ResponseControl로 실제 수락 여부를 통제하고 native 제출·수락 횟수를 관찰한다. |
| 세션 권한 범위 | PermissionScenario의 실제 coveredTargets를 준비한다. 승인 후 범위 안·밖 효과를 시도하고 observedEffects로 판정한다. |
| 사용량 | reportUsageSnapshot과 reportUsageDelta의 의미를 고정한다. null이 patch 생략인지 unknown인지 구현별로 바뀌지 않는다. |
| 부분·구조화 산출물 | reportOutput(OutputObservation)으로 종료 전 사실을 제공하고 네 outcome으로 종결한다. 구조화 원문과 native 검증 여부를 구별한다. |
| 저장·재개 | SessionControl로 실제 저장·재개 실패와 정규화 참조를 유도한다. |
| 진단 격리 | reportDiagnostic으로 진단을 넘치게 해도 기본 의미 이벤트·outcome이 영향을 받지 않는지 검사한다. |
| 실행·재시작 | RuntimeControl은 실제 귀속 범위·지원 철회·process 경계를 제어한다. 단순 harness 재생성을 process 재시작 통과로 세지 않는다. |

검증자는 공개 Port·fixture·계약 문서를 사용한다. 구현자는 같은 명령을 자신의 native 경계에 연결한다. 의미가 모호하면 검사를 구현에 맞춰 낮추지 않고 계약을 함께 고친다.

## 기존 실험과 남은 실행 검증

패키지 이동으로 깨진 Koog 실험은 당시 protocol revision d5733031a2533dfd0d56821d912442a08552b0fd의 소스를 별도 build 디렉터리에 추출하도록 고쳤다. legacy 제거 후에도 현재 Port에 연결되지 않는다. 실험 Kotlin 소스와 기존 verification.json은 소급 수정하지 않았다. 준비 조건과 새 재현 기록은 [실험 안내](../experiments/koog-validation/README.md)에 있다.

공개 값 타입의 회귀 검사는 harness-protocol의 PublicModelTest다. harness-conformance의 세 adapter 공통 시나리오와 각 fixture 구현은 아직 작성·실행 전이다. 그 전체 검증을 값 타입 검사나 기존 legacy suite 통과로 대체하지 않는다.

## 이번 검증 결과

Corretto 25.0.3에서 캐시된 의존성을 사용하되 test 결과는 재사용하지 않고 실행했다.

```powershell
./gradlew.bat :harness-conformance:compileKotlin test --offline --rerun-tasks --console=plain
./gradlew.bat -p experiments/koog-validation test --offline --rerun-tasks --console=plain
```

| 검사 | 실행 수 | 실패 / 오류 / skip |
|---|---:|---|
| 기존 Kotlin adapter·bridge·bundle suite | 71 | 0 / 0 / 0 |
| PublicModelTest | 9 | 0 / 0 / 0 |
| 고정한 과거 Port의 Koog 실험 | 18 | 0 / 0 / 0 |

새 fixture 선언도 컴파일됐다. JUnit XML에서 실제 실행 수와 시각을 확인했다. Python/Node host suite와 실모델 호출은 이번 수정에서 재실행하지 않았다. 기존 adapter·host·legacy 동작 코드는 변경하지 않았다.
