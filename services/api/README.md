# AI 및 문서 검색 API

Java 17 / Spring Boot 3.5 / WebFlux / Security / Gradle Wrapper. 업무 질문은 Lucene 검색을 거친 뒤 설정된 AI 공급자로 전달된다. POI가 Excel 구조를, Tika가 DOCX/PDF를 추출한다. [공급자 선택 및 YAML 우선순위 설정](../../README.md#ai-공급자와-우선순위-설정)을 참고한다.

작업 디렉터리는 이 `services/api` 폴더다. `src/main/resources/application.yml`에는 공개 기본값과 `spring.config.import`가 있다. 작업 디렉터리의 `.secret/application.yml`에 로컬 설정을 적으면 공개 기본값을 덮어쓴다. 이 파일은 선택 사항이며 Git과 JAR에 포함되지 않는다.

```zsh
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
./gradlew bootRun
```

로컬 API 비밀번호는 Groq 키와 별개다. API 사용자·비밀번호·허용 프로젝트·요청 한도는 Git에서 제외하는 `services/api/.secret/application.yml`의 `pet.api`에서 관리한다. 환경변수로 재정의할 수도 있다. 개인 계정은 `pet.api.accounts`로 지정한다. 설정 예시는 [루트 README](../../README.md#계정-등급과-학습-자료-등록)를 참고한다. `projects`가 비어 있거나 생략되면 로그인한 계정으로 모든 지원 프로젝트의 검색 가능한 문서를 조회할 수 있다. HTTP는 localhost 개발에만 사용한다. 네트워크에 공개하기 전에 조직 인증과 HTTPS를 구성해야 한다.

- API 주소: http://127.0.0.1:9001
- POST /api/chat/stream: `{question, mode: work|general}` → sources/delta/done 또는 error SSE
- GET /api/search?q=...: 인증된 프로젝트만 검색
- Mock 선택: `AI_PROVIDER=mock` 환경변수. 기존 Groq 키를 삭제할 필요 없음
- 원본: 기본 ../../data/knowledge, 색인: ../../.local/knowledge-index
- 문서 폴더는 `knowledge.root`, 색인 위치는 `knowledge.index-path`로 지정한다. 서버 초기화 시 두 폴더가 없으면 생성한다. 실행 중 자료 폴더에 접근할 수 없으면 문서 검색을 중단한다. `knowledge.poll-ms`의 기본값은 5000이다. 서버가 실행 중에도 이 간격으로 폴더를 확인한다.
- 외부 AI 키/원문/인증 헤더를 로그에 남기지 않음

문서 제안 검토, 직접 반영, 프로젝트 생성은 `accounts`에서 `grade: admin`으로 지정한 개인 계정이 담당한다. `accounts`를 설정하지 않은 서버는 기존 `pet.api.admin.*` 및 `pet.api.knowledge.direct-publish-users` 설정으로 로그인과 승인을 처리한다. 제안 원문과 업로드 첨부파일은 `knowledge.store-path`에 보관한다. 기본값은 `knowledge.root`와 같은 부모 아래의 `knowledge-submissions/`이며 기본 프로젝트 구조에서는 `data/knowledge-submissions/`이다. 이 폴더의 `proposals.json`, `.initialized`, `attachments/`를 함께 백업한다. 기존 색인 폴더의 제안은 첫 실행 시 복사하고, 이후 검색 색인은 영구 저장소에서 재생성한다.

```sh
./gradlew test bootJar
KNOWLEDGE_CORPUS_TEST=true ./gradlew test --tests 'com.mychatbot.knowledge.*'
```

테스트는 합성 HTTP 상류로 타임아웃/429/취소/부분 EOF와 ACL·민감정보·구조 추출·동기화를 검증한다. 실제 문서 테스트는 읽기 전용이며 `.local`에만 파생물을 쓴다. 실제 Groq opt-in 설정은 LiveGroqTest를 참고한다. 결과는 루트 docs/qa-report.md에 기록한다.

문서/설정 원본과 키가 Git/JAR에서 제외되므로 다른 환경에 배포할 때는 별도 서버 비밀 설정이 필요하다.

JDK 17에서는 Lucene 9.12.3을 사용한다. 이전 Lucene 10 검색 색인은 `lucene/`에 남기고 `lucene-9/`에 원문으로부터 재색인한다. 기존 제안의 자동 이관을 위해 `knowledge.index-path` 전체를 먼저 삭제하지 않는다. 제안 원문과 첨부파일은 `knowledge.store-path`에 보관한다.

계정별 `pet.api.accounts[].excluded-paths`는 `knowledge.root` 기준 상대 경로의 정확한 경로와 그 하위를 차단한다(대소문자 구분). 예: `projects: [e2u, paye2u, wayup, user]`, `excluded-paths: [user/ghkang]`. 제외가 전체/개별 프로젝트 허용보다 우선한다. 검색·AI 근거 선택·다운로드와 원본에 연결된 제안/첨부/검토에도 적용하며, 설정 변경은 새 서버 JAR 배포 후 재시작해야 반영된다. 원본 자료는 그대로 두고 기존 색인만 재생성한다. 자세한 YAML 예시는 루트 README를 참고한다.

## 프로젝트·폴더 트리 API

개인 계정은 `grade: admin` / `grade: user`로 관리하며 기존 `member`도 일반 사용자로 인식한다. 폴더 바로 생성과 검토는 관리자만 수행하고 `direct-publish-users`로 부여하지 않는다. `projects`와 `excluded-paths`는 관리자에게도 적용한다.

- `GET /api/knowledge/folders`: 접근 가능한 폴더 경로, 본인 요청과 관리자가 검토할 수 있는 요청, 생성 권한을 반환한다.
- `POST /api/knowledge/folders`: `{parentPath, name}`으로 하위 폴더를 생성하거나 승인 요청한다. 최상위 프로젝트의 `parentPath`는 빈 문자열이다.
- `POST /api/knowledge/admin/folders/{id}/approve` 또는 `/reject`: 관리자 허용 범위를 다시 확인해 처리한다.
- 문서 초안·제출의 선택 필드 `folderPath`는 미지정 시 기존 프로젝트 루트로 처리한다. 선택 경로를 저장하고 검색·다운로드 권한에도 반영한다.

요청 이력 `knowledge.store-path/folder-requests.json`을 함께 백업한다. 프로젝트 승인은 요청자에게 새 프로젝트 권한을 자동 부여하지 않는다. 프런트엔드와 서버를 함께 업데이트해야 한다.

## 승인 파일 동기화

승인 시 선택한 `folderPath` 아래에 `<안전한 제목>--<제안 UUID>.md`와 `attachments/<동일 UUID>/`의 첨부 사본을 생성한다. 계정명이나 순번 대신 기존 UUID로 묶고, 보완 제안은 원본을 덮어쓰지 않는 별도 문서로 보관한다. 기존 승인 자료는 시작·동기화 시 반영하며 기존 원본 문서와 제안 저장소를 이동하지 않는다.

`knowledge.store-path/published-files.json`에 소유 경로와 해시를 기록하고 일반 문서 스캔에서 정확한 관리 경로만 제외한다. 승인 제안은 기존 권한 검사와 한 번의 검색 색인 경로를 유지한다. 비활성화된 생성 파일은 `published-history/<UUID>/`로 보관하며 외부 수정·파일 충돌·심볼릭 링크는 덮어쓰지 않는다. 소유 기록이 유실되거나 활성 사본의 무결성이 달라지면 안전하게 중단한다. 제안 저장소 전체와 자료 폴더를 함께 백업한다.

## 배포 서버 시작과 종료

`bin/start.sh`와 `bin/stop.sh`는 macOS·Linux용이다. 배포할 때 아래처럼 파일을 배치한다.

```text
배포폴더/
├── app.jar
├── bin/
│   ├── start.sh
│   └── stop.sh
├── .secret/application.yml
└── data/
```

서버 실행 계정에 배포 폴더의 쓰기 권한을 주고 Java 17 이상을 준비한다. `start.sh`는 `bin`의 부모 폴더에서 실행하므로 `.secret/application.yml`도 그 위치에서 읽는다. 배포 설정의 `knowledge.root`, `knowledge.store-path`, `knowledge.index-path`는 서버에 맞는 절대 경로로 지정한다.

```sh
chmod +x bin/start.sh bin/stop.sh
./bin/start.sh
./bin/stop.sh
```

실행할 Java를 지정하려면 `JAVA_HOME`을 설정한다. 시작 스크립트는 `app.jar`를 우선 사용하고, 개발 저장소에서는 `build/libs/`의 실행 JAR를 찾는다. PID는 배포 폴더의 `.local/api.pid`, 실행 로그는 `logs/api-날짜-시간-PID.log`에 기록한다. 시작 명령은 프로세스를 띄우며 최종 기동 성공 여부는 로그의 `Started MyChatBotApplication`으로 확인한다. 종료 명령은 해당 PID의 앱 경로를 확인한 뒤 정상 종료를 요청하고 최대 30초 기다린다.

새 JAR 배포는 **종료 → app.jar 교체 → 시작** 순서로 진행한다. 이 스크립트의 시작은 서버 재부팅 후 자동 실행 등록까지 포함하지 않는다.
