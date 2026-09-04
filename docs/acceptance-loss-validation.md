# 시작·응답의 수락 확인 유실 검증

G02는 실제 요청의 미전달과, 전달 후 수락 여부를 확인하지 못하는 상황을 구별한다. 요구 이행 가능성의 `RequirementUnconfirmedException`과도 다른 계약이다. 기준은 [설계 선언](../AHP_CHARTER.md), [시작·interaction 계약](protocol-reference.md), [생명주기](lifecycle-and-concurrency.md)다.

## 실제 경계와 공통 판정

공통 판정은 `HarnessAcceptanceConformanceTest.kt`에 있고 provider wire를 읽지 않는다. 실제 runtime 구성과 전달 제어는 native binding에 있다. `AcceptanceFixture`는 진행 중인 실제 작업과 모델 관찰을 연결한다. `StartAcceptanceFixture`와 `ResponseAcceptanceFixture`는 각각 실제 요청/확인 경계와 caller 승인 채널이 있는 구성에만 연결한다.

Codex·Gemini의 전달 제어는 실제 `JsonLineProcessBridge`와 공식 SDK host를 감싼다. 미전달은 요청을 host에 넘기기 전에 실패시킨다. 수락 후 유실은 실제 host의 수락 응답을 받은 뒤 adapter에 돌려주지 않는다. 미수락 상태의 유실은 host에 요청을 넘기지 않고 adapter에는 확인 유실을 보고한다. fixture는 실제 전달 여부를 알지만 adapter는 그 사실을 알 수 없다.

이것은 SDK 전달 경계에서의 결정적 오류 주입이다. 물리적인 네트워크 단절·패킷 유실을 일으킨 검사는 아니다. Port handle이나 이벤트를 합성하지 않으며, 수락된 실행이 실제 모델 경계까지 도달하는지 별도로 확인한다. `observedSubmissions`는 제어 경계에 들어온 호출 시도, 수락 횟수는 실제 host가 응답한 수락을 기록한다.

## 구성별 적용

| 목적 | Codex | Gemini CLI | Koog |
|---|---|---|---|
| 실제 작업 진행 중 유효한 handle 회수 | 실제 runtime | 실제 runtime | 실제 graph |
| 확인된 미전달 뒤 안전한 재시도 | SDK 전달 경계 | SDK 전달 경계 | 현재 로컬 handoff에 전달 통신 단계 없음 |
| 실제 수락/미수락 각각에서 시작 확인 유실 | SDK 전달 경계 | SDK 전달 경계 | 현재 startTask의 비중단 반환을 별도로 검사 |
| 승인 응답 확인 유실·실제 효과·중복 제출 방지 | 실제 승인 handler와 파일 효과 | caller 승인 요구 거절: G01 | caller 승인 요구 거절: G01 |

Koog의 현재 startTask는 로컬 호출 안에서 handle을 돌려주며 별도 수락 응답을 기다리지 않는다. UNDISPATCHED 호출의 즉시 완료를 확인하는 검사는 이 근거가 구현 변화로 사라지면 실패한다. Koog 밖에 임시 Port proxy를 붙여 없는 통신 단계를 만들지 않는다. Gemini·Koog의 미지원 승인 경로도 가짜 요청으로 대체하지 않는다.

## 판정하는 보장

- **시작 미전달:** native 수락 0회, 모델 입력 없음, 같은 session에서 정상 재시도 가능.
- **시작 확인 유실:** 실제 수락 여부와 무관하게 `TaskStartUnconfirmedException`과 session/request identity를 회수하고 전달 경계가 기록한 실제 요청과 정확히 대조한다. 같은 문맥의 재제출은 경계에 도달하지 않고 차단되며 독립 session은 계속 사용할 수 있다. 나중에 실제 작업이 끝나도 기존 문맥을 자동으로 안전하다고 판정하지 않는다.
- **응답 미전달:** pending 요청은 유지되고 효과는 없으며 한 번의 정상 재시도로 실제 효과를 만들 수 있다.
- **응답 확인 유실:** `InteractionResponseUnconfirmedException`의 Task/Interaction identity, pending 제거와 `RESPONSE_UNCONFIRMED`, 같은 요청의 재응답 거절, native 중복 제출 없음을 검사한다. 미수락이면 실제 효과 0이며 그 자체로 Task를 Cancelled로 만들지 않는다.

