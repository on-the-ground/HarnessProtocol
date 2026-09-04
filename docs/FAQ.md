# FAQ

이 문서는 AHP(HarnessProtocol)의 핵심 추상화에 대해 나눈 대화를 재작성 없이 그대로 옮긴 것이다. BFS 방식으로(한 레벨씩 넓게 펼치고, 상대가 고른 방향으로만 더 깊이 들어가는 방식으로) 진행됐다.

---

**Q. 하네스 프로토콜의 핵심 개념들을 중심으로 agent harness를 어떻게 추상하고 있는지 내게 설명해줘. 막 쏟아내지 말고 BFS하듯이 서로 주고받는 방식으로**

좋아요, BFS로 가죠. 먼저 최상위 레벨(depth 0~1)만 펼쳐볼게요.

**Depth 0 — 왜 추상화가 필요한가**
Codex CLI, Gemini CLI, (나중엔 Koog) 같은 서로 다른 에이전트 SDK를 하나의 Kotlin 인터페이스 뒤에 숨기는 게 목표예요. Port/Adapter 패턴이고, `dev.harnessprotocol.*`가 새 Port (계약), 각 SDK별 구현체가 Adapter.

**Depth 1 — 세 개의 축**
Port의 뼈대는 3단 계층이에요:

- **`AgentHarness`** — provider 하나의 진입점. 세션을 만들고 닫는 공장 역할.
- **`AgentSession`** — 대화/문맥의 연속성 단위. 여기서 작업(Task)을 시작함.
- **`AgentTask`** — 실제로 실행되는 작업 하나. 이벤트 스트림과 최종 outcome을 가짐.

이 세 개가 뼈대고, 나머지 개념들(`TaskOutcome`, `TaskEvent`, `CompatibilityReport`, `InteractionRequest` 등)은 전부 이 세 노드 중 하나에 붙어있는 자식들이에요.

여기서 어느 쪽으로 먼저 들어가볼까요? 세 축 중 하나를 고르셔도 되고, 아니면 "이 셋이 왜 3단으로 나뉘었는지" 같은 설계 이유 쪽으로 가도 됩니다.

---

**Q. Harness는 결국 어떤 Harness 구현체를 추상화 해주는 걸거고, 그 안에 다양한 맥락이 있을테니 그걸 세션이 관리하는걸거고 한 맥락 안에서 계속 작업들이 펼쳐질터이니 그게 task가 되겠지.**

맞습니다, 정확해요. 한 가지만 살짝 더 얹으면:

- `AgentHarness` = provider 하나의 연결(설정, 인증, 지원 여부)을 캡슐화한 팩토리
- `AgentSession` = 그 안에서 문맥이 이어지는 단위 — 근데 기본적으로는 세션이 끝나면 문맥도 끝나요. "재개 가능한 세션"은 별도 옵션(`PersistentSessions`)으로만 지원되는 거지, 기본 계약엔 없어요.
- `AgentTask` = 한 세션 안에서 벌어지는 작업 하나. 세션은 동시에 활성 Task를 하나만 가질 수 있어요 (직렬화됨).

이제 각 노드마다 붙는 자식들을 이름만 살짝 보여드릴게요 (아직 안 펼침):

- `AgentHarness`의 자식: `CompatibilityReport`(뭘 지원하는지), `SessionSpec`(세션 만들 때 요구사항)
- `AgentSession`의 자식: 딱히 큰 게 없고 `startTask()` / `release()` 정도의 동작
- `AgentTask`의 자식: `TaskEvent`(스트림), `TaskOutcome`(최종 결과), `InteractionRequest`(승인/질문 같은 중간 개입)

이 중 어디부터 열어볼까요?

---

**Q. 하네스는 결국에... 음 AHP 인터페이스 기준으로 나는 뭘 할 수 있고 못하는지를 응답해야겠지, 그다음에 session을 생성하는게 중요한 업무이니 session 생성에 필요한 의존을 알려줘야겠지.**

맞아요, 정확히 그 두 가지가 `AgentHarness`의 핵심 책임이에요. 여기서 설계상 흥미로운 포인트 하나:

지원 여부를 물었을 때 답이 **둘이 아니라 셋**이에요 — `CompatibilityStatus`가 `COMPATIBLE` / `INCOMPATIBLE` / `UNCONFIRMED` 세 값이거든요. 왜 이진법이 아니냐면, 어떤 요구사항(예: 특정 네트워크 접근 제약)은 harness가 사전에 "된다/안된다"를 확정적으로 알 수 없는 경우가 있어서예요. 그럴 땐 "모른다"를 정직하게 답해야지, COMPATIBLE로 낙관하거나 INCOMPATIBLE로 비관하면 거짓말이 되는 거죠. 그래서 `RequirementUnconfirmedException`이라는 별도 예외까지 있어요 — "거절당함"과 "확인 불가"를 호출자가 구분할 수 있게.

`SessionSpec`은 말씀하신 대로 세션 생성에 필요한 요구사항 뭉치인데, 여기 들어가는 항목들도 나중에 궁금하시면 펼칠게요 (persistence, approval 정책, network 제약 같은 것들).

