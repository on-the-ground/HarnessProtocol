# Codex ephemeral retention validation

2026-09-06. 이 기록은 provider conversation retention과 사용자 history visibility를 분리하기 위한 근거다. 계정 credential이나 실제 사용자 history는 사용하지 않았다.

## 배포·소스 기준

- Maven Central의 `io.github.joohyung-park:harness-protocol`과 `harness-codex` 최신 공개 버전은 `0.1.0` 하나다. GitHub release와 version tag는 없다.
- 검토한 최신 main 기준은 `43a24dc3f31eada6c7d8f792f5757f850d5db3e9`다. 공개 `0.1.0`과 이 revision 모두 provider retention 요구, user-history visibility 요구, 생성 결과 disposition을 제공하지 않았다.
- Codex bridge는 `openai-codex==0.147.0`을 고정한다. 해당 SDK의 `ThreadStartParams`에는 `ephemeral`이 있고 `Thread.ephemeral` 설명은 “disk에 materialize하지 않음”이다. 공식 App Server 문서도 in-memory temporary thread와 `thread.path == null`을 설명한다.

근거: [Codex App Server README](https://github.com/openai/codex/blob/rust-v0.147.0/codex-rs/app-server/README.md), [0.147.0 thread/start test](https://github.com/openai/codex/blob/rust-v0.147.0/codex-rs/app-server/tests/suite/v2/thread_start.rs), [Maven metadata](https://repo.maven.apache.org/maven2/io/github/joohyung-park/harness-protocol/maven-metadata.xml).

## 인증 없는 격리 검사

`openai-codex==0.147.0`을 새 virtual environment에 설치하고 빈 임시 `CODEX_HOME`으로 실제 bundled App Server를 시작했다. turn이나 모델 호출 없이 persistent/ephemeral thread를 각각 생성했다.

| 관찰 | ephemeral | persistent control |
|---|---:|---:|
| 반환 `thread.ephemeral` | `true` | `false` |
| 반환 `thread.path` | `null` | rollout 대상 경로 |
| 임시 home의 rollout/lock materialization | 없음 | thread writer lock 관찰 |
| 같은 connection의 기본 `thread/list` | 미포함 | 첫 user message 전이라 미포함 |

공식 0.147.0 Rust suite는 통제된 모델 응답으로 turn까지 실행한 뒤에도 ephemeral thread가 pathless임을 별도로 검증한다. 이번 격리 검사는 실제 계정 인증, 원격 provider retention, Codex Desktop 계정 Recents를 검증하지 않았다.

## 계약 판정

- `ContextRetentionRequirement.Ephemeral`은 SDK가 직접 제공하고 생성 응답으로 확인할 수 있으므로 Codex adapter가 지원한다.
- `UserHistoryVisibilityRequirement.Hidden`은 SDK에 독립 제어·관측 필드가 없다. local no-materialization이 일반 Recents 비노출의 구현 근거가 될 수는 있지만 account-wide visibility 보장으로 확대하지 않는다. Codex adapter는 이 capability를 `Unknown`으로 보고하고 Hidden 요구를 `UNCONFIRMED`로 거절한다.
- `AgentSession.disposition`은 요청값이 아니라 native 생성 응답의 실제 `thread.ephemeral`을 사용한다. 요청한 ephemeral과 응답이 일치하지 않거나 응답이 누락되면 session 성공을 반환하지 않는다.
- 요구 위반으로 방금 생성된 native thread는 정상 release와 구별된 `thread/delete` 보상 경로로 폐기한다. 폐기 확인을 잃으면 생성 실패 진단에 남기고 harness close에서 제한된 시간 안에 재시도한다.
- 정상 생성된 ephemeral session도 `release` 때 `thread/delete`를 확인해 장수 host에 in-memory thread와 그 소유 자원이 누적되지 않게 한다. 삭제 확인을 잃으면 harness close에서 재시도한다. Persistent/default session의 release는 native history를 삭제하지 않는다.
- Ephemeral retention과 `PersistenceRequirement.Required`는 서로 모순이므로 provider 요청 전에 거절한다.

## 소비자 migration

내부 one-shot reasoning처럼 provider conversation materialization을 허용하지 않는 호출은 `SessionRequirements(retention = ContextRetentionRequirement.Ephemeral)`을 명시하고, 생성된 `AgentSession.disposition.retention == EPHEMERAL`을 기록한다. 단순히 `createSession`을 호출하거나 persistence를 요구하지 않는 것만으로 ephemeral을 뜻하지 않는다.

사용자 Recents 비노출이 별도의 필수 조건이면 현재 Codex adapter에서 `historyVisibility = Hidden`을 요청했을 때 성공을 기대하지 않는다. provider가 독립적으로 확인 가능한 계약을 제공하기 전까지 제품 eligibility 판단에서 `UNKNOWN/UNCONFIRMED`를 유지한다.
