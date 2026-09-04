# Distribution

배포는 [개정된 Port](protocol-reference.md)를 제공하는 구현 수단이다. 비즈니스 코드는 vendor SDK나 transport에 의존하지 않고, 구성 경계가 adapter와 필요한 선택 계약을 고른다.

## 배포와 계약의 관계

애플리케이션은 AgentHarness/AgentSession/AgentTask와 그 요구·outcome·output을 사용한다. SDK·provider thread·process 경로는 adapter 구성에 둔다. SDK API를 숨기는 것이 실행 환경의 설치·인증을 없앤다는 뜻은 아니다.

단일 bundle은 현재 제공하는 배포 편의다. 모든 하네스가 process host를 포함하거나 같은 언어·artifact 구조를 가져야 한다는 AHP의 필수 계약은 아니다. 직접 연결하는 라이브러리와 원격 서비스도 같은 Port의 구현 대상이다.

영속 저장소, 작업 자원, 권한 집행, 진단 등 선택 계약의 제공 범위는 artifact 이름만으로 추론하지 않는다. 지원 선언·필수 요구 검증·운영 구성이 일치해야 한다.

## 현재 구현의 좌표와 factory

소스의 factory는 새 Port로 전환했다. artifact는 이번 작업에서 발행하지 않았으며 기존 `0.1.0` 다운로드가 새 AgentTask API를 제공한다고 주장하지 않는다.

다음 예시는 이 저장소에서 `./gradlew.bat publishToMavenLocal`을 실행해 로컬 Maven 저장소에 설치한 artifact를 소비한다. 공개 Maven repository에서 다운로드할 수 있다는 안내가 아니다. publicationGroup/publicationVersion을 변경했다면 소비 좌표도 실제 로컬 발행값에 맞춘다. 현재 문서 작업에서는 발행을 실행하지 않았다.

```kotlin
repositories { mavenLocal() }
dependencies {
    implementation("io.github.joohyung-park:harness-bundle:0.1.0")
}
```

현재 `dev.harnessprotocol.Harnesses.create(provider)`는 Codex/Gemini adapter를 제공한다. Koog는 구성에 필요한 executor·model을 `Harnesses.koog(executorFactory, model, tools)`로 받는다. bundle에 `harness-koog`와 `harness-runtime`을 포함했다. process-bridge는 두 process adapter의 구현 의존성이며 Koog의 필수 기반이 아니다. adapter-testkit·native-integration은 test-only다.

현재 소스의 소비 예제는 `./gradlew.bat -p samples/basic -PuseProjectSource compileKotlin`로 발행 없이 검증할 수 있다. 이 옵션은 명시적 composite-build dependency substitution을 사용한다. 새 factory를 소비하는 컴파일이 통과했으며, 발행된 POM/JAR 검증과는 구별한다.

## 현재 runtime 준비

| 경로 | 현재 필요한 환경 |
|---|---|
| Codex | Python 3.10+, requirements에 고정된 openai-codex 0.147.0 및 포함된 runtime. client 세부는 [Provider mapping](provider-mapping.md) |
| Gemini CLI | Node와 공식 SDK build entrypoint. 고정 source revision과 내부 호환 코드·빌드 제한은 [실제 adapter 검증](native-port-validation.md) 참조 |
| Koog | `ai.koog:agents-core:1.2.0`, caller가 구성한 PromptExecutor·LLModel·ToolRegistry. root Kotlin 2.3.10, JVM target 21/JDK 25 |

현재 JAR은 bridge script와 requirements를 포함하고 factory가 script를 추출한다. Python/Node 실행 파일과 provider 인증은 운영 환경이 제공한다. 현재 실행 파일 override는 HARNESS_CODEX_PYTHON, HARNESS_GEMINI_NODE이고 Gemini SDK 경로는 GEMINI_CLI_SDK_MODULE로 지정할 수 있다.

모델 인증 정보를 artifact에 포함하지 않는다. 운영 환경을 self-contained하게 제공하려면 별도의 runner image·sidecar·runtime packaging 등 배포 구성이 필요하다. 이것은 각 adapter의 제공 방식이다.

## 현재 발행 명령

다음은 기존 build의 명령 안내이며 이번 문서 정리에서 발행을 실행한 것은 아니다.

```powershell
./gradlew.bat publishToMavenLocal
./gradlew.bat publishAllPublicationsToBuildRepository
./gradlew.bat bundleForMavenCentral -PpublicationVersion=1.0.0
```

publicationGroup/publicationVersion을 지정할 수 있다. 공개 발행에는 해당 repository의 namespace·서명·POM·의존성·라이선스·인증 구성을 확인한다. bundle 생성 성공과 실제 public repository 발행 성공을 구별한다. 생성 경로는 Gradle task의 실제 출력 위치를 따른다.

## 개정 계약의 배포 전환

공개 Port·KDoc, 세 adapter의 factory·bundle 구성, source 소비 예제 컴파일은 완료했다. 실제 artifact는 발행하지 않았으며 아래에서 migration 예제 보강과 발행된 artifact 검증은 남아 있다.

1. 공개 타입과 KDoc은 새 의미로 전환했다. 발행 버전과 소비자의 소스·의미 호환성 변경을 정리한다.
2. 기본 영속 Session을 사용하던 소비자가 새 영속성 요구를 명시하도록 migration 예제를 제공한다.
3. 예외 중심 결과 회수를 TaskOutcome으로, finalMessage 사용을 TaskOutput으로 옮기는 소비 예제를 제공한다.
4. 선택 기능의 지원·거절과 runtime 준비는 provider별로 명시했고 세 provider를 factory·의존성에 반영했다. 지원 범위가 바뀌면 같은 검증으로 갱신한다.
5. [samples/basic](../samples/basic)을 새 artifact만으로 빌드하여 transitive dependency와 공개 API를 검증한다.
6. README·문서의 구현 상태를 실연동 결과와 일치시킨다.

새 문서의 존재나 기존 테스트 통과만으로 새 계약을 발행하지 않는다. 전체 완료 조건은 [전환 계획](port-revision-plan.md)을 따른다.
