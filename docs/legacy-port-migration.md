# Legacy Port 이전과 회귀 검토

2026-09-04. 기준은 [AHP 설계 선언](../AHP_CHARTER.md)과 [현재 공개 계약](protocol-reference.md)이다.

모든 실행 소스가 `dev.harnessprotocol`의 현재 Port를 사용한다. 구 패키지의 타입 6개 파일, 구 bundle factory, 두 Legacy harness, 두 구 mapper, BridgeAgentExecution·BridgeHarnessRuntime을 제거했다. native host의 SDK 옵션은 독립된 CodexSdkOptions·GeminiCliSdkOptions 파일로 옮겼다. EmbeddedBridgeResource와 SdkBridge의 예외도 현재 HarnessTransportException을 사용한다.

## 보존한 검사와 바꾼 판정

| 기존 검사의 목적 | 현재 검증 경로 / 판정 |
|---|---|
| 설정 의도 보존·미지원 사전 거절 | SpecSpace와 독립 IntentProjection이 실제 CodexHarness·GeminiCliHarness를 검사. null/빈 지시, workspace 부재/빈 요구, skill 제공/활성화, filesystem·network·승인을 분리 |
| 완료·실패·취소·부분 결과 회수 | 실제 adapter의 AgentTask·TaskOutcome 검사. 취소 요청만으로 Cancelled를 만들지 않음 |
| 관찰 상실과 자원 정리 | EOF/read 실패·정리 만료는 Unresolved. 다음 작업의 문맥 차단과 native 시작 미호출 확인 |
| observer 독립·overflow·terminal 보존 | 현재 adapter와 production ManagedTask의 bounded queue 검사. RecordingBridge는 SDK 경계만 제어 |
| usage·메시지 역할·하위 작업 상태 | CodexTaskMapperTest 9개와 GeminiTaskMapperTest 8개. production ManagedTask의 의미 이벤트·outcome을 직접 관찰 |
| 승인 응답·취소·중복 거절 | 실제 CodexHarness의 pending snapshot·typed response·host 전달 검사. 범위 없는 acceptForSession은 선택지에서 제외 |
| 문맥 재개·host 정규화 ID | PersistentSessions와 명시적 persistence 요구·참조 사용. host가 돌려준 정규화 참조로 다시 재개할 수 있는지도 확인 |
| 실제 process 종료·NDJSON 손상 | ProcessLifecycleTest를 Gemini adapter 모듈로 옮겨 실제 ProcessTaskHarness·GeminiTaskMapper를 구동. 직접 상태를 설정하던 구 실행기는 제거 |
| bundle의 공통 타입 | 현재 Harnesses 반환값을 현재 ProviderId로 검사 |

이전 과정에서 Gemini EffectChanged의 명령 설명 누락을 복원했다. Codex 재개 설정 기록은 namespace와 session ID로 구분하고 정규화된 재개 ID도 기록한다. 테스트의 RecordingBridge는 동시 native reader와 이벤트 주입이 같은 mailbox를 사용하도록 concurrent collection으로 바꿨다.

Codex에서 구조화된 실패 코드가 없으면 UNKNOWN을 유지한다. Gemini의 내부 error·blocked·finished 관찰만으로 부모 작업을 종결하지 않으며 host가 전체 호출 종료를 확인해야 한다. 과거 구현의 강제 Failed·Cancelled나 추측성 분류를 회귀 보장으로 붙들지 않는다.

## 독립 Koog 실험

실험 빌드가 과거 Git revision의 Port 소스를 추출하던 의존도 제거했다. composite build로 현재 harness-protocol·harness-runtime을 사용하며 별도 ObservationStream은 삭제했다. 실제 graph·승인 도구·파일 보관·native checkpoint 검사를 유지한다.

기존 질문의 표현 불가 검사는 현재 Question·Answer로 실제 질문 도구를 이어 실행하는 검사로 바꿨다. 반복 한도는 산출물 null을 보존하고, 비협조적 효과는 Unresolved 이후에도 실제 발생할 수 있으며 판정이 바뀌지 않는지 검사한다. 이 구성의 승인·질문·파일 보관 지원을 production bare Koog 구성의 지원으로 일반화하지 않는다.

과거 evidence JSON은 당시 기록으로 유지한다. 현재 실행 결과는 [native 검증 기록](native-port-validation.md#실행-결과)과 [Koog 이전 기록](../experiments/koog-validation/evidence/current-port-migration.json)에 구분한다. 48개 미연결 conformance 정의는 계속 컴파일만 하며 실행·통과 수에 넣지 않는다.

## 검증 범위

전체 JVM·native·host 검사, 독립 Koog 실험, 소비 sample 컴파일을 수행한다. 소스·빌드 스크립트와 생성 JAR에서 구 Port 의존이 사라졌는지, 문서 링크와 diff 공백도 확인한다. 개별 실행 수와 결과는 위 기계 판독 기록을 따른다. legacy 제거는 모든 선택 계약과 경쟁 조건의 적합성 인증을 뜻하지 않는다.
