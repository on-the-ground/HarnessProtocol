# 새 Port의 실제 adapter 검증

기준일: 2026-09-04. [설계 선언](../AHP_CHARTER.md)과 공개 계약을 기준으로 세 실제 제공 경계를 연결하고 기본 native 검증을 완료했다. 임시 참조 하네스는 제거했으며 현재 실행 주체는 실제 adapter다.

## 구현과 검증의 경계

`CodexHarness`, `GeminiCliHarness`, `KoogHarness`는 모두 `dev.harnessprotocol.AgentHarness`를 구현한다. `harness-bundle`의 새 `Harnesses`가 세 경로를 구성한다. 공통 task 상태·관찰 구현은 선택적인 내부 모듈 `harness-runtime`에 두었으며, Koog는 process bridge에 의존하지 않는다. 공개 Port는 변경하지 않았다.

`harness-native-integration`은 실제 adapter와 실제 runtime을 구동한다. 결정적인 진행을 만들기 위해 모델 응답만 통제한다. Task state/outcome을 검사 코드에서 직접 설정하지 않는다.

| 구현 | 실제 실행 경계 | 통제한 경계 |
|---|---|---|
| Codex | Python `openai-codex 0.147.0`의 CodexClient와 포함된 `codex.exe app-server` | localhost Responses HTTP/SSE |
| Gemini CLI | 공식 저장소 SDK 및 core의 session, 모델 요청, 도구 scheduler | localhost generateContent HTTP/SSE |
| Koog | `ai.koog:agents-core:1.2.0`, 실제 GraphAIAgent·singleRunStrategy·ToolRegistry | PromptExecutor의 모델 응답 |

실모델 품질·인증·외부 서비스 가용성을 검사한 결과는 아니다. 모델 요청의 실제 내용과 실제 파일 효과를 증거로 사용한다.

## 실제 연결에서 확인한 문제와 수정

1. **Gemini의 지시 누락.** `agentOptions.instructions`가 맞아도 native 모델 요청에는 지시가 없었다. 해당 SDK의 `memoryContextManager`가 `Config.userMemory`를 우회하며, 공개 dynamic instructions 경로도 같은 문제를 가진다. Host가 session별 system-instruction memory 경로를 연결하도록 보완했다. 사용자 입력에 지시를 덧붙이지 않는다. 이 호환 코드는 TS-private `session.config/client` 구조에 의존하며 구조가 달라지면 명시적으로 실패한다. 모델 요청 검사가 이 버전 의존성을 검출한다.
2. **Gemini 진단 출력의 통신 오염.** 실제 SDK의 `Experiments loaded` 로그가 stdout에 나와 NDJSON 해석을 깨뜨렸다. SDK import 전에 Console을 stderr로 연결했다. 의미 이벤트 parser가 임의의 텍스트를 무시하도록 완화하지 않았다.
3. **Codex 자식 process와 저장 파일 잠금.** 작업 판정은 성공해도 Python host만 종료하면 App Server가 살아 있어 테스트 임시 저장소를 지울 수 없었다. stdin EOF로 native client 정리를 유도하고 소유 process tree를 정리하도록 고쳤다.
4. **Koog의 실패 후 문맥·부분 산출물.** 성공한 graph 결과만 저장하면 앞선 모델 출력과 실패 직전 문맥을 잃는다. 실제 LLM hook에서 부분 산출물을 확보하고, graph 정리 전에 native history를 회수한다. 실패한 다중 호출 다음 작업의 모델 입력으로 문맥을 검사한다.
5. **실제 효과와 handle 종결의 분리.** 비협조적인 Koog 도구가 유예 뒤에도 실제 파일을 쓰도록 실행했다. `release → Unresolved → 파일 쓰기`를 확인하며, 이미 확정한 outcome이 바뀌지 않고 같은 session의 재시작이 차단되는지 검사한다.
6. **Codex 재개의 desired configuration 누락.** `thread/resume`에 새 developerInstructions를 투영해도 실제 모델 요청에는 반영되지 않았다. 이 버전에서는 기존 설정과 다른 재개를 사전에 거절한다. 같은 설정의 native 이력 재개는 유지하며, Gemini에서는 변경한 지시의 실제 반영을 검사한다. 설정을 전달했다는 사실만으로 수락하지 않는다.

## 검사 범위

공통 검사는 provider wire를 모르며, provider별 구성과 모델 경계가 실제 native 동작을 유도한다.

