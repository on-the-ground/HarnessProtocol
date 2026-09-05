# Testing

## 실제 adapter 전환 현황

세 adapter의 Port 실행 검증은 `verification/native-integration`에서 수행한다. 실제 Codex App Server·Gemini SDK/core·Koog graph에 통제된 모델 경계를 연결하며 입력·지시·이력과 도구 효과를 확인한다. 결과와 한계는 [G01–G12 검증](contract-boundary-validation.md)에 기록한다.

임시 `ReferenceFixture`·참조 하네스·실행 subclass는 제거했다. `protocol/conformance/src/testFixtures`의 공통 판정은 실제 runtime binding과 SDK 경계 binding에서 실행한다. 요구 수락·확인 유실에 이어 상호작용·문맥·네 outcome·다중 정리·관찰 부하·승인 범위·사용량·저장 장애·작업 환경 검사를 G12까지 연결했다. [현재 단계별 증거와 한계](contract-boundary-validation.md), [전체 대응표](conformance-scenarios.md)를 따른다.

기존 Core/Cleanup의 마지막 미연결 본문 44개는 목적별 대체·현재 구성의 기능 거절·잘못된 fixture 가정을 각각 기록한 뒤 제거했다. 원래 48개 identity는 역사적 inventory로만 남기며 통과 수로 세지 않는다. 미지원 선택 기능의 정확한 사전 거절과 지원 기능의 의미 이행을 구별하고, 실행 수는 JUnit에서 집계한다.

검증 기준은 [Semantic contract](semantic-contract.md)와 [개정 계약](protocol-reference.md)이다. 기본 동작의 실행 주체는 실제 세 adapter이며 모델 경계만 통제한다. 별도로 adapter suite는 현재 구현의 SDK 변환·transport 회귀를, PublicModelTest는 값 타입을 검사한다. 아래 목록은 계약상 검사해야 할 범위로서 이미 실행한 항목과 미검증 항목을 모두 포함한다. 항목이 코드나 목록에 존재하는 것과 실제 검증을 통과한 것은 구별한다.

## 공통 검사와 구현별 검사

| 검사 | 공통 판정이 알아야 할 것 | 구현별 fixture의 책임 |
|---|---|---|
| Port 적합성 | AgentHarness, AgentSession, AgentTask, 요구·interaction·outcome·output | provider 구성, 통제된 진행·실패·취소 유도, 합성 업무 효과 관찰 |
| 선택 계약 | 선언한 지원, 의미 이행, 정확한 사전 거절 | 영속 저장소, 승인·질문 경로, 정책 집행, schema 검증 등 |
| SDK 변환 | 해당 adapter의 의도 투영·event 매핑 | provider JSON, SDK 모델, 원본 이벤트 |
| Transport | 해당 process adapter의 전달·EOF·중복·정리 | RecordingBridge, 실제 host process, stub server |
| 실제 연동 | 실제 provider에서 관찰한 계약 범위 | 고정한 SDK/runtime/model, 인증·설정·호출 한도 |

공통 suite의 factory에 SdkBridge나 RecordingBridge를 요구하지 않는다. 기존 의도 투영 검사는 독립적인 선언으로 유지하고 adapter의 변환 코드 자체를 호출해 기대값을 만들지 않는다. Koog는 직접 연결하는 fixture로 같은 업무 판정을 검증한다.

