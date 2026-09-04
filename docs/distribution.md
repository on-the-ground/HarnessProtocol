# Distribution

배포는 [개정된 Port](protocol-reference.md)를 제공하는 구현 수단이다. 비즈니스 코드는 vendor SDK나 transport에 의존하지 않고, 구성 경계가 adapter와 필요한 선택 계약을 고른다.

## 배포와 계약의 관계

애플리케이션은 AgentHarness/AgentSession/AgentTask와 그 요구·outcome·output을 사용한다. SDK·provider thread·process 경로는 adapter 구성에 둔다. SDK API를 숨기는 것이 실행 환경의 설치·인증을 없앤다는 뜻은 아니다.

단일 bundle은 현재 제공하는 배포 편의다. 모든 하네스가 process host를 포함하거나 같은 언어·artifact 구조를 가져야 한다는 AHP의 필수 계약은 아니다. 직접 연결하는 라이브러리와 원격 서비스도 같은 Port의 구현 대상이다.

영속 저장소, 작업 자원, 권한 집행, 진단 등 선택 계약의 제공 범위는 artifact 이름만으로 추론하지 않는다. 지원 선언·필수 요구 검증·운영 구성이 일치해야 한다.

## 현재 구현의 좌표와 factory

아래 정보는 전환 전 구현에 관한 것이다. `0.1.0` 좌표에서 새 AgentTask API를 제공한다고 주장하지 않는다.

다음 예시는 이 저장소에서 `./gradlew.bat publishToMavenLocal`을 실행해 로컬 Maven 저장소에 설치한 artifact를 소비한다. 공개 Maven repository에서 다운로드할 수 있다는 안내가 아니다. publicationGroup/publicationVersion을 변경했다면 소비 좌표도 실제 로컬 발행값에 맞춘다. 현재 문서 작업에서는 발행을 실행하지 않았다.

```kotlin
repositories { mavenLocal() }
dependencies {
    implementation("io.github.joohyung-park:harness-bundle:0.1.0")
}
```

현재 factory는 `Harnesses.create(configuration.provider)`이며 Codex/Gemini adapter를 제공한다. Koog 실험은 bundle에 포함되지 않는다. 개별 protocol/codex/gemini-cli artifact도 있고 process-bridge는 해당 adapter의 내부 의존성이다. adapter-testkit은 test-only이며 발행 대상이 아니다.

## 현재 runtime 준비

| 경로 | 현재 필요한 환경 |
|---|---|
| Codex | Python 3.10+, requirements에 고정된 openai-codex 0.147.0 및 포함된 runtime. client 세부는 [Provider mapping](provider-mapping.md) |
| Gemini CLI | Node와 SDK build entrypoint. 기존 조사에서는 외부 build 경로를 사용했으며 현재 설치 가능한 release는 후속 연동 때 재확인 |
| Koog 실험 | [독립 빌드](../experiments/koog-validation/README.md)의 JDK·Kotlin·lockfile. 모델 경계는 fixture이며 실모델 인증 불필요 |

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

1. 실제 공개 타입과 KDoc을 새 의미로 전환하고 버전·소스 호환성과 의미 변경을 기록한다.
2. 기본 영속 Session을 사용하던 소비자가 새 영속성 요구를 명시하도록 migration 예제를 제공한다.
3. 예외 중심 결과 회수를 TaskOutcome으로, finalMessage 사용을 TaskOutput으로 옮기는 소비 예제를 제공한다.
4. 선택 기능의 지원·거절과 runtime 준비를 provider별로 명시한다. 새 provider를 포함했다면 factory와 의존성에 실제 반영한다.
5. [samples/basic](../samples/basic)을 새 artifact만으로 빌드하여 transitive dependency와 공개 API를 검증한다.
6. README·문서의 구현 상태를 실연동 결과와 일치시킨다.

새 문서의 존재나 기존 테스트 통과만으로 새 계약을 발행하지 않는다. 전체 완료 조건은 [전환 계획](port-revision-plan.md)을 따른다.