다음은 어디로 갈까요 — `CompatibilityReport`의 세부 구조 쪽? 아니면 이제 Session으로 넘어가서 `startTask()`가 뭘 하는지?

---

**Q. 잠시만. AgentHarness를 결국 여러 session들을 관리하게 되고 그 말은 그 아래의 다양한 Tasks를 동시에 운영하게 된다고. 아마 concurrent하겠지? concurrency확보가 되어야 할터이니. 이게 중요한 harness의 역할일거라봄**

정확히 짚으셨어요, 근데 경계를 하나 명확히 하는 게 중요해요:

- **Harness 레벨** = concurrent. 여러 세션이 동시에 독립적으로 진행돼요. (실제로 conformance suite에 "different sessions on one harness make progress concurrently without mixing context" 라는 테스트가 있어요.)
- **Session 레벨** = 직렬화(serialized). 한 세션 안에서는 Task가 동시에 두 개 못 떠요 — 이전 Task가 terminal 상태가 되기 전엔 `startTask()`가 거절돼요 (`IllegalStateException`, 그것도 실제 provider 호출 전에 거절되는 게 보장됨).

그러니까 concurrency 확보의 단위는 "harness가 세션들을"이지, "세션이 그 안의 task들을"이 아니에요. 왜 이렇게 나눴냐면, 한 세션 = 한 문맥(대화)이라서 그 안에서 두 작업이 동시에 돌면 문맥이 꼬이거든요. 세션을 문맥의 경계이자 동시에 직렬화 경계로 쓴 거예요.

(참조 구현에서는 이게 `ConcurrentHashMap<SessionId, Session>` + 세션마다 자기 `Mutex` 하나, 이런 식으로 구현돼 있어요.)

이 직렬화 규칙 쪽으로 더 들어가볼까요, 아니면 다시 위로 올라가서 Task 레벨(`TaskEvent`/`TaskOutcome`)로 갈까요?

---

**Q. 그렇겠지... 결국에 session id가 같다면 무조건 serialized되어야 하니까. 정 이 단일 session 속에서 병렬성을 확보하고 싶다면 자그마한 독립 세션을 만들거나.... 해야하려나?**

네, 정확히 그거예요. 그게 이 설계가 암묵적으로 강제하는 트레이드오프예요:

- 같은 세션 안 = 문맥 공유되지만 직렬
- 다른 세션 = 병렬되지만 문맥 안 섞임 (자동으로도 안 섞이게 설계돼있음 — "mixing context" 자체를 명시적으로 막는 게 conformance 시나리오 중 하나예요)

그런데 여기 중요한 함정이 하나 있어요: 새 세션을 만든다고 해서 원래 세션의 대화 히스토리가 자동으로 딸려오지 않아요. `SessionSpec`은 매번 "새 문맥"을 요청하는 거지, "세션A의 문맥을 복제해서 세션B로"같은 fork 개념은 프로토콜에 없어요. 그러니까 병렬화하고 싶으면:

- 원래부터 서로 독립적인(문맥 공유 필요 없는) 하위 작업이어야 하고
- 공유가 필요하다면 그 문맥을 사람이 수동으로 요약해서 각 세션의 초기 지시문(instructions)에 넣어주는 수밖에 없어요.

이건 사실 실제 CLI 프로세스(Codex/Gemini)가 원래 "한 프로세스 = 한 대화 스레드" 구조라서 그런 물리적 제약을 그대로 반영한 거예요. AHP가 인위적으로 막은 게 아니라, 실제 구현체들의 한계를 정직하게 노출한 거죠.

여기서 계속 파고들까요 (문맥 공유가 안 되는 이유를 실제 adapter 레벨에서 보기), 아니면 이제 Task 레벨로 넘어가서 "한 Task 안에서 뭐가 벌어지는지" 볼까요?

---

**Q. task로 이동해보자**

좋아요. `AgentTask`의 최상위 표면부터 보여드릴게요 (아직 안 펼침, 그냥 뼈대만):

Task는 시작하면 handle을 하나 돌려주는데, 그 handle이 노출하는 게 크게 세 가지예요:

1. **`events: Flow<TaskEvent>`** — 진행되는 동안의 이벤트 스트림 (메시지 조각, 툴 호출, 상태 변화 등)
2. **`state: StateFlow<TaskState>`** — 지금 이 순간의 상태 (STARTING/RUNNING/AWAITING_RESPONSE/...)
3. **`awaitOutcome(): TaskOutcome`** — 최종 결과를 기다리는 suspend 함수 (Completed/Failed/Cancelled/Unresolved 중 하나로 끝남)

그리고 능동적으로 개입할 수 있는 두 개의 동작:
- **`respond(...)`** — 중간에 승인/질문 요청이 오면 응답
- **`requestCancellation()`** — 취소 요청