- observer 없는 완료, state·pending·output 회수, 실제 입력·지시 전달
- 동일 session의 이전 입력·출력 연결과 새 session의 격리
- 미지원 출력 요구의 사전 거절과 native 모델 미호출
- 같은 session의 중첩 거절, waiter 하나의 취소와 실행 수명 분리
- 진행 중 close의 실제 경과 시간과 waiter 정리. 현재 검사는 Windows scheduling 여유 2초를 두며 정확한 상한 경계 경쟁 검증은 추가 gate다.
- 의미·진단 observer의 독립 종료, terminal 보존, 늦은 구독의 종료
- 명시적 취소의 native 종결 확인과 이후 같은 session 재사용
- Codex/Gemini: harness 재생성 후 실제 문맥 복원, desired configuration을 지원하면 반영하고 지원하지 않으면 사전 거절, 다른 namespace 거절
- Koog: 비협조적 도구의 실제 후속 효과, 다중 호출 실패의 부분 출력·문맥 보존

실행 수·결과는 아래 실행 결과와 기계 판독 기록에 남겼다. 기존 48개 suite를 세 provider에서 모두 통과한 것으로 환산하지 않는다.

## 기존 공통 시나리오에 환류할 것

| 기존 검사에서 부족한 판정 | 실제 경계에서 필요한 판정 |
|---|---|
| 예상 답변을 `reportCompletion`에 넣고 문맥 연속성을 판단 | 후속 native 모델 입력에 실제 이전 입력·출력이 있는지 검사. 지시도 옵션 객체가 아닌 실제 입력에서 확인 |
| close/release 이후부터 timeout 적용 | 정리 호출 자체의 경과 시간을 공개 CleanupBudget과 비교하고 native 자원 정리까지 확인 |
| 비협조적 작업을 fixture의 집합에서 제거 | handle 종결 뒤 실제 도구 효과가 발생해도 거짓 Cancelled를 반환하지 않는지 확인 |
| 늦은 구독의 첫 이벤트를 terminal로 고정 | replay 순서를 추가로 강제하지 않고 terminal 보존과 flow 종료를 확인 |
| baseline에 질문·승인·구조화 결과·skills 등을 일률적으로 요구 | 독립 작성한 profile/case에 따라 지원 시 이행, 미지원 시 사전 거절을 검사 |
| 모든 입력 오류를 동일한 실패로 취급 | 실제 요청 전에 거절한 경우와 요청 수락 확인을 잃은 경우를 구별 |

기존 `HarnessFixture`는 이 목적을 상당 부분 이미 표현한다. 따라서 Port 의미를 낮추는 대신 검사에서 `RequirementCase`, 실제 context/effect observation, response/start control을 사용해야 한다. 48개 시나리오 정의와 공통 지원 코드는 `harness-conformance/src/testFixtures`로 옮겼으며 `testFixtures(project(":harness-conformance"))`로 소비할 수 있다. 연결되지 않은 정의는 컴파일 대상이지 실행·통과 결과가 아니다.

참조 하네스·session·task·control·fixture·실행 subclass와 전용 Multicast, 총 8개 파일을 제거했다. 실제 Port 구현을 가장하는 대체 엔진을 다시 넣지 않는다. `reportUsageSnapshot`처럼 모델 경계만으로 임의의 native 누적값을 만들 수 없는 검사는 실제 SDK 변환 경계의 별도 검사로 분리해야 한다. 내부 상태를 직접 설정해서 적합성 점수를 채우지 않는다.

실제 SDK 변환·통신 검사에 필요한 RecordingBridge·stub server와 모델 응답 제어, 이전 Koog 실험의 실제 runtime 코드는 보존했다. 이들은 제거한 임시 Port 구현과 역할이 다르다.

## 지원 범위와 남은 gate

| 보장 | Codex | Gemini CLI | Koog의 현재 구성 |
|---|---|---|---|
| 기본 Task·live 문맥·결과 회수 | 구현 | 구현 | 구현 |
| 진단 | 제공 | 제공 | 제공 |
| caller 승인 | native handler 중재 | 요구 거절 | bare ToolRegistry 구성은 요구 거절 |
| 지속 승인 | 명시 범위가 없는 native 요청에서는 선택지 생략 | 미지원 | 미지원 |
| 영속 재개 | namespace 구성 시 같은 애플리케이션 process 내 harness 재생성, 기존과 같은 설정만 수락 | 같은 process 범위, 변경한 지시 반영 | 미지원 |
| 애플리케이션 process 재시작·다중 접근 조정 | 요구 거절 | 요구 거절 | 요구 거절 |
| 작업 공간·skills | native 투영, 추가 실증 필요 | native 투영, 추가 실증 필요 | 미지원 |
| filesystem/network 집행 | 지원 조합만 수락, 추가 실제 효과 검사 필요 | 요구 거절 | 요구 거절 |
| typed 질문·구조화 출력 | 현재 구성은 요구 거절 | 현재 구성은 요구 거절 | 현재 구성은 요구 거절 |

