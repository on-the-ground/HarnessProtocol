# `codex-agent` 채택 검토 기록

> 상태: 보류 — 재검토 대상  
> 최초 검토일: 2026-09-03  
> 마지막 검토일: 2026-09-03  
> 검토 대상: [`codex-agent-labs/codex-agent`](https://github.com/codex-agent-labs/codex-agent)  
> 검토 커밋: [`b7dfaa45f2bd2a09515ec8abb59eea313e726dcb`](https://github.com/codex-agent-labs/codex-agent/commit/b7dfaa45f2bd2a09515ec8abb59eea313e726dcb)

## 목적

이 문서는 `harness-codex`의 기반을 공식 Python `openai-codex` SDK와 process bridge에서 Kotlin Multiplatform 라이브러리인 `codex-agent`로 교체할 수 있는지 추적하는 결정 기록이다.

검토의 기준은 단순히 Kotlin에서 Codex를 호출할 수 있는지가 아니다. 새 기반이 `harness-protocol`에 선언한 provider-neutral 목적과 의미를 보존하고, 보존할 수 없는 요청은 실행 전에 명시적으로 거절할 수 있어야 한다.

OpenAI 공식 문서상 Codex SDK는 TypeScript와 Python 라이브러리를 제공하며, 인증·대화 기록·승인·스트리밍 이벤트가 필요한 깊은 통합에는 App Server를 사용할 수 있다.

- [OpenAI Codex SDK](https://developers.openai.com/codex/sdk)
- [OpenAI Codex App Server](https://developers.openai.com/codex/app-server)

`codex-agent`는 App Server 위에 Kotlin API와 로컬 runtime lifecycle을 제공하는 제3자 라이브러리이며 OpenAI 공식 Kotlin SDK는 아니다.

## 현재 결정

현재는 주 Codex 구현체로 교체하지 않는다. 다만 다음 재검토 때 우선 평가할 후보로 유지한다.

이 라이브러리가 성숙하고 아래 차단 조건이 해결되면 다음 부분을 대체할 가능성이 크다.

- Python `openai-codex` host
- `bridges/codex_sdk_bridge.py`
- Codex용 NDJSON process bridge
- 소비 환경의 Python 및 Python package 설치 요구
- 우리가 직접 관리하는 App Server JSON-RPC 연결과 프로세스 lifecycle 일부

교체 후에도 다음 코드는 우리 책임으로 남는다.

- `AgentHarness`, `AgentSession`, `AgentExecution` 구현
- `AgentSpec`과 SDK 설정 사이의 의미 변환
- SDK 상태와 `AgentEvent` 사이의 변환
- `CompatibilityReport` 판정
- runtime classifier 선택·패키징·추출
- 공통 contract test와 provider별 회귀 테스트

즉, `codex-agent`는 어댑터를 없애는 대체물이 아니라 어댑터 아래의 SDK/runtime 기반을 대체하는 후보이다.

## 기대 효과

| 영역 | 기대 효과 |
|---|---|
| 언어/runtime | Codex 경로를 Kotlin 중심으로 통일하고 Python sidecar를 제거할 수 있다. |
| lifecycle | `CodexHost` → `CodexAgent` → `CodexConversation`의 명시적인 수명 모델을 활용할 수 있다. |
| App Server | 프로세스 시작, 프로토콜 생성물, runtime 검증과 설치를 재사용할 수 있다. |
| 기능 | 인증, approval/elicitation, conversation 관리, skills, MCP, connector, host-defined tool 지원을 활용할 수 있다. |
| 배포 | OS별 App Server classifier를 검증하고 versioned cache에 설치하는 구현을 재사용할 수 있다. |

## 현재 차단 조건

### 1. 안정적인 배포본

검토한 `main`은 README에서 `0.2.0` 좌표를 제시하지만 해당 버전이 아직 tag 또는 publish되지 않았다고 명시한다. 과거 버전이 존재하더라도 검토한 API와 동일하다고 가정하지 않는다.

재검토할 때는 반드시 다음을 확인한다.

- Maven Central에서 JVM artifact와 필요한 platform artifact를 받을 수 있는가?
- 문서의 좌표와 실제 publish 좌표가 같은가?
- source commit, tag, POM, runtime classifier가 같은 release에 묶여 있는가?
- breaking-change 및 호환성 정책이 문서화되어 있는가?

### 2. 라이선스

검토 시점의 라이브러리는 `GPL-3.0-or-later`이고, 함께 배포되는 Codex App Server는 별도의 `Apache-2.0` 라이선스다.

라이브러리 의존 및 classifier 재배포가 Harness Protocol과 소비 애플리케이션의 배포 방식에 미치는 영향은 프로젝트의 라이선스 정책과 법률 검토를 거쳐야 한다. 라이선스 적합성이 결정되지 않은 상태에서는 공개 Maven artifact의 기본 의존성으로 추가하지 않는다.

### 3. instructions 의미 보존

우리 계약의 `AgentSpec.instructions`는 system/developer 수준의 지속 지시다.

검토한 구현은 thread start/resume에 `developerInstructions`를 전달하지만, 값을 라이브러리 내부에서 생성하며 호출자가 지정하는 공개 API가 없다. 사용자 prompt 앞에 instructions를 붙이는 방식은 같은 의미가 아니므로 허용하지 않는다.

교체 조건:

- conversation 또는 thread 생성 시 caller-defined developer instructions를 전달할 수 있어야 한다.
- resume 시에도 동일한 의미가 보존되어야 한다.
- 설정이 지원되지 않으면 실행 전에 판정할 수 있어야 한다.

### 4. filesystem 및 network 정책

우리 계약은 `FilesystemAccess`, `NetworkAccess`, `ApprovalPolicy`를 provider-neutral 실행 의도로 다룬다.

검토한 구현에서는 public conversation settings가 approval preset과 service tier만 제공하고, thread start/resume의 sandbox가 `DANGER_FULL_ACCESS`로 고정되어 있다. 따라서 read-only, workspace-write, provider-default filesystem 의미와 network deny/allow를 현재 API로 보존할 수 없다.

교체 조건:

- provider default, read-only, workspace-write, full-access를 명시적으로 선택할 수 있어야 한다.
- additional writable roots를 전달할 수 있어야 한다.
- network access 의도를 전달하거나, 지원하지 않는 경우 `validate`에서 거절할 수 있어야 한다.
- 설정값과 실제 App Server request를 contract/integration test로 확인할 수 있어야 한다.

### 5. 실행 이벤트와 사용량

우리 `AgentExecution.events`는 개별 실행의 진행 delta, tool/effect lifecycle, context management, usage, terminal event와 알 수 없는 provider event를 관찰하는 포트다.

검토한 `codex-agent`에는 내부 이벤트 계층이 있지만 public API는 주로 누적된 `StateFlow` 상태를 노출한다. 이 API만으로는 다음을 보장하기 어렵다.

- 모든 delta의 순서 보존과 손실 없는 전달
- tool call의 원래 arguments/result/error
- 원시 provider method/payload를 담는 `ProviderEventObserved`
- `UsageChanged`와 최종 `AgentResult.usage`
- 정확히 하나의 terminal event

교체 조건:

- bounded behavior가 문서화된 public event `Flow` 또는 동등한 observer API가 있어야 한다.
- token usage를 실행 단위로 읽을 수 있어야 한다.
- terminal 상태와 cancellation 원인을 구분할 수 있어야 한다.
- 상태 snapshot에서 event를 추정해야 한다면 누락·중복·conflation이 없다는 근거와 테스트가 있어야 한다.

### 6. 세션과 workspace 수명

검토한 API는 하나의 `CodexAgent`가 하나의 active `CodexConversation`을 소유하고, 다른 conversation을 열 때 이전 handle을 해제한다. 반면 `AgentHarness`는 생성한 여러 `AgentSession` handle이 각자 지속 문맥을 유지한다는 의미를 가져야 한다.

또한 `AgentSpec.workingDirectory`는 session별 설정이지만 `codex-agent`의 workspace와 agent 수명은 host lifecycle에 결합되어 있다.

교체 전에 다음 중 하나를 명시적으로 선택해야 한다.

- session마다 별도 `CodexHost`/App Server를 소유한다.
- upstream이 여러 live conversation을 지원할 때까지 기다린다.
- 포트 계약을 훼손하지 않는 session pool/runtime multiplexing 계층을 만든다.

프로세스 수, 시작 지연, close 순서, 동시 실행 제한도 함께 측정한다.

### 7. runtime classifier 배포

`codex-agent`는 App Server를 자동 다운로드하지 않는다. desktop/Node 소비 애플리케이션이 현재 OS와 architecture에 맞는 classifier ZIP을 제공해야 한다.

교체 시 `harness-bundle`은 다음을 해결해야 한다.

- 지원 OS/architecture와 classifier의 정확한 대응
- 단일 Maven dependency로 소비할 때 필요한 classifier가 전달되는 방식
- classifier 무결성 검증과 추출 위치
- offline 실행
- unsupported platform의 명시적인 시작 전 오류
- App Server와 Kotlin client 버전의 원자적인 업그레이드

## 포트별 적합성 체크리스트

재검토자는 아래 표를 최신 공개 API와 실제 통합 테스트 결과로 갱신한다. `부분`은 채택 가능을 뜻하지 않는다. 기본 포트의 의미를 보존하거나 실행 전에 거절할 수 있을 때만 `통과`로 바꾼다.

| Harness Protocol 목적 | 2026-09-03 상태 | 통과 조건 |
|---|---|---|
| 새 session 생성 | 부분 | working directory와 instructions를 보존하여 thread를 생성한다. |
| session resume | 부분 | 같은 지속 문맥과 설정 의미로 resume한다. |
| 여러 live session | 차단 | 기존 session handle을 무효화하지 않는다. |
| 한 번의 agent loop 실행 | 통과 가능 | prompt/model/skill/options mapping을 검증한다. |
| model 선택 | 통과 가능 | 요청 모델과 effort/service tier 적용을 검증한다. |
| instructions | 차단 | caller-defined developer instructions API가 필요하다. |
| filesystem policy | 차단 | sandbox 선택과 writable roots가 필요하다. |
| network policy | 차단 | 명시적인 제어 또는 사전 거절이 필요하다. |
| approval policy | 부분 | 세 preset의 의미 대응과 interactive lifecycle을 검증한다. |
| skill 활성화 | 통과 가능 | name/path 설치 및 실행별 활성화 의미를 검증한다. |
| message/reasoning delta | 부분 | 순서와 누락 없는 public event stream이 필요하다. |
| tool/effect lifecycle | 부분 | ID, arguments, result, failure를 보존해야 한다. |
| context management | 미확인 | compaction 발생과 token 전후 값을 관찰해야 한다. |
| usage | 차단 | 실행 단위 token usage public API가 필요하다. |
| cancel | 통과 가능 | idempotency와 terminal cancellation을 검증한다. |
| provider raw event | 차단 | 원시 method/payload observer가 필요하다. |
| terminal result | 부분 | completed/failed/cancelled의 유일성과 final message를 검증한다. |

## 재검토 트리거

다음 중 하나가 발생하면 이 문서를 다시 검토한다.

- `codex-agent`의 새 stable release가 Maven Central에 publish됨
- sandbox/network/developer instructions/event/usage 관련 public API가 추가됨
- multi-conversation 또는 session multiplexing 지원이 추가됨
- 라이선스가 변경되거나 우리 프로젝트의 라이선스 정책이 확정됨
- 공식 OpenAI Kotlin SDK가 출시됨
- 현재 Python SDK 또는 bridge 유지 비용이 실질적인 장애가 됨

정기 일정만으로 재검토하지 않는다. 위 신호 중 하나가 생겼을 때 검토하는 것이 기본이다.

## 재검토 절차

1. 이 문서의 날짜, 대상 tag와 commit을 먼저 갱신한다.
2. OpenAI 공식 SDK 및 App Server 문서에서 최신 지원 언어와 protocol/runtime 정책을 확인한다.
3. `codex-agent`의 release artifact, POM, classifier, license를 확인한다.
4. 위 차단 조건을 공개 API와 source에서 다시 확인한다.
5. 별도 실험 모듈에서 `AgentHarness` 어댑터를 구현한다. 주 구현을 바로 변경하지 않는다.
6. 공통 contract test를 현재 Python 기반 어댑터와 새 어댑터에 동일하게 실행한다.
7. Windows 외 지원 대상이 있다면 각 OS/architecture의 classifier 통합 테스트를 실행한다.
8. 결과를 아래 결정 이력에 기록하고 교체, 계속 보류, 후보 제외 중 하나를 선택한다.

## 교체 승인 기준

다음 조건을 모두 만족해야 기본 구현 교체를 승인한다.

- stable하고 재현 가능한 Maven release가 있다.
- 라이선스 및 재배포 조건이 승인되었다.
- 모든 기본 포트 의미가 구현되거나 `validate`에서 실행 전에 명시적으로 거절된다.
- 정상 실행, resume, 다중 session, cancellation, failure, close contract test가 통과한다.
- event ordering, terminal uniqueness, usage mapping test가 통과한다.
- runtime classifier의 offline consumer test가 통과한다.
- 독립 소비 프로젝트가 Python 없이 `harness-bundle`만으로 실행된다.
- 기존 Python 구현 제거 범위와 rollback 방법이 정리되었다.

## 예상 마이그레이션 순서

1. `harness-codex-codex-agent` 실험 모듈을 추가한다.
2. 기존 `AgentHarness` contract test suite를 두 Codex 구현에 공유한다.
3. runtime classifier packaging과 `Harnesses.codex()` bootstrap을 구현한다.
4. 의미 차이와 성능을 비교하고 승인 기준을 판정한다.
5. 승인되면 새 구현을 기본값으로 전환하되 한 release 동안 이전 구현을 rollback 경로로 유지한다.
6. 안정화 후 Python bridge와 Codex 경로의 process bridge 의존을 제거한다.

## 결정 이력

| 날짜 | 대상 | 결정 | 이유 |
|---|---|---|---|
| 2026-09-03 | `main` / `b7dfaa45` | 보류, 재검토 후보 유지 | Kotlin-native 기반의 이점은 크지만 배포 안정성, GPL 라이선스, instructions, sandbox/network, public event/usage, 다중 session 의미가 해결되지 않았다. |

새 검토는 기존 행을 수정하지 않고 새 행을 추가한다. 판단 근거가 바뀌면 관련 차단 조건과 포트별 체크리스트도 함께 갱신한다.
