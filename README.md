# MyChatBot

Tauri 2 데스크톱 펫과 Java 21 / Spring Boot API로 구성된 사내 챗봇이다. 업무 문서를 검색해 답변하고, 사용자가 제출한 문서 제안을 관리자 승인 후 검색에 반영한다. 아래 경로는 모두 `AI_PET` 루트 기준이다.

## 내 조직에 맞게 바꾸기

| 바꾸려는 것 | 파일 또는 설정 | 적용 방법 |
| --- | --- | --- |
| 챗봇이 읽을 업무 자료 | `data/knowledge/<프로젝트>/` | 파일을 넣으면 서버가 자동으로 다시 읽는다 |
| 대화창 옆의 기본 펫 이미지 | `pet.png` | 같은 이름의 투명 PNG로 교체하고 앱을 다시 빌드한다 |
| 드래그 중 펫 이미지 | `assets/pet/pet-drag.png` | 같은 이름의 투명 PNG로 교체하고 앱을 다시 빌드한다 |
| 답변 대기 중 펫 이미지 | `assets/pet/pet-thinking.png` | 같은 이름의 투명 PNG로 교체하고 앱을 다시 빌드한다 |
| 대화창의 이름·배지·테마 | `branding.json` | PC별 설정 파일을 저장한 뒤 앱을 종료하고 다시 연다 |
| 설치 파일과 앱의 OS 아이콘 | `assets/pet/app-icon.png` | 정사각형 PNG를 교체하고 `.ico` 또는 `.icns`를 다시 생성해 패키징한다 |
| macOS 메뉴 막대 아이콘 | `assets/pet/tray-icon-macos.png` | 단색 투명 PNG를 교체하고 앱을 다시 빌드한다. macOS가 배경에 맞춰 색을 적용한다 |
| Windows 알림 영역 아이콘 | `assets/pet/tray-icon.png` | 투명 PNG를 교체하고 앱을 다시 빌드한다 |
| 앱이 접속할 API 주소 | `VITE_API_BASE_URL`, Tauri CSP `connect-src` | 두 곳에 같은 서버 주소를 지정하고 앱을 다시 빌드한다 |

### 업무 자료와 챗봇 사용 안내 추가

기본 자료 폴더는 `data/knowledge/`다. 서버가 시작할 때 지정된 자료 폴더와 색인 폴더가 없으면 자동으로 만든다. 새로 받은 저장소에서도 빈 자료 폴더를 미리 만들 필요가 없다. 예를 들어 `data/knowledge/syslink/엑셀-수집-절차.md`나 `data/knowledge/operations/정산-점검.xlsx`처럼 프로젝트 하위에 파일을 넣는다. 지원 프로젝트는 `paye2u`, `syslink`, `operations`, `notes`, `common`이며, `common` 폴더가 없으면 만들 수 있다. 서버가 하위 폴더까지 확인하며, 파일 경로와 문서 내용에 따라 프로젝트 분류가 달라질 수 있다. 검색과 원본 다운로드는 로그인 계정에 허용된 프로젝트 범위에서 동작한다.

- 매뉴얼은 Markdown(`.md`)로 작성하고, 자주 묻는 질문을 제목으로 쓰면 관련 내용을 찾기 쉽다. Excel, DOCX, PDF, TXT도 읽을 수 있다.
- 로컬의 `docs/chatbot-user-guide.md`를 조직에 맞게 수정한 뒤 `/새문서`에서 `common`을 선택해 제출한다. 현재 서버가 지원하는 실제 프로젝트 이름과 폴더는 이 README에서 확인할 수 있다. `docs/`는 프로젝트 문서 보관 위치다.
- 챗봇에서 제안하려면 `/새문서` 또는 `/수정`으로 초안을 작성하고 승인 요청을 보낸다. 관리자가 **제안 검토**에서 승인하면 해당 프로젝트 검색에 반영된다. 직접 반영 권한이 있는 계정은 제출과 함께 반영된다.