미지원은 공통 추상의 삭제가 아니다. Koog 실험의 승인·저장소 등 선택 구성을 production 경계로 가져오는 작업도 남아 있다. 미확정 문맥의 차단은 process adapter의 제공 종류·저장 namespace·session 참조별로 같은 애플리케이션 process에서 유지한다. 디스크에 차단을 영속화하거나 안전한 복구를 수행하지 않으므로 애플리케이션 process 재시작을 보장하지 않는다.

**전체 적합성 인증은 아직 아니다.** 시작·응답 acknowledgement 유실, 실제 승인 범위 안/밖 효과, 실제 native 고부하 observer overflow, 사용량의 reset/누락, native skill 활성화, 권한 집행, 영속 문맥의 경쟁·복구는 추가 gate다. 기존 mapper·bridge·bundle 회귀는 현재 Port와 실제 adapter로 이전했고 legacy 실행 경로는 제거했다. 이 SDK 경계 회귀를 모든 native 권한·효과 조건의 실증으로 확대하지 않는다. 이전 범위와 의미 변경은 [회귀 검토](legacy-port-migration.md)에 기록했다.

## 재현 환경

Gemini SDK source: [google-gemini/gemini-cli, 87a9c71d57a4ec56c00f3ff628970fea8291d812](https://github.com/google-gemini/gemini-cli/tree/87a9c71d57a4ec56c00f3ff628970fea8291d812), package version `0.60.0-nightly.20260901.g0bd1d4397`. `_stage/gemini-cli-runtime`에 checkout했다. SDK를 임의의 다른 배포본으로 바꿔도 같은 의미를 지킨다고 가정하지 않는다.

공식 workspace npm 설치 후 `npm run generate`, core와 sdk의 TypeScript 출력 및 resource 복사를 수행했다. 이 Windows 환경에서는 공식 전체 typecheck 빌드가 Node heap 상한으로 실패하여 `tsc --build --noCheck`로 runtime을 출력했다. SDK import와 실제 통합은 별도로 검사했다. `@types/command-exists@1.2.3`를 no-save로 추가했으므로 해당 node_modules를 원본 lockfile 그대로의 재현으로 주장하지 않는다. 이 준비 방식은 upstream 전체 빌드 통과 증거가 아니다.

```powershell
$env:JAVA_HOME = 'C:/Users/gcjoo/.jdks/corretto-25.0.3'
$env:HARNESS_CODEX_PYTHON = "$PWD/.venv/Scripts/python.exe"
$env:GEMINI_CLI_SDK_MODULE = "$PWD/_stage/gemini-cli-runtime/packages/sdk/dist/index.js"
./gradlew.bat --offline test :harness-conformance:testFixturesClasses hostTests -PnativeHarnessTests -PstrictHostTests
```

Native 검사는 `-PnativeHarnessTests`로 켠다. 켠 뒤 runtime이 없으면 실패하며 provider별로 조용히 skip하지 않는다. Codex/Gemini의 model endpoint와 홈 디렉터리는 테스트용으로 분리한다. 실제 모델 credential이 필요하지 않다. 새 모듈의 Koog 의존성에 맞춰 root Kotlin compiler는 2.3.10으로 갱신했고 JVM target 21/JDK toolchain 25를 유지했다.

## 실행 결과

legacy 이전과 임시 참조 구현 제거 뒤 `test :harness-conformance:testFixturesClasses hostTests -PnativeHarnessTests -PstrictHostTests`는 **BUILD SUCCESSFUL**이다. [기계 판독 기록](../harness-native-integration/evidence/verification.json)에 현재 suite별 수를 보관했다.

| 검사 | 통과 | 실패 / skip |
|---|---:|---|
| 새 native 통합 | 25: Codex 8, Gemini 8, Koog 기본 7 + 도구 2 | 0 / 0 |
| 현재 Port adapter·bridge·bundle 회귀 | 71 | 0 / 0 |
| PublicModelTest | 9 | 0 / 0 |
| 재사용 시나리오 정의 | 실행·통과 0, 정의 48개 컴파일 확인 | 임시 실행 subclass 제거 |
| Python host | 15 | 실패 0, 기존 Pydantic 경고 4 |
| Node host | 5 | 0 / 0 |

현재 JVM 결과는 총 **105개**, host 결과는 **20개**다. 변경되지 않은 native·기존 회귀·값 타입 suite는 Gradle up-to-date 결과를 재사용했고 host 20개는 재실행했다. 참조 하네스가 포함됐던 153개 집계는 [제거 전 기록](../harness-native-integration/evidence/before-reference-removal.json)에 분리했으며 현재 통과 수로 사용하지 않는다. 이전 Koog 독립 실험 18개도 현재 집계에서 제외한다.

`./gradlew.bat --offline -p samples/basic -PuseProjectSource compileKotlin`도 통과했다. 미발행 소스를 composite build로 소비한 결과이며 artifact 발행은 수행하지 않았다. README와 docs의 상대 파일 링크 검사에서 누락은 0개였다.
