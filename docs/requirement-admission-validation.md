# 요구 사례의 실제 수락 검증

단계 2의 첫 묶음인 G01은 지원 조회·사전 검증·호출 경계의 수락을 독립적인 기대값과 대조한다. 상위 기준은 [설계 선언](../AHP_CHARTER.md)과 공개 계약이며, 특정 provider의 기능 집합을 모든 구현의 필수 기능으로 만들지 않는다.

## 실행 구조

`ProfileFixture`는 기존 `HarnessFixture`의 profile 선언·harness 생성을 분리한 검사 경계다. 새 비즈니스 Port가 아니다. `RuntimeRequirementsFixture`는 실제 runtime의 모델 관찰을 추가한다. 전체 fault injection을 구현했다고 가장하거나 AgentTask의 state/outcome을 직접 조작하지 않는다.

공통 판정은 `HarnessRequirementsConformanceTest`에 있다. native binding은 기존 runtime 검사의 factory를 함께 사용한다. `NativeRequirementsFixture`의 기대 지원과 요구 사례는 고정한 제공 구성의 선언이며, `support`·`validate`의 결과에서 만들지 않는다. 지원 종류와 Conditional의 scope를 비교하고 이유·조건 설명이 비어 있지 않은지 확인한다. 설명 문구의 철자 일치는 요구하지 않는다.

각 사례는 서로 다른 harness에서 두 번 실행한다.

- **preflight:** session/task의 validate 결과를 선언된 기대값과 비교하고, 실제 호출의 수락·거절을 별도로 확인한다.
- **direct:** 검사 코드가 support·validate를 먼저 호출하지 않고 요구를 전달한다. 호출 경계 내부의 필수 검증은 그대로 수행된다.

수락된 작업은 실제 runtime에서 Completed까지 진행하고, 입력이 실제 모델 경계에 도달했는지 확인한다. 작업 요구 거절 뒤에는 같은 session에서 정상 작업을 다시 수행한다. 미지원·미확인 요구는 서로 다른 예외와 진단 종류로 판정하며, 작업 수락 확인 유실 예외를 대신 허용하지 않는다. 사전 검증·생성·거절 단계에서 모델 호출이 발생하면 실패다.

## 독립 구성과 사례

| Provider | Profile | 저장 구성 | 요구 사례 |
|---|---|---|---:|
| Codex | client-storage | 명시 namespace, 같은 application process에서 재개 | 13 |
| Codex | client-ephemeral | namespace 없음 | 2 |
| Gemini CLI | sdk-history | 명시 namespace, 같은 application process에서 재개 | 13 |
| Gemini CLI | sdk-live | namespace 없음 | 2 |
| Koog | graph-tools | 메모리 문맥, 영속 저장 없음 | 13 |

전체 구성에서는 기본 작업, 영속 문맥, process 재시작, 동시 저장 접근, caller 승인·질문, 진단, workspace, 읽기 전용, 파일시스템 조건 없는 network 요구, workspace/network 조합, 두 구조화 산출물 요구를 검사한다. namespace가 없는 추가 구성은 기본 작업과 영속 요구의 사전 거절을 검사한다. profile 이름과 목록 첫 사례에 의존하지 않고 선언된 모든 사례를 실행한다.

영속성은 항상 거절된다고 가정하지 않는다. Codex/Gemini의 namespace 구성에서 수락과 참조·연산 interface를 확인하고, namespace 없는 구성 및 Koog에서는 거절을 확인한다. network ALLOWED만 요청했을 때도 미확인이라고 고정하지 않는다. 현재 세 구성의 기대는 확정적 거절이며 Codex의 명시 workspace-write 조합은 별도 수락 사례다.

## 검증 범위의 한계와 후속 작업

수락된 승인·권한·workspace 요구로 정상 텍스트 작업이 끝났다는 사실만으로 효과의 승인 범위나 sandbox 집행을 인증하지 않는다. 이 사례들은 요구의 수락 경계 검증이며, 실제 효과·자원 접근은 G03/G08/G09에서 확인한다. 진단 interface 존재와 영속 참조 발급도 각각의 전체 의미 이행 검증과 구별한다. 기존 실제 진단 종료·영속 문맥 재개 검사는 계속 유지한다.

모델 입력 관찰만으로 모든 native RPC·session 할당·도구 부작용이 없었다고 단정하지 않는다. 거절 전에 모델 호출이 없었다는 범위의 증거다. 요청 전달·수락 확인 유실은 G02의 별도 경계 제어가 필요하다.

현재 구성에는 실제 UNCONFIRMED 지원/요구 사례가 없다. 공통 판정은 그 분기를 표현하지만, 실행하지 않은 분기를 통과했다고 계산하지 않는다. 이를 만들기 위해 임시 Port나 합성 지원 상태를 추가하지 않았다.

기존 C20/C21의 고정된 거절·미확인 전제는 삭제하고 이 요구 사례 판정으로 대체했다. C20의 실제 거절 사례는 세 구현체에서 실행하며, C21의 UNCONFIRMED 분기는 현재 실행 근거가 없다. 나머지 Core 27개·Cleanup 19개, 총 46개 정의는 아직 전체 fixture에 미연결이다. 그 안의 profile 이름 등은 효과 제어 연결 시 함께 전환한다. 새 요구 검사를 기존 48개의 전체 통과로 환산하지 않는다.

## 실행 결과

`./gradlew.bat --offline :harness-native-integration:test -PnativeHarnessTests --tests '*NativeRequirementsTest' --console=plain`은 통과했다. Codex 32개, Gemini 32개, Koog 27개로 총 91개이며 실패·오류·건너뜀은 0개다. 요구 사례 43개 × preflight/direct = 86개와 profile 지원 검사 5개로 구성된다.

[기계 판독 결과](../harness-native-integration/evidence/requirement-admission.json)에 provider × profile × scenario × mode × 실제 경계 × 결과, JUnit suite·검사 이름·시각과 실행 소스 hash를 남겼다. 이 91개는 기존 runtime 25개와 별도 검사이며 기존 48개를 이름만 바꿔 통과로 집계한 결과가 아니다.

기존 회귀 검사도 `./gradlew.bat --offline test :harness-native-integration:test --tests '*NativeHarnessTest' --tests '*NativeToolTest' :harness-conformance:testFixturesClasses -PnativeHarnessTests --console=plain`로 확인했다. 기존 105개(실제 runtime 25개 포함)가 통과했으며, 변경 없는 일부 검사는 Gradle up-to-date 결과를 재사용했다. 새 요구 검사 소스 hash가 그대로인지 확인한 뒤 두 실행의 JUnit 결과를 합산했다. [현재 검증 기록](../harness-native-integration/evidence/verification.json)은 총 196개, 실패·오류·건너뜀 0개다. host·독립 Koog 실험·sample은 이 단계에서 재실행하지 않았다.

다음 묶음은 G02의 시작·응답 수락 확인 유실이다. 요구 이행 가능성의 UNCONFIRMED와 전달 후 수락 확인 유실을 구별하며, 실제 경계에서 발생한 사실로 session 차단·재제출 방지·interaction identity를 검증한다.