자료 추가와 변경은 기본 5초 간격으로 확인하므로 서버를 재시작할 필요가 없다. 다른 자료 폴더를 쓰려면 서버 설정의 `knowledge.root`를 변경하고 서버를 재시작한다.

### 이름·배지·테마 변경

배포 기본값은 [프로젝트 루트의 branding.json](branding.json)에서 수정한다. 빌드 시 앱에 포함되므로 같은 설치 파일을 받은 PC에 공통으로 적용된다.

설치한 앱에서 PC별로 바꾸려면 아래 경로에 UTF-8로 `branding.json`을 저장한다. 폴더가 없으면 만든다. PC별 파일에 적은 항목만 배포 기본값을 덮어쓴다.

| 운영체제 | 파일 경로 |
| --- | --- |
| macOS | `~/Library/Application Support/com.easytouse.ai-pet/branding.json` |
| Windows | `%APPDATA%\com.easytouse.ai-pet\branding.json` |

```json
{
  "name": "MyChatBot",
  "badge": "우리 회사",
  "theme": "mono"
}
```

- `name`은 로그인·대화창·답변 새창의 표시 이름이다. 기본값은 `MyChatBot`이다.
- `badge`는 이름 옆의 짧은 문구다. 기본값은 `CHAT`이며 `""`을 지정하면 숨긴다.
- `theme`은 `gold`(기본 노랑), `blue`(파랑), `green`(초록), `mono`(흰 화면에 검정 버튼과 회색 배지) 중에서 고른다. 버튼, 배지, 입력 포커스에 적용된다.
- 프로젝트 루트 설정을 변경하면 다시 빌드해야 한다. PC별 설정을 변경하면 앱을 트레이 메뉴에서 **종료**하고 다시 열면 된다. PC별 설정이 없거나 잘못된 항목은 배포 기본값을 사용한다. 빌드 시 지정한 `VITE_CHATBOT_NAME`, `VITE_CHATBOT_BADGE`가 있으면 이름·배지의 기본값으로 사용한다.

### 펫 이미지와 설치 아이콘 교체

`pet.png`를 교체하면 기본 펫의 모습이 바뀐다. 드래그하거나 답변을 기다릴 때도 같은 캐릭터를 표시하려면 `assets/pet/`의 상태 이미지 두 장도 함께 교체한다.

Windows 설치 파일과 macOS 앱에 쓰는 아이콘은 `assets/pet/app-icon.png`에서 생성한다. `apps/desktop`에서 아래 명령을 실행하면 `src-tauri/target/generated-icons/`에 `icon.ico`와 `icon.icns`가 생성된다.

```sh
npm run tauri -- icon ../../assets/pet/app-icon.png --output src-tauri/target/generated-icons
```

생성한 아이콘을 패키징 설정의 `bundle.icon`에 지정하고 [macOS·Windows 패키징 안내](apps/desktop/README.md)에 따라 다시 빌드한다.

## 구조
```text
.codex/                 # 프로젝트 에이전트 설정과 5개 역할
AGENTS.md               # 메인 총괄/파일 소유권/보안 지침
apps/desktop/           # TypeScript, Vanilla UI, Vite, Tauri 2
services/api/            # WebFlux, Security, Groq/Mock, POI/Tika/Lucene
assets/                 # 펫/브랜드 리소스 안내
pet.png                 # 사용자 원본
data/knowledge/        # 기존 업무 자료, 원본 보존/Git 제외
.local/knowledge-index/ # 파생 검색 인덱스/manifest/status/CSV, Git 제외
docs/                  # 요구사항·설계·인벤토리·QA·진행 기록
ops/                    # 구성/API smoke 검사
```

## 서버 실행
Java 21이 필요하다. 서버 작업 디렉터리는 `services/api`다. `services/api/src/main/resources/application.yml`은 Git과 JAR에 포함되는 공개 기본 설정이다. Spring Boot는 작업 디렉터리의 `.secret/application.yml`이 있으면 함께 읽고, 그 값으로 공개 기본값을 덮어쓴다. API 키와 비밀번호는 `services/api/.secret/application.yml` 또는 서버 환경 변수에만 둔다.