어디부터 열어볼까요 — 이벤트 스트림 쪽, 아니면 outcome이 왜 4갈래(Completed/Failed/Cancelled/**Unresolved**)로 나뉘는지 (이 네 번째가 좀 독특해요)?

---

**Q. 흠.. 궁금한건...
Task하나 아래 subTask가 있나? 그리고 Task하나에 TaskEvent가 여럿일수가 있나? 그리고 TaskOutcome의 상태를 변화시키는 주체는 무엇인가.

respond, requestCancellation은... 아무래도 interrupt/exception 형태로 쓰이는건가.

그리고 Task와 추상화되어 보이지 않는 agent loop과의 관계가 궁금함. 이 프로토콜은 어디에도 agent loop이라는 추상이 없는지.**

하나씩 정확하게 답해볼게요.

**1) Task 아래 subTask 있나?** — 없어요. `AgentTask`는 원자적 단위예요. "여러 내부 도구·모델 호출이 한 Task에 포함될 수 있다"는 문서 표현이 있는데, 이건 Task가 쪼개진다는 뜻이 아니라 — 그 내부 도구 호출들이 `WorkId`로 식별되는 "관찰 항목"으로 같은 Task의 이벤트 스트림 안에 flat하게 나타난다는 뜻이에요. Task-of-Task 계층 구조는 프로토콜에 없어요.

**2) 한 Task에 TaskEvent가 여럿?** — 네, 당연히. `events: Flow<TaskEvent>`는 Task 생명주기 전체에 걸친 스트림이에요. `TaskStarted` → (수많은 `MessageDelta`/`ToolCallChanged`/`EffectChanged`...) → 마지막에 딱 하나의 `Terminal` 서브타입(`TaskCompleted`/`Failed`/`Cancelled`/`Unresolved`)으로 끝나요. 다대일(N events : 1 outcome) 구조.

**3) TaskOutcome을 바꾸는 주체는?** — 소비자(당신) 쪽에선 절대 못 바꿔요. 오직 **adapter 구현체**가 내부적으로 바꿔요 — 실제 provider(Codex/Gemini SDK)의 원본 신호를 받아서 내부 제어 API(`TaskControl`류 — `reportRunning`, `reportCompletion`, `reportFailure`...)를 호출하는 식으로. 이건 harness 소비자에게는 안 보이는 내부 계약이에요. 소비자는 오직 `events`/`awaitOutcome()`로 "관찰"만 하고, `requestCancellation()`으로 "요청"만 할 수 있어요.

**4) respond/requestCancellation이 interrupt/exception인가?** — 아니요, 그건 좀 다른 프레임이에요. 둘 다 **협조적 요청(cooperative request)**이지 강제 인터럽트가 아니에요.
   - `respond()`는 Task가 띄운 `InteractionRequest`(승인/질문)에 대한 응답이고, 응답이 늦어서 유실되면 `InteractionResponseUnconfirmedException`이 나긴 하지만 이건 "응답 자체가 실패했다"는 신호지 Task를 강제로 끊는 게 아니에요.
   - `requestCancellation()`은 "취소해줘 부탁해"지 "지금 죽어"가 아니에요. 실제로 자연 완료가 취소 요청을 이길 수도 있어요 (이전에 봤던 "natural completion wins the race against a cancellation request" 시나리오 기억나시죠). 취소가 관철되는지, 얼마나 걸리는지는 결국 provider 쪽 협조 여부에 달려있고, 그래서 `Unresolved`라는 네 번째 outcome이 존재하는 거예요 — "취소했다고 확신할 수 없음"을 정직하게 표현하려고.

**5) agent loop 추상이 있나?** — **없습니다, 의도적으로.** think/act/observe 반복, 모델 호출, 툴 호출 판단 같은 실제 에이전트 루프는 전부 adapter/provider SDK 내부에 있는 블랙박스예요. AHP는 그 루프를 표준화하려는 시도를 아예 안 해요. 대신 그 루프가 바깥으로 흘려보내는 **관찰 가능한 결과물**(이벤트, effect, outcome)만 표준화해요. 이게 이 프로토콜의 핵심 설계 결정이에요 — "에이전트가 어떻게 생각하는지"가 아니라 "에이전트가 무엇을 관찰 가능하게 만드는지"의 계약이라는 것.

이 중에 제일 파고들고 싶은 게 뭔가요 — WorkId/effect 관찰 쪽? 아니면 Unresolved라는 네 번째 outcome이 왜 필요한지?

---

**Q. 흠... 그렇다는건 Task lifecycle이 이런건가
Task 등록 -> 핸들로 이벤트 스트림, 태스트 상태 스트림, 태스크 결과를 await할 수 있는 핸들 을 받음
그리고 사용자 쪽에서 개입할수 있는건 응답으로 무언가 준다, 취소 부탁한다. 이렇게**

정확합니다. 요약하면:

```
session.startTask(request)  ── suspend 함수, handle을 반환 (또는 거절)
        │
        ▼
   AgentTask handle
        │
        ├── events: Flow<TaskEvent>       (관찰)
        ├── state: StateFlow<TaskState>   (관찰)
        ├── awaitOutcome(): TaskOutcome   (관찰, suspend)
        │
        ├── respond(...)                 (개입 — 응답 제공)
        └── requestCancellation()        (개입 — 취소 요청)
```

딱 하나 짚을 게 있는데, "Task 등록"이라고 하신 그 첫 단계(`startTask()`) 자체가 이미 실패할 수 있어요. handle을 받기도 전에요:
- 요구사항 자체가 지원 불가 → `IncompatibleRequirementException`
- 세션이 이미 다른 Task로 바쁨 → `IllegalStateException`
- 요청은 보냈는데 provider가 수락했는지 확인 못함 → `TaskStartUnconfirmedException`

그러니까 "handle을 얻었다"는 것 자체가 이미 하나의 확정된 사실이에요 — handle을 쥐고 있다면 최소한 "이 Task는 시작이 확정됐다"는 보장은 있는 거고, 그 이후로만 저 4갈래 outcome을 향해 흘러가는 거죠.

이제 어디로 갈까요 — `TaskState` 상태 전이(STARTING→RUNNING→...)? 아니면 `Unresolved`가 왜 필요한지, 아니면 `TaskEvent` 안의 `InteractionRequest` 흐름?

---

**Q. TaskState 상태 전이 흐름하고, TaskEvent들 소개 부터 바라**

**TaskState 전이**

```
STARTING
   │  (reportRunning — 첫 진행 신호가 옴)
   ▼
RUNNING ⇄ AWAITING_RESPONSE
   │           │  (pending interaction 열림/닫힘 — 승인·질문 요청 왔다갔다)
   │           │
   └─────┬─────┘
         ▼
   COMPLETED / FAILED / CANCELLED / UNRESOLVED   ← 여기 도달하면 isTerminal = true, 다시는 안 바뀜
```

RUNNING과 AWAITING_RESPONSE는 서로 왔다갔다할 수 있어요 (질문이 여러 번 나올 수도 있으니까). 근데 terminal 4갈래 중 하나에 도달하면 그걸로 끝 — "한 번만 확정된다"가 이 프로토콜 전체에서 반복되는 불변식이에요.

**TaskEvent 종류** (전부 `sealed`, 이 목록이 사실상 전부예요)

| 이벤트 | 의미 |
|---|---|
| `TaskStarted` | 실제로 진행이 시작됐다는 신호 |
| `MessageDelta` / `MessageCompleted` | 사람이 읽는 메시지 조각/완성 (답변, 설명, 코멘터리) |
| `ToolCallChanged` | 도구 호출 상태 변화 |
| `EffectChanged` | 외부 부작용(명령 실행 등)의 상태 변화 |
| `InteractionRequested` / `InteractionResolved` | 승인·질문이 열리고 닫힘 |
| `UsageChanged` | 토큰/비용 사용량 갱신 |
| `Warning` | 경고성 신호 (실패는 아님) |
| `ObservationGap` | **구독자가 못 받은 이벤트가 있었다는 사실 자체를 알려주는 이벤트** (뒤에서 다뤘던 그 gap) |
| `Terminal` (하위 4종) | `TaskCompleted`/`TaskFailed`/`TaskCancelled`/`TaskUnresolved` — 스트림의 마지막, 절대 유실 안 됨 |

여기서 뭐가 궁금하세요 — `ObservationGap`이 왜 별도 이벤트로 존재하는지 (일반 이벤트는 유실돼도 되는데 얘랑 Terminal만 특별대우받는 이유)? 아니면 `InteractionRequested`가 State를 AWAITING_RESPONSE로 바꾸는 메커니즘?

---

**Q. TaskState의 UNRESOLVED는 뭔지
TaskEvent들의 조금 더 자세한 설명 그러니까 왜 이 이벤트가 있는건지**

**UNRESOLVED**

이건 "실패도 아니고 취소도 아니고 완료도 아닌, **모른다**"는 상태예요. 정직한 무지 표현이라고 보시면 돼요. 언제 나오냐면:

- 취소를 요청했는데 provider가 확인해주기 전에 정리 시간(cleanup budget)이 끝나버림 → `CLEANUP_BOUND_EXCEEDED`
- 관찰 채널(스트림) 자체가 끊겨서 종료 증거를 아예 못 봄 → `OBSERVATION_LOST`
- 취소는 요청했지만 provider가 그걸 받아들였는지 확인 불가 → `CANCELLATION_UNCONFIRMED`
- 부분적인 증거는 있는데 넷 중 하나로 확정 짓기엔 부족함 → `PARTIAL_EVIDENCE`

**왜 필요하냐**면 — Codex/Gemini 같은 외부 프로세스는 파이프가 끊기거나, cancel 신호를 보냈는데 프로세스가 계속 뒤에서 파일을 쓰고 있을 수도 있거든요. 이때 억지로 `Cancelled`라고 우기면 거짓말이에요. 실제로 취소 후에도 몰래 효과가 발생하는 시나리오("거짓 취소 검출")도 이 상태 때문에 검사 가능한 거고요. 그래서 세션 레벨에서도 `Unresolved`가 뜨면 그 문맥은 "차단"돼요 — 뒤에 뭔 일이 남아있을지 모르니까 새 작업을 함부로 못 열게.

---

**TaskEvent, 왜 이렇게 나뉘나**

| 이벤트 | 왜 존재하나 |
|---|---|
| `TaskStarted` | "수락됨"과 "실제로 돌기 시작함"을 구분해야 하니까. handle을 받았다고 해서 바로 실행 중인 건 아니에요. |
| `MessageDelta`/`Completed` | 스트리밍 UX용. 전체 응답 기다리지 않고 타이핑되듯 보여주려면 조각(Delta)이 필요하고, "이 메시지는 이제 끝났다"는 별도 확정(Completed)이 필요해요. |
| `ToolCallChanged` | 앞서 말했듯 agent loop 자체는 블랙박스인데, 그 안에서 벌어지는 개별 도구 호출만은 관찰 가능하게 새어나오게 한 거예요. UI에 "지금 뭐 하는 중"을 보여줄 유일한 창구. |
| `EffectChanged` | ToolCall과 구분되는 이유가 있어요 — 이건 **실세계에 영향을 주는 것**(명령 실행, 파일 쓰기)만 가리켜요. 승인/권한 체계(`attemptGuardedEffect`)가 이 이벤트에 물려있어서, "권한이 필요한 일"과 "그냥 내부 도구 호출"을 분리한 거예요. |
| `InteractionRequested`/`Resolved` | 승인·질문 흐름을 폴링이 아니라 스트림으로 반응형으로 알리려고. Resolved는 실제 응답뿐 아니라 TASK_ENDED, CANCELLATION_REQUESTED, SUPERSEDED, PROVIDER_WITHDRAWN 같은 이유로도 "닫힘"이 될 수 있어요 — 왜 닫혔는지까지 정직하게 알려주는 거죠. |
| `UsageChanged` | 비용/토큰을 마지막에 한 번에 주면 롱런 작업에서 실시간 비용 추적이 불가능하니까 증분으로. |
| `Warning` | 실패는 아니지만 눈여겨볼 일 — "실패"로 뭉뚱그리면 정보 손실이라 별도 채널. |
| `ObservationGap` | **채널이 bounded + droppable이라서** 존재해요. 느린 구독자를 무한 버퍼링하거나 전체를 막으면 안 되니까 일반 이벤트는 유실 허용하는데, 그럼 "유실됐다는 사실"조차 모르면 안 되잖아요. 그래서 유실 자체를 알리는 전용 이벤트가 필요한 거예요. |
| `Terminal`(4종) | 유일하게 **유실 절대 금지**인 이벤트예요. 아무리 느린 구독자여도, 아무리 늦게 구독해도 반드시 마지막에 받아요. `TaskOutcome`이랑 정확히 같은 4갈래라서, 스트림으로 봐도 `awaitOutcome()`으로 봐도 같은 결론에 도달하게 맞춰놓은 거예요. |

정리하면: 이벤트 시스템의 설계 원칙이 "**일반 신호는 유실 가능, 대신 유실 사실은 알림 / 결론은 절대 유실 불가**"예요. 이게 `Multicast` 구현체가 가진 gap-on-overflow + terminal-always-last 규칙이랑 그대로 맞물려요.

더 파고들 곳 있나요 — `EffectChanged`랑 승인 체계(`InteractionRequest.Approval`)의 관계? 아니면 이제 다시 위로 올라가서 세 축(Harness/Session/Task) 밖에 있는 `CompatibilityReport`/선택 계약 쪽?

---

**Q. TaskState도..... TaskEvent로 같이 합쳐서 flatten할 수 있지 않았어?  분리해서 유리한 경우가 있었나?**

좋은 질문이에요. 실제로 겹치는 정보가 있는 건 맞아요 — `InteractionRequested`가 오면 `state`도 AWAITING_RESPONSE로 바뀌니까요. 근데 합치지 않은 데는 이유가 있어요, 정리해볼게요.

**핵심은 "전달 보장"이 근본적으로 다르다는 거예요.**

- `TaskEvent`는 `Flow` — 큐(queue) 기반이고, 일반 이벤트는 **유실 가능**해요 (기억하시죠, bounded + droppable + `ObservationGap`).
- `TaskState`는 `StateFlow` — **conflated 최신값**이에요. 큐가 아니라 "지금 값"을 들고 있는 컨테이너라서, 중간 변화는 놓칠 수 있어도 **최종적으로 항상 진짜 현재 상태로 수렴**해요. 구조적으로 유실이라는 개념 자체가 성립 안 해요.

만약 State를 Event 스트림에서 fold해서 파생시켰다면, 그 fold 로직이 `ObservationGap`을 만났을 때 뭘 해야 하죠? "AWAITING_RESPONSE → RUNNING 전이 이벤트를 놓쳤다"고 치면, 클라이언트는 실제로는 RUNNING인데 계속 AWAITING_RESPONSE라고 착각하게 돼요. 근데 지금 설계에서는 그럴 일이 아예 없어요 — `state.value`를 읽으면 항상 진짜예요, event를 몇 개 놓쳤든 상관없이.

**그리고 실제 쓰임새도 달라요.** conformance 테스트 코드 보면 `task.state.value`, `task.pendingInteractions.value.isNotEmpty()`처럼 **스트림 구독 없이 그냥 동기적으로 스냅샷 읽기**를 엄청 많이 해요. "지금 끝났나?"를 물을 때마다 Flow를 collect하고 fold하는 건 과한 오버헤드고 번거로워요.

이거 사실 아까 봤던 다른 패턴이랑 똑같은 원리예요 — `awaitOutcome()`(직접 스냅샷)과 `Terminal` 이벤트(스트림 안의 서사)가 둘 다 존재하는 것도 같은 이유였잖아요. "지금 결론이 뭐야"를 묻는 접근과 "무슨 일이 있었는지 서사로 보여줘"를 묻는 접근이 근본적으로 다른 소비 패턴이라서 분리한 거고, State/Event도 같은 원리를 한 단계 더 세밀하게 적용한 거예요.

**합쳤다면 어떻게 됐을까** — 모든 상태 전이 이벤트를 Terminal처럼 "유실 불가"로 승격시켜야 했을 거예요. 근데 그러면 결국 "유실 가능한 이벤트군"과 "유실 불가 이벤트군" 두 클래스가 필요해지고, 그게 사실상 지금의 Flow/StateFlow 분리랑 똑같은 결과예요. 그냥 이름만 안 나눴을 뿐이지.

이 방향 더 팔까요, 아니면 이제 Task를 벗어나서 다른 쪽(선택 계약 — 승인/영속성/구조화 산출물)으로 넘어갈까요?

---

**Q. 흠. 결국에 이거네
Ideal하게는 TaskEvent만 있으면 되지만, 현실적으로 Event는 droppable하다.
따라서 TaskState에 대해선 적어도 droppable하면 안되므로 우리가 event sourcing한 특정 "최종 상태"를 직접 하네스가 제공하겠다.**

정확히 그거예요. 한 문장으로 완벽하게 정리하셨네요.

이거 사실 **event sourcing + materialized view** 패턴이랑 똑같은 구조예요. CQRS에서 쓰는 표현 빌리면:
- `TaskEvent` 스트림 = 원본 이벤트 로그 (근데 우리 경우엔 그 로그 자체가 부분적으로 손실 허용됨)
- `TaskState`(그리고 `awaitOutcome()`) = 하네스가 **자기 자신은 절대 이벤트를 놓치지 않는 내부 상태**를 들고 있다가, 그걸 미리 projection해서 넘겨주는 read model

핵심은 "이벤트 재구성"의 책임을 소비자에게 절대 안 넘긴다는 거예요. 소비자가 event sourcing을 직접 해야 한다면 소비자 쪽의 유실까지 소비자가 감당해야 하는데, 그건 부당한 요구잖아요 — 소비자는 느릴 수도 있고 늦게 구독할 수도 있는데. 그래서 "신뢰 가능한 최종 진실"은 하네스가 내부적으로(유실 없이) 계산해서 별도 채널로 직접 쏴주는 거고, `TaskEvent`는 순수하게 "부가적인 서사/디버깅/UX용 부산물"로 격하된 거죠.

이 관점으로 보면 재밌는 게, `ObservationGap`도 다시 보이는데 — 그게 event log 쪽에서 "너 로그에 구멍 났어"라고 알려주는 거고, 그 구멍이 나도 상관없는 이유가 바로 "진짜 중요한 진실은 어차피 TaskState/Outcome이 별도로 보장하니까"인 거예요. 두 메커니즘이 서로를 정당화하는 구조네요.

다음은 어디로 갈까요 — 이 "event log는 손실 허용, 진실은 별도 보장" 원칙이 usage(`AgentUsage`)에도 똑같이 적용되는지? 아니면 이제 Task 밖으로 나가서 선택 계약(승인/영속성) 쪽?

---

**Q. 이제 Task 밖으로 나가자**

좋아요, Task를 벗어나면 이제 "코어 3단(Harness/Session/Task)은 모든 구현체가 반드시 지켜야 하는 필수 계약"이고, 그 위에 **선택적으로 얹히는 계약들**이 있어요. 이것들은 지원 안 해도 되지만, 지원한다고 선언했으면 정확히 지켜야 해요.

목록만 먼저 펼쳐볼게요 (BFS니까 아직 깊이 안 들어감):

| 선택 계약 | 뭘 다루나 |
|---|---|
| **Caller 승인** (approval) | 위험한 effect를 실행하기 전에 승인받는 흐름 |
| **질문** (questions) | Task가 진행 중 사용자에게 되묻는 흐름 |
| **영속성** (persistence) | 세션을 끊었다가 나중에 이어서 재개(`reopenSession`) |
| **권한·작업 자원** | 승인 범위(scope)를 세션 단위로 넓게 부여해서 반복 승인 안 받기 |
| **구조화 산출물** | JSON schema 검증된 output |
| **진단** (diagnostics) | 의미(semantic) 이벤트와 별개인 저수준 진단 채널 |

그리고 이 모든 걸 **"이 harness가 뭘 지원하는지"** 선언하는 게 아까 얘기했던 `CompatibilityReport`예요 — 근데 이건 단순히 `Set<Capability>`가 아니라, 구체적인 요구사항 케이스별로 COMPATIBLE/INCOMPATIBLE/UNCONFIRMED를 답하는 형태였죠.

여기서 흥미로운 설계 원칙 하나 미리 힌트만 드리면 — "한 선택 기능을 지원한다고 다른 보장까지 자동으로 딸려오지 않는다"는 규칙이 있어요. 예: 영속성을 지원한다고 해서 재개된 세션이 exactly-once를 보장하는 건 아니에요.

어디부터 열어볼까요 — 승인/질문(중간 개입 흐름)? 영속성(재개)? 아니면 이 "기능 하나 지원 ≠ 다른 보장 자동 포함" 원칙 자체?

---

**Q. 승인 질문 부터**

좋아요, 이것도 뼈대부터 봅시다.

**흐름의 모양** (Task가 뭔가 위험하거나 불확실한 걸 할 때 멈추고 물어보는 구조):

```
Task 진행 중
   │
   ▼
InteractionRequest 열림  ──── TaskEvent.InteractionRequested 로 통지, state → AWAITING_RESPONSE
   │  (두 종류: Approval / Question)
   ▼
task.respond(id, InteractionResponse)  ← 사용자가 답
   │
   ▼
InteractionResolution
   ├── Responded          (정상적으로 응답이 반영됨)
   └── Cleared(reason)    (응답 없이 그냥 닫힘 — 이유가 다섯 가지)
```

**요청 종류 둘:**
- `InteractionRequest.Approval` — "이 effect 실행해도 돼?" (한 번만 승인 vs 세션 전체에 대해 승인 범위 부여 가능)
- `InteractionRequest.Question` — "이거 뭐야?" 같은 자유형 질문

**응답 종류 둘:**
- `InteractionResponse.Approval` — APPROVE_ONCE / APPROVE_FOR_SESSION / DECLINE / CANCEL
- `InteractionResponse.Answer` — 텍스트

**Cleared되는(응답 없이 닫히는) 이유 다섯 가지가 있는데** — 이게 좀 흥미로워요: `RESPONSE_UNCONFIRMED`(응답은 보냈는데 전달 확인 못함), `TASK_ENDED`, `CANCELLATION_REQUESTED`, `SUPERSEDED`(다른 요청이 대체함), `PROVIDER_WITHDRAWN`(provider가 스스로 철회). 즉 "질문에 답 안 하면 그냥 방치된다"가 아니라, 왜 닫혔는지가 항상 명시적으로 기록돼요.

그리고 `pendingInteractions`도 아까 State랑 똑같은 패턴이에요 — event 스트림에서 파생시키지 않고 직접 materialized snapshot(`StateFlow`)으로 제공해요. 같은 이유죠.

어디부터 들어갈까요 — 세션 단위 승인 범위(`APPROVE_FOR_SESSION` + scope)가 어떻게 작동하는지? 아니면 저 다섯 가지 Cleared 이유가 왜 필요한지?

---

**Q. 세션 단위 승인 범위**

구조는 이래요:

```
InteractionRequest.Approval(sessionGrant: SessionApprovalGrant?)
        │
        ▼  사용자가 APPROVE_FOR_SESSION 선택
        │
   session.grant(scopeId, targets)   ← Session 객체에 저장
        │
        ▼  같은 세션 안에서 이후 비슷한 effect 시도할 때마다
   session.hasGrant(scopeId, target) 먼저 확인
        │
   있으면 → 다시 안 물어보고 바로 적용
   없으면 → 다시 InteractionRequest 열림
```

**왜 필요하냐** — 매번 파일 하나 쓸 때마다, 명령어 하나 실행할 때마다 승인을 새로 받아야 한다면 실용성이 없죠. "이 작업 범위(scope) 안에서는 앞으로 계속 이 종류의 effect를 허용해줘"를 한 번만 말하고 나머지는 자동 통과시키는 게 이 메커니즘이에요.

**단위가 (scopeId, target) 쌍이라는 게 핵심**이에요. "전체 승인"이 아니라 특정 범위+대상 조합에 국한돼요. 그래서 "같은 scopeId라도 범위가 확장되진 않는다"는 규칙이 있어요 — scopeId A에 대해 승인했다고 그 scopeId 밑에 새로운 target이 나타나면 자동으로 커버되는 게 아니라, 명시적으로 그 target까지 승인 범위에 들어와야 해요.

**그리고 이게 Session에 붙어있다는 게 중요해요, Task가 아니라.** 그러니까:
- 같은 세션 안 다음 Task로 넘어가도 grant는 유지돼요 (세션 = 문맥 단위니까 자연스러움)
- **다른 세션으로는 절대 전파 안 돼요** — "다른 session 전파를 허용하지 않는다"는 규칙이 명시적으로 있어요. 세션 = 승인 범위의 경계이기도 한 거죠.

**선택 안의 선택**도 있는데 — 어떤 provider는 애초에 "이 세션 동안 계속 기억해줘"라는 개념 자체를 지원 못할 수도 있어요(SDK 한계로). 그런 경우엔 `APPROVE_FOR_SESSION`을 아예 지원 안 해도 되지만, `APPROVE_ONCE`(단발 승인)은 무조건 지켜야 해요. 세션 범위 승인은 "있으면 좋은 것"이지 필수가 아니라는 거죠.

솔직히 말하면, 이 grant가 `PersistentSessions`로 세션을 reopen했을 때도 살아남는지는 이번 세션에서 확실히 확인한 적이 없어서 — 그 교차점은 정확히 말씀 못 드리겠네요. 궁금하시면 다음에 코드 확인해볼게요.

계속 갈까요, 아니면 이제 영속성(persistence) 쪽으로?

---

**Q. Persistence로 가자**

구조부터 봅시다.

```
AgentHarness            (기본 계약 — 여기엔 없음)
    │
    ▼ 구현체가 선택적으로
PersistentSessions       (추가 interface)
    │
    └── reopenSession(ref: PersistentSessionRef, spec): AgentSession
```

**제일 눈에 띄는 설계 포인트** — 이게 런타임 플래그가 아니라 **타입 레벨 opt-in**이에요. `harness.supportsPersistence = true/false` 같은 boolean이 아니라, 그 harness 클래스가 `PersistentSessions` 인터페이스를 **구현하느냐 마느냐**로 결정돼요. 그래서 `harness is PersistentSessions` 체크 한 번이면 컴파일 타임/타입 체크 수준으로 "이건 재개 가능한 harness다"를 알 수 있어요. 문서 표현으로는 "지원하지 않으면 구현하지 않으며"라고 명시돼있어요 — 지원 안 하는 구현체엔 아예 이 메서드 자체가 존재하지 않게.

**`createSession()`이 만든 세션은 `persistentRef: PersistentSessionRef?`를 가져요** (nullable — persistence 지원 harness에서만 채워짐). 이 ref를 나중에 `reopenSession(ref, spec)`에 넣어서 세션을 재개해요.

**흥미로운 규칙 하나**: reopen했을 때 돌려주는 ref가 **호출자가 넣은 원본 문자열 그대로가 아니라 저장소가 결정한 정규화된(canonical) id**예요. 예를 들어 대소문자나 형식이 다른 같은 ID를 넣어도, 저장소가 "진짜 정체성"을 판단해서 그 canonical 형태로 돌려줘요. 그리고 모르는 ID를 넣으면 그냥 `IncompatibleRequirementException`으로 확실하게 거절돼요 — 이건 "모른다"가 아니라 "이건 안 된다"로 확정.

그리고 아까 얘기했던 원칙 — **"한 기능 지원이 다른 보장을 자동으로 안 딸고 온다"**가 여기서 직접 예시로 나와요: 영속성 지원한다고 해서 "정확히 한 번(exactly-once)" 실행이나 이력 조회가 자동으로 보장되는 게 아니에요. 그건 별도 계약으로 따로 검증해야 해요.

파고들 곳 고르실래요 — (1) 타입 레벨 opt-in을 왜 런타임 플래그 대신 택했는지, (2) canonical id 정규화가 왜 필요한지, (3) harness 재생성/process 재시작까지 버텨야 하는 더 강한 영속성 케이스?

---

**Q. canonical id 정규화**

이건 "누가 정체성(identity)의 최종 권위를 갖느냐" 문제예요.

**호출자가 들고 있는 ref는 결국 하나의 요청/힌트일 뿐, 진짜 정체성이 아니에요.** 예를 들어 대소문자가 다르거나, 포맷이 살짝 다르거나, 오래된 형식의 ID를 들고 올 수도 있어요. 근데 실제로 어떤 세션을 가리키는지는 **저장소(provider+namespace+id 조합)만 알아요**. 그래서 reopen할 때 캐논icalization이 필요한 거예요:

1. **같은 raw id라도 namespace가 다르면 완전히 다른 세션이에요.** 참조 구현 보면 저장 key가 `"provider:namespace:id"` 세 부분 조합이거든요. 호출자가 "id만 같으면 같은 거겠지"라고 착각하면 안 되고, 프로토콜이 그 착각을 명시적으로 막아요 — "동일 ID 문자열의 다른 저장 namespace를 거절".

2. **모르는 ID면 명확히 거절**(`IncompatibleRequirementException`)이지 애매하게 새 세션을 만들어버리거나 하지 않아요. 정체성 위조나 사고성 충돌을 막는 거죠.

3. **되돌려주는 ref도 호출자가 넣은 그 문자열이 아니라 저장소가 인정하는 canonical 형태**예요. 이게 왜 중요하냐면 — 만약 캐논화 안 하고 호출자가 넣은 문자열을 그대로 돌려줬다면, 같은 실제 세션이 다른 문자열 표현으로 여러 번 "다른 세션"인 것처럼 보일 수 있어요. 나중에 감사 로그 찍거나, 세션 dedup하거나, "이 세션이 그 세션이랑 같은 거야?"를 비교할 때 전부 꼬여요.

**더 크게 보면 이건 이 프로토콜 전체에 반복되는 원칙의 또 다른 사례예요** — "진실은 harness/storage가 결정하고, 호출자의 입력은 어디까지나 요청일 뿐 신뢰의 근거가 아니다." `CompatibilityReport`가 호출자 짐작이 아니라 harness가 답하는 것도 같은 원리고, `TaskState`가 호출자의 이벤트 재구성이 아니라 harness가 직접 보장하는 것도 같은 원리였잖아요. 여기선 그 원리가 "세션 정체성"이라는 영역에 적용된 거예요.

이제 (1) harness 재생성/process 재시작까지 버티는 더 강한 영속성 케이스로 갈까요, 아니면 (2) 다시 위로 올라가서 지금까지 본 개념 전체를 한 장으로 정리해볼까요?