요구 검사의 seam은 `ProfileFixture.profiles()`가 제공하는 고정된 구성·요구 사례다. expectedSupport·사전 검증·실제 수락의 기대값은 adapter의 조회 결과와 독립적으로 작성한다. 전체 profile에는 정상 기본 작업을 수락하는 사례가 있어야 한다. 조건부·미확인 지원을 `Set<Capability>`로 줄이지 않고 구체적인 수락·거절 사례로 검사한다. 다른 검사는 증명할 사실에 맞는 작은 fixture를 사용한다. [공개 모델](public-model.md#독립-검증-경계)의 제어·관찰 수단을 따른다.

## 필수 공통 시나리오

- 작업을 수락하고 handle을 반환한다. 여러 내부 도구·모델 호출이 한 Task에 포함될 수 있다.
- 완료·확인된 실패·확인된 취소·종료 미확정의 outcome과 state가 일치하고 한 번만 확정된다.
- 모든 outcome에서 waiter 반환 전에 terminal state·빈 pending snapshot이 확정되고, 남은 요청의 정리와 늦은 응답 거절이 이뤄진다. 완료·실패 경로도 취소·close와 별도로 검사한다.
- observer가 없거나 늦거나 느려도 진행·pending·outcome이 막히지 않는다. 구독 중 유실은 gap으로 알려지고 terminal은 잃지 않는다.
- 같은 session의 문맥이 다음 작업에 이어지고 다른 session과 섞이지 않는다. 진행 중 중첩 시작은 실제 요청 전에 거절한다.
- 취소 요청이 완료를 미리 확정하지 않는다. 자연 완료 경쟁, 즉시 취소, 정리 중 경쟁을 검사한다.
- close/release는 공개한 적용 범위·전체 시간 상한 안에서 handle을 정리한다. 여러 자원과 비협조적 작업에서도 waiter가 남지 않으며, 실제 종료를 확인하지 못하면 Unresolved를 회수한다.
- Unresolved 뒤에도 실제 효과가 발생할 수 있는 fixture로 거짓 취소를 검출한다. 같은 문맥의 새 작업은 [차단·복구 범위](lifecycle-and-concurrency.md#문맥-차단과-복구-범위)에 따라 거절한다.
- 정리와 native terminal이 경쟁하면 실제로 먼저 확인된 terminal fact를 보존하고 이후 알림이 outcome을 덮어쓰지 않게 한다. 예약된 완료가 취소보다 우선한다고 가정하지 않는다.
- 반대 방향으로 Task 범위에 해당하는 충분한 종결 근거를 전달한 fixture에서 Unresolved를 반환하면 실패한다. Completed·Failed·Cancelled 각각을 검사하고, handle 확정 뒤 도착한 알림이 outcome을 덮어쓰지 않는 경우와 구별한다.
- 관찰 stream 종료와 실제 실행 종료를 분리한다. 내부 turn만 끝나고 Task는 진행 중인 경우, 귀속된 실행 전체의 종료를 입증한 경우, process 밖 작업이 남아 있는 경우를 구별해 [종결 증거 규칙](lifecycle-and-concurrency.md#종결-확인의-근거)을 검사한다. 공통 판정에는 provider 신호 이름을 넣지 않는다.
- 같은 harness가 소유한 동일 문맥의 모든 handle에 차단이 적용된다. release 뒤에도 차단을 우회하지 못하고, 독립된 새 session에서는 기존 문맥을 자동 복사하지 않고 시작할 수 있다. 새 session 생성이 이전 작업의 외부 효과를 중단했다는 판정을 만들지 않는다.
- 알려진 부분 산출물·사용량이 있는 상태에서 네 outcome을 각각 유도한다. observer 없음·유실에서도 결과로 회수되며, unknown·불완전·검증 실패가 빈 값·0·성공으로 바뀌지 않아야 한다. Unresolved의 관찰값을 실제 실행의 최종값으로 보고하지 않는다.
- 산출물이 없는 정상 종결도 Completed(output=null)로 회수한다. 실제 빈 문자열을 제공한 경우와 구별하며 이전에 확보한 부분 산출물은 종결 보고의 null 때문에 지우지 않는다.
- 잘못된 값·지원 불가 요구·호출 실패와 handle을 얻은 이후 outcome을 구별한다.
- 시작 요청 수락 뒤 응답을 잃은 경우를 요청 전 거절과 구별한다. handle 반환 실패만 보고 재시도하거나 같은 문맥에 새 작업을 시작하지 않는다.
- 같은 하위 ID를 가진 서로 다른 Task의 이벤트·응답이 섞이지 않는다. 공백 입력을 그대로 전달하고, null/빈 지시를 구별하며, active skill 본문을 실제 native 지시에 적용하고, reopen의 정규화된 응답 ID를 보존한다.
- 완료가 업무 성공이나 산출물 schema 검증을 뜻하지 않음을 확인한다.

사용량을 관찰하는 경로에서는 provider 증분과 누적 입력을 각각 사용해 공통 누적 snapshot이 같은 의미가 되는지 검사한다. 반복 snapshot, 누락·reset, Task/Session 분리와 최종 관찰을 확인한다. 실패 분류는 구조화 정보·확인된 native 예외와 자연어 문구만 있는 경우를 구별한다.

reportUsageSnapshot은 누적값 전체, reportUsageDelta는 겹치지 않는 구간의 증분이다. 의미를 fixture마다 바꾸지 않는다. 미측정 구간과 알려진 구간의 합이 알려진 전체로 바뀌지 않는지 검사한다. 문맥·입력·지시는 실제 runtime 관찰로 확인하며, 예상 답변을 reportCompletion에 직접 주입한 결과만으로 문맥 연속성을 판정하지 않는다.

이벤트 전환에서는 공개 설명이 메시지로 보존되고 최종 산출물을 덮어쓰지 않는지 검사한다. 내부 context 관리 알림은 기본 효과·의미 이벤트를 만들지 않고 지원한 진단 경로에서 다룬다. 같은 이름·인자라도 native call identity가 다르면 별도 WorkId이고, 입증된 동일 호출의 tool/effect는 같은 WorkId여야 한다. 상관관계를 알 수 없는 경우를 같은 작업으로 합치지 않는다.

필수 검사는 모든 적합 구현이 통과해야 한다. 전부 거절하거나 skip하여 통과시키지 않는다. 종료 미확정을 정상 지원 불가의 대체 결과로 쓰지 않는다.

## 선택 계약 검사

| 선택 계약 | 지원 구현 | 미지원 구현 |
|---|---|---|
| Caller 승인 | 승인 전 효과 0, 허용 범위 내 효과, 거절 시 효과 없음, 중복·만료·취소 처리. 응답 수락 뒤 acknowledgement 유실 시 이중 응답 방지 | 필수 승인 요구를 작업 전에 거절 |
| 질문 | 현재 요청, typed 답변, 같은 작업 계속, 응답·철회 경쟁. 전달 확인 유실과 미전달을 구별 | 필수 질문 요구를 거절; 승인 enum으로 위장하지 않음 |
| 영속성 | 약속한 보관 범위, release/재생성 후 reopen, desired configuration, 모르는 ID, 저장 실패 | 영속 요구를 거절; 기본 session ID로 재개 보장하지 않음 |
| Context retention | ephemeral 요구의 native 전달, 생성 응답 관측, 반대·누락 응답의 fail-closed | 요구를 provider default로 생략하지 않고 사전 거절 |
| User history visibility | 독립 visibility 관측과 일반 사용자 history 비노출 | 관측할 수 없으면 UNKNOWN으로 보고하고 Hidden 요구를 UNCONFIRMED/미지원으로 거절 |
| 권한·작업 자원 | 요청 scope의 실제 집행·자료 해석·활성화 | 요청을 default로 낮추지 않고 거절 |
| 구조화 산출물 | schema 요구·검증 성공·실패·부분 산출물의 구별 | JSON 문자열 전달만으로 지원 선언하지 않음 |
| 진단 | 선언한 범위·유실·상관관계 | 원본 이벤트 부재가 기본 Task 적합성을 막지 않음 |

한 선택 기능의 지원이 다른 보장을 의미하지 않는다. checkpoint 복원이 이력 조회나 외부 효과의 exactly-once를 자동 보장하는지 검사하지 말고, 그런 별도 계약을 제공할 때 별도 시나리오로 검증한다.

세션 승인을 제공하는 adapter는 `SessionApprovalGrant`가 설명하는 실제 대상 집합을 준비하고 허용 범위 안의 후속 행위와 범위 밖 행위를 각각 실행해야 한다. 같은 scopeId의 권한 확장·다른 session 전파를 허용하지 않는다. 현재 세 구성은 그 범위를 집행할 수 없어 지속 승인 선택지를 제공하지 않으며, Codex의 일회 승인은 실제 효과로 검사한다.

ResponseControl로 실제 수락·미수락 각각에서 acknowledgement를 잃게 한다. InteractionResponseUnconfirmedException과 Task/Interaction identity, pending 제거·RESPONSE_UNCONFIRMED, 재응답 거절·native 중복 제출 없음으로 판정한다. 이때 Task의 Cancelled나 provider의 거절을 합성하지 않는다.

TaskDiagnostics가 지원되면 진단 observer만 느리게 하거나 진단을 넘치게 한 상태에서 의미 이벤트와 outcome을 검사한다. 진단 유실은 DiagnosticGap이며 TaskEvent.ObservationGap과 구별한다. 기본 의미 이벤트 stream에는 ProviderDiagnostic이 나타나지 않는다.

지원 탐색·validate·실제 수락의 관계도 검사한다. 같은 adapter라도 구성·session 조건이 달라 지원 여부가 바뀌는 사례, 미확인 조건, 조회 없이 직접 요구한 사례, 조회 뒤 환경이 변한 사례를 포함한다. 사전 검증을 건너뛰어도 필수 요구가 집행되거나 실제 작업 전에 거절되어야 한다. 수락 뒤 요구를 이행할 수 없게 되는 fixture에서는 조용한 완화 대신 실패·미확정 판정을 확인한다.

영속성 검사는 선언한 재개·조정 범위별로 나눈다. 동일 ID 문자열의 다른 저장 namespace를 거절하고 정규화한 참조를 보존한다. harness 재생성·process 재시작을 지원하면 미확정 문맥의 차단도 그 경계 너머에서 보존하거나 검증된 복구로 해소해야 한다. 다중 접근 미지원 구성의 사전 거절과 지원 구성의 조정을 별도로 검사한다. 복구를 제공한다면 차단 해소 조건·문맥 일관성·이전 outcome 불변을 검사하며 단순 reopen 성공을 복구 성공으로 세지 않는다.

## 검증 위치와 실행 명령

| 위치 | 내용 |
|---|---|
| `protocol/core/src/test` | 공개 값 타입과 기본 불변식 |
| `protocol/conformance` | 목적별 fixture seam과 재사용 가능한 공통 판정 |
| `implementations/*/src/test` | adapter 매핑·정책·resource·transport 회귀 |
| `implementations/codex/host/tests` | Python Codex host와 stub App Server |
| `implementations/gemini-cli/host/tests` | Node Gemini host |
| `verification/adapter-testkit` | process adapter의 SDK 경계 공통 검사 |
| `verification/native-integration` | 세 실제 runtime과 통제된 모델 경계의 계약 검사 |

Gradle 프로젝트 이름과 발행 artifact 이름은 물리 디렉터리와 독립적으로 유지한다.

```powershell
./gradlew.bat test
./gradlew.bat :harness-conformance:testFixturesClasses
./gradlew.bat hostTests
./gradlew.bat check -PstrictHostTests
./gradlew.bat test hostTests -PnativeHarnessTests -PstrictHostTests
```

Codex host는 Python 3.10+, `implementations/codex/host/requirements.txt`와 pytest가 필요하다. Gemini host는 Node 20+가 필요하다. `hostTests`는 interpreter가 없으면 건너뛸 수 있으므로 전체 검증에서는 `-PstrictHostTests`를 사용하고 실제 실행 수를 확인한다.

## 증거 기록

문서/API 조사, 통제된 모델 경계의 실제 runtime 검증, 외부 실모델 호출을 구별한다. 최종 실행 수와 source hash는 [native 실행 기록](../verification/native-integration/evidence/final-g01-g12.json), 전체 JVM·host 집계는 [최종 회귀 요약](../verification/native-integration/evidence/final-regression-summary.json)에 있다. 역사적 C/K identity의 처리 근거는 [시나리오 대응표](conformance-scenarios.md)와 [기계 판독 catalog](../protocol/conformance/scenario-catalog.json)에 남긴다.

외부 실모델과 현재 구성이 거절하는 선택 기능은 검증된 성공 경로로 세지 않는다. 공통 판정 본문이 존재하는 것과 실제 adapter binding에서 실행된 것도 구별한다.