IntelliJ에서는 `services/api`를 Gradle 프로젝트로 열고 `com.mychatbot.MyChatBotApplication`을 실행한다. 이전 시작 클래스를 지정한 실행 설정이 있다면 새 클래스로 바꾼다. 서버 프로젝트 이름은 `my-chat-bot`이다.

로컬 API 인증은 Groq 키와 별개다. API 사용자·비밀번호·허용 프로젝트·요청 한도는 Git에서 제외하는 `services/api/.secret/application.yml`의 `pet.api`에서 관리한다. 환경변수로 재정의할 수도 있다. 개발용 일반 계정과 문서 제안 검토용 관리자 계정을 각각 설정할 수 있다.

```zsh
cd services/api
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
./gradlew bootRun
```

로컬 API 기본 주소는 `http://127.0.0.1:9001`이다. `pet.api.projects`와 `pet.api.admin.projects`는 일반 계정과 관리자 계정의 권한을 각각 설정한다. 비워 두거나 생략하면 모든 지원 프로젝트에 접근할 수 있고, 범위를 제한하려면 프로젝트 ID를 쉼표로 지정한다. 미분류·격리 문서는 검색에서 제외한다. LAN/운영 노출 전에 조직 인증·사용자별 권한 원천·HTTPS를 연결해야 한다.

현재 환경에서 Gradle 기본 캐시가 동작하지 않으면 `GRADLE_USER_HOME=/private/tmp/easytouse-gradle`를 지정한다. 네트워크가 없는 개발 검증은 명시적으로 `AI_PROVIDER=mock`을 설정한다. 설정 누락이나 공급자 오류를 조용히 Mock으로 바꾸지 않는다.

### AI 공급자와 우선순위 설정

서버의 `.secret/application.yml`에서 `ai.provider`에 사용할 공급자를 쉼표로 나열하고, 각 공급자의 `order`를 지정한다. 숫자가 가장 작은 공급자를 먼저 호출한다. 아래 항목을 기존 `ai` 블록에 합친다. 공개 YAML에는 실제 키를 적지 않는다.

```yaml
ai:
  provider: groq,codex,claude,gemini,kimi
  groq:
    order: 10
  codex:
    order: 20
    api-key: ${OPENAI_API_KEY:}
  claude:
    order: 30
    api-key: ${ANTHROPIC_API_KEY:}
  gemini:
    order: 40
    api-key: ${GEMINI_API_KEY:}
  kimi:
    order: 50
    api-key: ${MOONSHOT_API_KEY:}
```

API 키는 IntelliJ 실행 설정의 환경 변수에 넣고 서버를 재시작한다. 환경 변수 `AI_PROVIDER`로 사용할 공급자 목록을 바꿀 수도 있다. `order`를 생략하면 100이며, 값이 같으면 `ai.provider`의 목록 순서가 우선한다. 공급자별 `model`과 `base-url`은 필요할 때만 지정한다.

한 요청에 공급자 한 곳만 먼저 호출한다. 키가 없으면 호출하지 않고 건너뛰며, 키 인증 실패(401) 또는 사용 한도·크레딧 부족이 첫 답변 전에 확인되면 다음 공급자로 넘어간다. 일반 권한 거부, 서버 과부하, 타임아웃 및 답변을 이미 보내기 시작한 경우에는 전환하지 않는다. 공급자별 자동 재호출과 병렬 호출은 하지 않으며 새 질문은 다시 우선순위 1순위부터 시작한다.

