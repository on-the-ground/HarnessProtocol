# Distribution

## 소비자 계약

소비 프로젝트는 vendor SDK에 의존하지 않고 하나의 Maven artifact만 받는다.

```kotlin
repositories {
    mavenLocal()
}

dependencies {
    implementation("dev.harnessprotocol:harness-bundle:0.1.0-SNAPSHOT")
}
```

provider 선택은 composition root 한 곳에서 끝난다.

```kotlin
val harness: AgentHarness = Harnesses.create(configuration.provider)
```

그 아래 application code는 `AgentHarness`, `AgentSession`, `AgentExecution`, `AgentEvent`만 사용한다. Codex/Gemini SDK 타입, SDK의 session/turn 명칭, bridge script 경로는 노출되지 않는다.

## 발행

모든 모듈은 `maven-publish` publication과 sources JAR를 가진다.

로컬 개발용:

```powershell
.\gradlew.bat publishToMavenLocal
```

빌드 전용 Maven repository로 발행:

```powershell
.\gradlew.bat publishAllPublicationsToBuildRepository
```

기본 좌표는 다음과 같다.

```text
dev.harnessprotocol:harness-bundle:0.1.0-SNAPSHOT
```

좌표는 발행 시 바꿀 수 있다.

```powershell
.\gradlew.bat publishToMavenLocal `
  -PpublicationGroup=com.example.agent `
  -PpublicationVersion=1.0.0
```

실제 Maven Central 공개에는 프로젝트 URL, SCM, 개발자, 라이선스 선택, 서명, repository credential을 조직 정보에 맞게 추가해야 한다. 이 저장소는 아직 그 정보를 임의로 만들지 않는다.

## JAR에 포함되는 것

- 공통 Kotlin port와 값 타입
- Codex 및 Gemini CLI adapter
- SDK process transport
- Codex Python bridge와 고정 requirements
- Gemini CLI Node bridge

기본 factory는 JAR에 포함된 bridge를 임시 경로로 추출하므로 소비자가 script 경로를 전달할 필요가 없다.

## JAR에 포함할 수 없는 것

SDK API를 감추는 것과 provider runtime을 없애는 것은 다른 문제다.

- Codex adapter를 실행하는 머신에는 Python 3.10 이상과 `openai-codex==0.147.0`이 필요하다. 패키지가 고정된 Codex runtime(`openai-codex-cli-bin`)을 포함하므로 Codex CLI를 별도로 고를 필요는 없다. host는 `openai_codex.client.CodexClient`를 직접 사용하므로 버전 고정은 계약의 일부다([provider-mapping.md](provider-mapping.md)).
- Gemini CLI adapter를 실행하는 머신에는 Node.js와 Gemini CLI SDK build가 필요하다. 조사 시점에는 SDK 구현이 공식 monorepo에만 있고 npm package가 공개되지 않았으므로 `GEMINI_CLI_SDK_MODULE` 환경 변수로 build entrypoint를 지정한다.
- 인증 정보는 library가 소유하거나 artifact에 포함하지 않는다. 각 실행 환경의 provider 인증을 사용한다.

기본 실행 파일 이름은 `python`과 `node`다. 배포 이미지의 경로가 다르면 각각 `HARNESS_CODEX_PYTHON`, `HARNESS_GEMINI_NODE` 환경 변수로 실행 파일의 절대 경로를 지정할 수 있다.

즉, 소비 프로젝트의 **코드와 Gradle dependency**에서는 SDK를 제거했다. 운영 환경까지 완전히 self-contained하게 만들려면 OS/architecture별 runner image 또는 별도 sidecar 배포물이 추가로 필요하다.

## 개별 artifact

하나의 provider만 필요하면 bundle 대신 다음을 직접 사용할 수 있다.

```text
dev.harnessprotocol:harness-protocol
dev.harnessprotocol:harness-codex
dev.harnessprotocol:harness-gemini-cli
```

`harness-process-bridge`는 adapter의 내부 runtime dependency이므로 소비자가 직접 참조하지 않는다. `harness-adapter-testkit`은 test-only 모듈이며 발행되지 않는다.

독립 Gradle build가 실제 발행 artifact만 받아 사용하는 예제는 `samples/basic`에 있다. 이 예제는 root project module에 의존하지 않으므로 transitive POM과 공개 API를 함께 검증한다.
