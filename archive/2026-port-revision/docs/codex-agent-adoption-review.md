# codex-agent 채택 검토

상태: 기반 교체 보류. 이번 문서 개편은 **평가 기준의 갱신**이며 후보 코드나 release의 재검증은 아니다.

최초 조사: 2026-09-03. 대상은 [codex-agent-labs/codex-agent](https://github.com/codex-agent-labs/codex-agent)의 [b7dfaa45f2bd2a09515ec8abb59eea313e726dcb](https://github.com/codex-agent-labs/codex-agent/commit/b7dfaa45f2bd2a09515ec8abb59eea313e726dcb)다. 조사 당시 사실과 새 AHP의 채택 조건을 구분한다.

## 교체의 목적과 경계

이 후보는 Codex adapter 아래의 SDK/runtime 기반을 교체하는 수단이다. Kotlin 기반이라는 이유로 AHP의 주체가 되거나 Port의 의미를 결정하지 않는다. 기준은 [설계 선언](../AHP_CHARTER.md)과 [개정 계약](protocol-reference.md)이다.

교체로 Python host·NDJSON 구현·runtime 설치 부담을 줄일 수 있는지 평가한다. AgentHarness/AgentSession/AgentTask, 요구 검증, outcome·output 변환, 선택 기능과 공통 테스트는 여전히 AHP adapter 책임이다.

## 이전 조사에서 확보한 사실

다음 내용은 위 commit에 대한 기존 조사 기록이며 현재 upstream 상태라는 주장이 아니다.

| 영역 | 조사 당시 관찰 |
|---|---|
| 기반 | App Server 위의 제3자 Kotlin Multiplatform 라이브러리 |
| 배포 | README의 0.2.0과 실제 tag/publish 상태의 일치 확인 필요 |
| 라이선스 | 라이브러리 GPL-3.0-or-later, 포함 App Server는 별도 Apache-2.0로 기록 |
| 지시 | 내부 developerInstructions를 구성하지만 caller가 지정할 공개 API가 확인되지 않음 |
| 권한 | 공개 settings가 제한적이며 sandbox가 DANGER_FULL_ACCESS로 고정된 경로가 관찰됨 |
| 관찰 | 내부 이벤트가 있으나 public 표면은 누적 StateFlow 중심 |
| Session | 하나의 active conversation을 소유하며 다른 conversation을 열 때 기존 handle 해제 |
| 배포 runtime | OS/architecture classifier ZIP 제공이 필요하다고 기록 |

기존 보고서의 사실은 재검토의 출발점이다. 실제 채택 때 artifact/POM/source/runtime/라이선스의 같은 버전 여부와 프로젝트 배포 조건을 확인한다. 이번 작업에서 외부 버전이나 법적 적합성을 새로 판정하지 않았다.

## 새 계약에 따른 평가

| 평가 대상 | 충족할 목적 / 판정 기준 |
|---|---|
| 필수 Port | 작업 수락·관찰·문맥 격리·취소 요청·outcome 회수. 모든 기본 동작을 거절해서 적합하다고 판단하지 않음 |
| 문맥 연속성 | native conversation과 별개로 adapter가 보장할 수 있는지 확인. 여러 handle이 조용히 무효화되지 않아야 함 |
| 영속성 | 기본 적합성에서 분리. 지원을 선언하거나 소비자가 요구하면 보관·reopen 범위를 정확히 검증 |
| 지시와 모델 | 요구한 적용 scope·값을 보존하거나 사전 거절. prompt 덧붙이기로 다른 의미를 전달하지 않음 |
| 자원·FS/network | 선택 계약의 지원·집행·거절로 평가. 새 core 밖으로 옮겼다는 이유로 기존 소비 요구를 조용히 포기하지 않음 |
| 승인·질문 | 요청·응답·대기·정리와 실제 효과의 순서. 공급자가 제공하는 approval/elicitation을 목적별로 검증 |
| 진행·산출물 | 상태와 실제 관찰에서 의미를 정확히 제공. 누락된 delta·tool 결과를 지어내지 않음 |
| Outcome | Completed/Failed/Cancelled/Unresolved 구별, 한 번 확정, bounded cleanup |
| Usage/context | 관찰 가능한 값만 전달. 정확한 측정을 별도로 요구하면 충족 여부 검증 |
| 원본 진단 | ProviderDiagnostic의 선택 기능. raw event 부재만으로 기본 적합성을 차단하지 않음 |
| 배포 | 재현 가능한 artifact, 지원 platform, runtime 준비·무결성·offline 조건과 소비 환경 |

누적 StateFlow만 제공한다고 즉시 탈락시키지도, 이벤트를 손실 없이 재구성할 수 있다고 가정하지도 않는다. 필요한 상태·결과·개입을 정확히 얻을 수 있는지 실제로 검증하고 관찰 불가능한 진행은 지원 범위에 반영한다.

기본 문맥 격리를 위해 session별 host나 multiplexing이 필요할 수 있다. 구현 비용과 보장 가능성을 평가하되 upstream 객체 구조를 그대로 공통 계약으로 올리지 않는다.

## 재검토 순서

1. 조사 대상 release·source commit과 실제 배포 artifact를 고정한다.
2. 공개 API에서 필수 계약과 요구한 선택 기능을 어떻게 제공할지 대응표를 작성한다.
3. 격리 adapter에서 같은 업무 시나리오를 수행한다. 공통 검사는 SdkBridge를 요구하지 않는다.
4. 특히 승인 대기 중 취소, close 이후 실제 작업, 문맥 격리·영속 reopen, 관찰 유실을 검증한다.
5. 기존 Codex 소비 요구의 보존·변경과 배포 비용을 비교한다.
6. 실제 연동 범위와 미검증 항목을 기록한 뒤 기본 구현 교체 여부를 판단한다.

새 release, 지시·정책·interaction·관찰 API 변경, 여러 conversation 지원, 배포 조건 변화, 현재 host의 유지 비용 문제가 재검토 계기가 될 수 있다. 현재 기본 구현의 교체나 자동 재검토 작업을 이 문서 작성만으로 시작하지 않는다.

## 교체 조건과 전환

필수 공통 계약을 충족하고 선언한 선택 기능을 실제 제공해야 한다. 소비자가 필수로 요구하던 추가 기능을 잃는 변경은 migration에 명시한다. fixture 성공과 실제 연동 성공을 구분하고, runtime packaging과 독립 소비 예제도 검사한다.

채택할 경우 실험 adapter → 공통·실연동 검증 → 배포 구성 → 기본 factory 전환 순서로 진행한다. 기존 Python 경로 제거와 복구 전략은 실제 교체 작업에서 정한다. 기존 보류 결정은 이번 문서 개편으로 해제되지 않는다.