Codex 응답 검사에서는 모델 경계가 실제 command 도구 호출을 반환한다. 승인 대상은 격리된 테스트 디렉터리의 marker 파일에 한 번 추가하는 행위다. 승인 전 효과 0회와 수락 후 효과 1회를 파일에서 관찰한다. 수락 후 유실에서는 host의 응답뿐 아니라 같은 수락을 알리는 `interaction_resolved` 알림도 차단한다. 독립 확인 알림이 전달된 상황을 미확인으로 가장하지 않는다. 도구 실행 다음 모델 응답은 별도 gate로 유지해 확인 유실 판정보다 Task 완료가 먼저 도착하지 않게 한다.

동시 철회·취소·종결이 응답과 경쟁하는 경우는 G03에서 다룬다. 지속 승인 범위, 같은 문맥의 여러 handle, process 재시작 이후 차단 및 외부 작업의 정리는 각각 해당 후속 시나리오의 범위다.

## 연결 과정에서 확인한 사항

첫 G02 실행에서 시작 관련 10개는 통과했고 승인 응답 3개는 pending 요청을 기다리다 시간 초과됐다. fixture가 해당 모델 구성에 노출된 `shell_command`를 처리하지 못해, 실제 승인 대상 호출을 만들지 못한 것이 원인이었다. 실제 모델 요청의 도구 이름을 선택하도록 보완한 뒤 응답 3개가 통과했고 파일 효과도 확인했다. [첫 실행 기록](../harness-native-integration/evidence/acceptance-loss-first-run.json)을 보존한다. Port나 production adapter의 계약을 바꿔 해결한 문제가 아니다.

기존 K13/K14 정의는 실제 전달·수락 횟수와 identity까지 확인하는 이 검사로 대체했다. 나머지 Core 27개·Cleanup 17개, 총 44개 정의는 아직 미연결이다. C20/C21의 요구 판정과 G02의 수락 확인 유실은 별도 범위로 유지한다.

## 검증 결과

공통 정의 7개가 실제 binding에서 12회 실행되고 Koog의 비중단 handoff 검사 1개를 더해 G02는 13개다. Codex 시작 4개·응답 3개, Gemini 시작 4개, Koog 2개이며 실패·오류·건너뜀은 0개다. [provider별 결과](../harness-native-integration/evidence/acceptance-loss.json)는 실제 적용 경계, JUnit 이름·시각과 최종 소스 hash를 기록한다.

`./gradlew.bat --offline test :harness-conformance:testFixturesClasses -PnativeHarnessTests --console=plain`으로 전체 209개가 통과했다. 마지막에 불확실한 시작의 reference를 실제 전달 기록과 정확히 대조하는 assertion을 보강하고 G02 13개를 별도로 다시 실행해 통과했다. [전체 실행 기록](../harness-native-integration/evidence/acceptance-loss-full-regression.json)과 [최신 검증 집계](../harness-native-integration/evidence/verification.json)는 두 실행을 구분한다. 최종 13개는 같은 검사의 이전 결과를 교체하며 다시 합산하지 않는다. 고유 검사는 총 209개, native 모듈은 129개다.

변경 없는 일부 non-native 검사는 Gradle up-to-date 결과를 재사용했다. host·sample·독립 Koog 실험·JAR 감사는 이번 단계에서 재실행하지 않았다. 남은 44개 미연결 정의나 적용되지 않는 오류 주입을 통과 수에 넣지 않았다. 다음 묶음은 G03의 interaction 철회·응답·취소·종결 경쟁이다.
