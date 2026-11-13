# AI 및 문서 검색 API

Java 21 / Spring Boot 3.5 / WebFlux / Security / Gradle Wrapper. 업무 질문은 Lucene 검색을 거친 뒤 설정된 AI 공급자로 전달된다. POI가 Excel 구조를, Tika가 DOCX/PDF를 추출한다. [공급자 선택 및 YAML 우선순위 설정](../../README.md#ai-공급자와-우선순위-설정)을 참고한다.

작업 디렉터리는 이 `services/api` 폴더다. `src/main/resources/application.yml`에는 공개 기본값과 `spring.config.import`가 있다. 작업 디렉터리의 `.secret/application.yml`에 로컬 설정을 적으면 공개 기본값을 덮어쓴다. 이 파일은 선택 사항이며 Git과 JAR에 포함되지 않는다.

```zsh
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
./gradlew bootRun
```

로컬 API 비밀번호는 Groq 키와 별개다. API 사용자·비밀번호·허용 프로젝트·요청 한도는 Git에서 제외하는 `services/api/.secret/application.yml`의 `pet.api`에서 관리한다. 환경변수로 재정의할 수도 있다. 단일 개발용 계정이며 조직 SSO가 아니다. `password`가 없으면 계정이 비활성이다. `projects`가 비어 있거나 생략되면 로그인한 계정으로 모든 지원 프로젝트의 검색 가능한 문서를 조회할 수 있다. HTTP는 localhost 개발에만 사용한다. 네트워크에 공개하기 전에 조직 인증과 HTTPS를 구성해야 한다.

- API 주소: http://127.0.0.1:9001
- POST /api/chat/stream: `{question, mode: work|general}` → sources/delta/done 또는 error SSE
- GET /api/search?q=...: 인증된 프로젝트만 검색
- Mock 선택: `AI_PROVIDER=mock` 환경변수. 기존 Groq 키를 삭제할 필요 없음
- 원본: 기본 ../../data/knowledge, 색인: ../../.local/knowledge-index
- 문서 폴더는 `knowledge.root`, 색인 위치는 `knowledge.index-path`로 지정한다. 서버 초기화 시 두 폴더가 없으면 생성한다. 실행 중 자료 폴더에 접근할 수 없으면 문서 검색을 중단한다. `knowledge.poll-ms`의 기본값은 5000이다. 서버가 실행 중에도 이 간격으로 폴더를 확인한다.
- 외부 AI 키/원문/인증 헤더를 로그에 남기지 않음

문서 제안을 검토하려면 서버 환경에 `PET_API_ADMIN_USER`, `PET_API_ADMIN_PASSWORD`를 설정한다. YAML에서는 `pet.api.admin.user`, `pet.api.admin.password`에 해당한다. 일반 계정과 다른 사용자 이름과 비밀번호를 사용한다. `PET_API_ADMIN_PROJECTS` 또는 `pet.api.admin.projects`를 비우거나 생략하면 전체 지원 프로젝트를 검토할 수 있다. 범위를 제한하려면 `paye2u,syslink`처럼 쉼표로 나열한다. 관리자는 같은 미니펫 로그인 화면에서 별도 계정으로 접속한다.

```sh
./gradlew test bootJar
KNOWLEDGE_CORPUS_TEST=true ./gradlew test --tests 'com.mychatbot.knowledge.*'
```

테스트는 합성 HTTP 상류로 타임아웃/429/취소/부분 EOF와 ACL·민감정보·구조 추출·동기화를 검증한다. 실제 문서 테스트는 읽기 전용이며 `.local`에만 파생물을 쓴다. 실제 Groq opt-in 설정은 LiveGroqTest를 참고한다. 결과는 루트 docs/qa-report.md에 기록한다.

문서/설정 원본과 키가 Git/JAR에서 제외되므로 다른 환경에 배포할 때는 별도 서버 비밀 설정이 필요하다.