`codex`는 [OpenAI Responses API](https://developers.openai.com/api/docs/guides/streaming-responses)를 사용하며 기본 모델은 [Codex 권장 후속 모델](https://developers.openai.com/api/docs/deprecations)인 `gpt-6-sol`이다. Codex 앱 로그인이나 구독 세션과 별도로 OpenAI API 키가 필요하다. 다른 기본 모델은 `claude-sonnet-4-6`, `gemini-3.5-flash`, `kimi-k3.1-pro`다. 활성화한 공급자에는 질문과 기존 권한·민감정보 검사를 거친 문서 발췌가 전달될 수 있으므로 사내 자료 전송이 허용된 공급자만 선택한다.

## 데스크톱
Node 22.12 이상과 npm이 필요하다. 네이티브 빌드는 Rust stable과 [Tauri OS 개발 도구](https://v2.tauri.app/start/prerequisites/)도 필요하다.

```sh
cd apps/desktop
npm ci
npm test
npm run build
npm run dev
# 네이티브 실행
source "$HOME/.cargo/env"
npm run tauri -- dev
```

앱을 시작하면 로그인 화면이 열린다. 서버 API 사용자와 비밀번호로 접속하면 앱을 종료하거나 로그아웃할 때까지 유지된다. 인증 정보는 앱 메모리에만 두고 대화와 함께 파일에 저장하지 않는다. 헤더의 `−`/`＋` 또는 창 모서리로 대화창 크기를 조절하고, 완료 답변의 **새창**를 누르면 별도 창에서 본문과 출처를 볼 수 있다. 서버를 이전 버전에서 실행 중이면 새 로그인 API를 사용하도록 재시작한다.

업무 질문은 권한 있는 문서 검색 후 근거와 출처를 제공한다. 질문 모드 선택 없이 입력한다. 권한 있는 문서가 검색되면 근거로 사용하고, 검색 결과가 없으면 일반 답변을 시도하며 사내 정보는 추측하지 않는다. Enter 전송, Shift+Enter 줄바꿈, Escape 닫기, 진행 중 취소와 명시적 재시도를 지원한다. 답변/출처는 안전한 텍스트로 표시하며 Markdown/코드 문법 렌더링은 아직 제공하지 않는다.

대화창과 새창에서 파일명 옆의 `다운로드`를 누르면 파일 사본이 사용자 기기의 다운로드 폴더에 저장된다. macOS와 Windows 앱에서는 지원 문서를 저장한 뒤 기본 앱으로 연다. 자동 열기에 실패해도 저장된 사본은 다운로드 폴더에서 열 수 있다. 브라우저에서는 다운로드를 제공한다. 답변이 중간에 끊긴 경우 받은 내용은 부분 답변으로 표시하며 새창에서도 읽을 수 있다.

대화창에서 문서 수정이나 새 문서 작성을 요청하거나 `/수정`, `/새문서`를 입력한다. 기존 문서를 선택하거나 새 문서의 프로젝트를 선택하고 초안을 검토한다. 동의 후 제출한 제안은 `내 제안`에서 확인한다. 별도 관리자 계정으로 로그인해 승인하면 검색에 반영된다. 기존 문서 수정은 원본에 연결된 정정 문서로 기록한다. 관리자 계정 설정은 [서버 안내](services/api/README.md)를 참고한다.

### 문서 제안 직접 반영 권한

서버 `.secret/application.yml`의 기존 `pet.api` 아래에 `knowledge.direct-publish-users`를 지정한다. 기본값은 빈 문자열이며, 아래는 `developer` 계정에 권한을 주는 예시다.

```yaml
pet:
  api:
    knowledge:
      direct-publish-users: "developer"
```

계정명을 쉼표로 구분하고 서버를 재시작한다. 등록한 계정은 본인이 새로 제출한 문서·정정을 공유 동의 후 관리자 검토 없이 검색에 반영할 수 있다. 프로젝트 권한, 민감정보 검사, 원본 버전 확인은 계속 적용된다. 같은 계정을 여러 사람이 사용하면 모두 이 권한을 갖는다. 기존 승인 대기 제안은 기존 절차로 처리한다.

## 파일 기반 RAG

MyChatBot은 **Lucene 키워드 검색 기반 RAG(검색 증강 생성)**를 사용한다. 파일을 등록할 때 모델을 재학습하는 대신, 문서에서 뽑은 텍스트를 로컬 검색 색인에 저장하고 질문마다 관련 부분만 AI에 전달한다. 검색에는 벡터 DB나 임베딩 API를 사용하지 않는다.

1. **색인** — `data/knowledge/`의 파일을 읽어 엑셀은 시트·행, Markdown은 제목과 본문 구간 단위로 나눈다. 검색 색인은 `.local/knowledge-index/`에 두고, 관리자 승인과 공유 동의를 거친 문서 제안도 해당 프로젝트에 색인한다.
2. **검색** — 로그인 계정의 프로젝트 권한으로 범위를 제한한 뒤 Lucene의 한국어 형태소 분석기(Nori)와 정확한 키워드 일치를 사용한다. 질문의 핵심 용어가 같은 구간에 함께 나오거나 가까이 있으면 우선한다.
3. **답변과 출처** — 관련 문서 조각을 최대 4개, 합계 최대 12,000자로 제한해 질문과 함께 선택된 AI 공급자에 보낸다. 모델에 근거 번호를 인용하도록 요청하고, 화면의 **검색 참고 자료**에는 AI에 전달한 파일명과 확인 가능한 시트·행을 표시한다. 이 목록은 검색 근거이며 모델이 답변에 실제로 인용한 자료와 다를 수 있다. 원본 다운로드 시 권한과 파일 버전을 다시 확인한다.

문서를 추가하거나 바꾸면 색인만 갱신된다. 키워드 기반이므로 문서와 질문이 같은 뜻을 전혀 다른 표현으로 쓰면 관련 자료를 놓칠 수 있다.

## 문서와 동기화
기존 `data/knowledge` 원본을 읽기만 한다. 메타데이터 분류·색인·CSV는 별도 `.local`에 생성한다. XLSX/XLS는 POI로 시트/행/열/병합/수식과 캐시값을 구분하고 수식을 재계산하지 않는다. Markdown은 제목 계층과 코드 문맥을 보존한다. DOCX/PDF는 제한된 Tika 추출, Java/XML/JSON/TXT/SQL은 텍스트 추출을 사용한다.

서버가 시작할 때와 실행 중 기본 5초 간격으로 문서 폴더를 스캔한다. 새 파일과 내용이 바뀐 파일을 읽어 색인에 반영하고, 삭제된 파일은 검색에서 제외한다. 검색 시에도 원본 가용성과 버전을 재확인한다. 색인 정책 버전 변경 시 재색인한다. HTML/이미지/OCR, Office 임시 파일은 제외한다. 변경 전/rollback/diff 이력은 보존하되 기본 검색에서 제외한다.

- 실제 검색 가능 수와 제외 사유: `.local/knowledge-index/status.json`, `inventory.csv`
- 휴리스틱 분류는 업무 담당자 검토가 필요하다. 파일을 물리적으로 이동하지 않는다.

## 검증
```sh
python3 ops/check-config.py
# 서버 실행 및 API 인증 환경변수 설정 후: 실제 AI 요청을 포함한다.
python3 ops/check-api.py
cd services/api
./gradlew test bootJar
# 선택 실행: 원본 읽기 전용 실제 문서 검사
KNOWLEDGE_CORPUS_TEST=true ./gradlew test --tests 'com.mychatbot.knowledge.*'
```

실제 Groq 검사는 기존 키를 읽는 opt-in 테스트다. 키/응답을 출력하지 않는다. 네이티브 패키징 방법은 [앱 README](apps/desktop/README.md)에 있다.

## 후속 범위

채팅으로 제출한 문서 제안은 서버의 색인 폴더에 `proposals.json`으로 저장하며, 공유 동의와 관리자 승인을 거친 제안만 공용 검색에 반영한다. 관리자 웹·업로드, MySQL 메타데이터 저장, 조직 SSO와 서명 배포는 후속 범위다.
