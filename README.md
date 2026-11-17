# MyChatBot

Tauri 2 데스크톱앱 Java 17 / Spring Boot API로 구성된 챗봇이다. 업무 문서를 검색해 답변하고, 사용자가 제출한 문서 제안을 관리자 승인 후 검색에 반영한다. 아래 경로는 모두 프로젝트 루트 기준이다.

## 내 조직에 맞게 바꾸기

| 바꾸려는 것            | 파일 또는 설정 | 적용 방법 |
|-------------------| --- | --- |
| 챗봇이 읽을 업무 자료      | `data/knowledge/<프로젝트>/` | 파일을 넣으면 서버가 자동으로 다시 읽는다 |
| 대화창 옆의 기본 봇 이미지   | `pet.png` | 같은 이름의 투명 PNG로 교체하고 앱을 다시 빌드한다 |
| 드래그 중 봇 이미지       | `assets/pet/pet-drag.png` | 같은 이름의 투명 PNG로 교체하고 앱을 다시 빌드한다 |
| 답변 대기 중 봇 이미지     | `assets/pet/pet-thinking.png` | 같은 이름의 투명 PNG로 교체하고 앱을 다시 빌드한다 |
| 대화창의 이름·배지·테마     | `branding.json` | PC별 설정 파일을 저장한 뒤 앱을 종료하고 다시 연다 |
| 설치 파일과 앱의 OS 아이콘  | `assets/pet/app-icon.png` | 정사각형 PNG를 교체하고 `.ico` 또는 `.icns`를 다시 생성해 패키징한다 |
| macOS 메뉴 막대 아이콘   | `assets/pet/tray-icon-macos.png` | 단색 투명 PNG를 교체하고 앱을 다시 빌드한다. macOS가 배경에 맞춰 색을 적용한다 |
| Windows 알림 영역 아이콘 | `assets/pet/tray-icon.png` | 투명 PNG를 교체하고 앱을 다시 빌드한다 |
| 앱이 접속할 API 주소     | `apps/desktop/.env`의 `VITE_API_BASE_URL` | 서버 주소를 지정하고 앱을 다시 빌드한다 |

### 업무 자료와 챗봇 사용 안내 추가

기본 자료 폴더는 `data/knowledge/`다. 서버가 시작할 때 지정된 자료 폴더와 색인 폴더가 없으면 자동으로 만든다. 새로 받은 저장소에서도 빈 자료 폴더를 미리 만들 필요가 없다. 예를 들어 `data/knowledge/user/엑셀-수집-절차.md`나 `data/knowledge/operations/점검.xlsx`처럼 프로젝트 하위에 파일을 넣는다. 기본 프로젝트에 더해 자료 폴더 바로 아래에 만든 폴더를 프로젝트로 인식한다. 예를 들어 `data/knowledge/e2u/`를 만들고 **새 문서 작성** 화면을 다시 열면 권한이 있는 계정의 목록에 표시된다. 폴더 이름에는 영문 소문자, 숫자, 하이픈, 밑줄을 사용할 수 있다. 계정의 프로젝트 목록을 비워 두면 새 폴더도 조회 범위에 포함되며, 범위를 지정한 계정은 설정한 프로젝트만 조회한다. 서버가 하위 폴더까지 확인하며, 파일 경로와 문서 내용에 따라 프로젝트 분류가 달라질 수 있다. 검색과 원본 다운로드는 로그인 계정에 허용된 프로젝트 범위에서 동작한다.

- 매뉴얼은 Markdown(`.md`)로 작성하고, 자주 묻는 질문을 제목으로 쓰면 관련 내용을 찾기 쉽다. Excel, DOCX, PDF, TXT도 읽을 수 있다.
- 로컬의 `docs/chatbot-user-guide.md`를 조직에 맞게 수정한 뒤 `/새문서`에서 `common`을 선택해 제출한다. 현재 서버가 지원하는 실제 프로젝트 이름과 폴더는 이 README에서 확인할 수 있다. `docs/`는 프로젝트 문서 보관 위치다.
- 챗봇에서 제안하려면 `/새문서` 또는 `/수정`으로 초안을 작성하고 승인 요청을 보낸다. 관리자가 **제안 검토**에서 승인하면 해당 프로젝트 검색에 반영된다. 직접 반영 권한이 있는 계정은 제출과 함께 반영된다.

자료 추가와 변경은 기본 5초 간격으로 확인하므로 서버를 재시작할 필요가 없다. 다른 자료 폴더를 쓰려면 서버 설정의 `knowledge.root`를 변경하고 서버를 재시작한다.

### 이름·배지·테마 변경

배포 기본값은 [프로젝트 루트의 branding.json](branding.json)에서 수정한다. 빌드 시 앱에 포함되므로 같은 설치 파일을 받은 PC에 공통으로 적용된다.

설치한 앱에서 PC별로 바꾸려면 아래 경로에 UTF-8로 `branding.json`을 저장한다. 폴더가 없으면 만든다. PC별 파일에 적은 항목만 배포 기본값을 덮어쓴다.

| 운영체제 | 파일 경로                                                         |
| --- |---------------------------------------------------------------|
| macOS | `~/Library/Application Support/com.easytouse.ai-pet/branding.json` |
| Windows | `%APPDATA%\com.easytouse.ai-pet\branding.json`                |

```json
{
  "appTitle": "MyChatBot",
  "name": "사내 챗봇",
  "badge": "우리 회사",
  "theme": "mono"
}
```

- `appTitle`은 앱·창·트레이 이름이다. 프로젝트 루트 설정은 `npm run tauri -- build` 실행 시 앱 제품명과 설치 파일명에 적용된다. PC별 설정은 창과 트레이 이름에 적용되고 Finder와 Windows 설치 목록 이름은 다시 빌드해야 변경된다. 파일명으로 쓸 수 있는 1~40자로 지정한다. 기존 설정에 이 항목이 없으면 기존 `name`을 배포 앱 이름으로 사용한다.
- `name`은 로그인·대화창·답변 새창 본문에 표시되는 챗봇 이름이다. `appTitle`과 별도로 설정한다.
- `badge`는 이름 옆의 짧은 문구다. 기본값은 `CHAT`이며 `""`을 지정하면 숨긴다.
- `theme`은 `gold`(기본 노랑), `blue`(파랑), `green`(초록), `mono`(흰 화면에 검정 버튼과 회색 배지) 중에서 고른다. 버튼, 배지, 입력 포커스에 적용된다.
- 프로젝트 루트 설정을 변경하면 다시 빌드해야 한다. PC별 설정을 변경하면 앱을 트레이 메뉴에서 **종료**하고 다시 열면 된다. PC별 설정이 없거나 잘못된 항목은 배포 기본값을 사용한다. 빌드 시 지정한 `VITE_CHATBOT_NAME`, `VITE_CHATBOT_BADGE`가 있으면 이름·배지의 기본값으로 사용한다.

### 이미지와 설치 아이콘 교체

`pet.png`를 교체하면 기본 챗봇의 모습이 바뀐다. 드래그하거나 답변을 기다릴 때도 같은 캐릭터를 표시하려면 `assets/pet/`의 상태 이미지 두 장도 함께 교체한다.

Windows 설치 파일과 macOS 앱에 쓰는 아이콘은 `assets/pet/app-icon.png`에서 생성한다. `apps/desktop`에서 아래 명령을 실행하면 `src-tauri/target/generated-icons/`에 `icon.ico`와 `icon.icns`가 생성된다.

```sh
npm run tauri -- icon ../../assets/pet/app-icon.png --output src-tauri/target/generated-icons
```

macOS·Windows 앱을 빌드하면 이 변환이 자동으로 실행된다.

## 앱 빌드와 사용자에게 전달하기

아래 명령은 저장소를 내려받은 **프로젝트 루트**에서 시작한다. 프로젝트 폴더 이름과 절대 경로는 각자의 환경을 사용한다. 파일명의 `<앱 이름>`은 루트 `branding.json`의 `appTitle`, `<버전>`은 `apps/desktop/src-tauri/tauri.conf.json`의 `version`이다. `npm run tauri -- build`는 선택한 대상·프로필의 이전 `bundle/`과 기존 실행 파일을 먼저 정리한다. 마지막 빌드 로그에 출력되는 경로의 파일을 전달한다. 다른 대상이나 debug/release 폴더는 별개다. 빌드가 실패하면 해당 결과를 다시 빌드해야 하며, 배포 보관본은 미리 다른 위치에 복사한다.

| 받는 사용자 | 전달할 파일 | 사용 방법 |
| --- | --- | --- |
| macOS | 해당 Mac CPU에 맞게 빌드한 `<앱 이름>.app`을 압축한 ZIP | 압축을 풀고 앱을 Applications 폴더로 옮긴 뒤 실행한다 |
| Windows x64 | `<앱 이름>_<버전>_x64-setup.exe` | 설치 파일을 실행하고 설치된 앱에서 로그인한다 |

사용자에게는 앱만 전달하면 된다. Spring 서버는 별도로 실행되어 있어야 하며, 사용자 PC에서 서버 주소에 접속할 수 있어야 한다. 서버의 `.secret/`, 원본 문서, 검색 색인, 소스 코드는 앱과 함께 전달하지 않는다.

### 공통 준비

빌드하는 컴퓨터에 Node.js 22.12 이상, npm, Rust stable과 OS별 개발 도구가 필요하다. macOS는 Xcode 개발 도구, Windows는 Visual Studio C++ Build Tools와 WebView2가 필요하다. 상세 설치 방법은 [Tauri 공식 사전 준비](https://v2.tauri.app/start/prerequisites/)를 따른다.

```sh
cd apps/desktop
npm ci
npm test
```

이후 명령의 작업 폴더는 `apps/desktop`이다. Tauri 실행 스크립트는 Rust 기본 설치 경로인 `~/.cargo/bin`을 자동으로 찾는다. `CARGO_HOME`을 지정한 환경에서는 그 아래 `bin`을 사용한다.

- `npm run dev`는 브라우저 개발 화면을 실행한다.
- `npm run tauri -- dev`는 개발용 데스크톱 앱을 실행한다.
- `npm run build`는 프런트엔드 리소스를 만든다.
- `npm run tauri -- build`는 macOS에서 `.app`, Windows에서 NSIS 설치 파일까지 생성한다.

### 배포 서버 주소

`apps/desktop/.env.example`을 같은 폴더의 `.env`로 복사하고, 받는 사용자 PC에서 접속할 수 있는 서버 주소를 적는다. 아래 주소를 실제 주소로 바꾼다.

```dotenv
VITE_API_BASE_URL=https://chat-api.example.com
```

설정이 없으면 `http://127.0.0.1:9001`을 사용한다. `127.0.0.1`은 앱을 실행하는 PC 자신을 가리킨다. 빌드할 때 이 주소를 앱과 Tauri의 네트워크 허용 설정에 함께 적용한다. `.env`는 Git에서 제외되며, 주소를 바꾸면 앱을 다시 빌드한다. `VITE_` 값은 빌드 결과에 포함되므로 API 키나 비밀번호는 서버 설정에 둔다.

### macOS에서 macOS 앱 빌드

```sh
npm run tauri -- build
```

프로젝트 루트 기준 결과 폴더는 `apps/desktop/src-tauri/target/release/bundle/macos/`다. 여기에 생성된 `<앱 이름>.app`을 전달한다. .app 내부의 실행 파일만 따로 보내면 안 된다.

기본 빌드는 macOS 앱에 임시 서명한다. 배포할 때는 서명을 확인하고 ZIP으로 묶는다. 명령의 앱 이름은 루트 설정에서 읽는다.

```sh
APP_TITLE=$(node -p 'const b=require("../../branding.json"); b.appTitle ?? b.name')
APP_PATH="src-tauri/target/release/bundle/macos/$APP_TITLE.app"
codesign --verify --strict "$APP_PATH"
ditto -c -k --sequesterRsrc --keepParent "$APP_PATH" "src-tauri/target/release/bundle/macos/$APP_TITLE.zip"
```

**같은 폴더의 ZIP을 Mac 사용자에게 전달**한다. 임시 서명은 Apple Developer ID 서명과 공증을 대체하지 않으며, 다른 Mac에서 보안 확인이 필요할 수 있다.

기본 빌드는 빌드한 Mac의 CPU 아키텍처를 따른다. Apple Silicon에서 만든 앱과 Intel Mac용 앱을 구분해 전달한다. 양쪽 모두에 배포하려면 Intel용 별도 빌드나 Universal 빌드를 준비한다.

### Windows에서 Windows x64 설치 파일 빌드

PowerShell에서 실행한다. Rust의 Windows x64 MSVC 타깃이 필요하다.

```powershell
rustup target add x86_64-pc-windows-msvc
npm run tauri -- build --target x86_64-pc-windows-msvc
```

프로젝트 루트 기준 결과 폴더는 `apps/desktop/src-tauri/target/x86_64-pc-windows-msvc/release/bundle/nsis/`다. Windows x64에서 `--target` 없이 빌드하면 `apps/desktop/src-tauri/target/release/bundle/nsis/`에 생성된다. **그 안의 `*-setup.exe`를 Windows 사용자에게 전달**한다. `release/my-chat-bot.exe`는 앱 실행 파일이며, 배포에는 NSIS 폴더의 설치 파일을 사용한다.

### Mac에서 Windows x64 설치 파일 빌드

Mac에서도 NSIS 설치 파일을 교차 빌드할 수 있다. Homebrew가 설치된 환경에서 최초 한 번 빌드 도구를 준비한다. MSVC 런타임·SDK 다운로드가 필요하다.

```sh
brew install llvm lld nsis
cargo install --locked cargo-xwin
rustup target add x86_64-pc-windows-msvc
```

공통 준비와 `.env` 설정을 마친 뒤 `apps/desktop`에서 실행한다.

```sh
export PATH="$(brew --prefix llvm)/bin:$(brew --prefix lld)/bin:$PATH"
npm run tauri -- build --runner cargo-xwin --target x86_64-pc-windows-msvc
```

결과는 위 Windows 빌드와 같은 `apps/desktop/src-tauri/target/x86_64-pc-windows-msvc/release/bundle/nsis/`의 `*-setup.exe`다. 교차 빌드는 실험적 지원이므로 전달 전 실제 Windows에서 설치·로그인을 확인한다. [Tauri Windows 설치 파일 안내](https://v2.tauri.app/distribute/windows-installer/)

### 개발 빌드와 기존 설치 교체

`--debug`를 넣으면 `release/` 대신 `debug/`에 결과가 생성된다. 예를 들어 Mac 디버그 앱은 `apps/desktop/src-tauri/target/debug/bundle/macos/<앱 이름>.app`에 있다.

현재 서명 인증서가 없는 환경에서 만든 Windows 설치 파일은 미서명이다. WebView2가 없으면 설치 중 런타임 다운로드가 필요할 수 있다. 정식 배포 서명과 공증은 조직의 인증서·배포 정책에 맞춰 준비한다.

이전 앱을 트레이에서 종료한 뒤 새 앱을 연다. Windows에서 제품명까지 변경했다면 기존 앱을 제거한 뒤 새 설치 파일을 사용한다. 창 위치와 크기를 보존하려면 제거 시 앱 데이터 삭제를 선택하지 않는다. Mac의 Dock에 이전 앱이 고정돼 있으면 새 앱으로 교체한다.

## 구조
```text
.codex/                 # 프로젝트 에이전트 설정과 5개 역할
AGENTS.md               # 메인 총괄/파일 소유권/보안 지침
apps/desktop/           # TypeScript, Vanilla UI, Vite, Tauri 2
services/api/            # WebFlux, Security, Groq/Mock, POI/Tika/Lucene
assets/                 # 챗봇/브랜드 리소스 안내
pet.png                 # 사용자 원본
data/knowledge/        # 기존 업무 자료, 원본 보존/Git 제외
data/knowledge-submissions/ # 챗봇 제안 원문·상태·첨부파일, Git 제외
.local/knowledge-index/ # 파생 검색 인덱스/manifest/status/CSV, Git 제외
docs/                  # 요구사항·설계·인벤토리·QA·진행 기록
ops/                    # 구성/API smoke 검사
```

## 서버 실행
Java 17이 필요하다. 서버 작업 디렉터리는 `services/api`다. `services/api/src/main/resources/application.yml`은 Git과 JAR에 포함되는 공개 기본 설정이다. Spring Boot는 작업 디렉터리의 `.secret/application.yml`이 있으면 함께 읽고, 그 값으로 공개 기본값을 덮어쓴다. API 키와 비밀번호는 `services/api/.secret/application.yml` 또는 서버 환경 변수에만 둔다.

IntelliJ에서는 `services/api`를 Gradle 프로젝트로 열고 `com.mychatbot.MyChatBotApplication`을 실행한다. 이전 시작 클래스를 지정한 실행 설정이 있다면 새 클래스로 바꾼다. 서버 프로젝트 이름은 `my-chat-bot`이다.

로컬 API 인증은 Groq 키와 별개다. API 사용자·비밀번호·허용 프로젝트·요청 한도는 Git에서 제외하는 `services/api/.secret/application.yml`의 `pet.api`에서 관리한다. 환경변수로 재정의할 수도 있다. 개발용 일반 계정과 문서 제안 검토용 관리자 계정을 각각 설정할 수 있다.

```zsh
cd services/api
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
./gradlew bootRun
```

로컬 API 기본 주소는 `http://127.0.0.1:9001`이다. `pet.api.projects`와 `pet.api.admin.projects`는 일반 계정과 관리자 계정의 권한을 각각 설정한다. 비워 두거나 생략하면 모든 지원 프로젝트에 접근할 수 있고, 범위를 제한하려면 프로젝트 ID를 쉼표로 지정한다. 미분류·격리 문서는 검색에서 제외한다. LAN/운영 노출 전에 조직 인증·사용자별 권한 원천·HTTPS를 연결해야 한다.

현재 환경에서 Gradle 기본 캐시가 동작하지 않으면 `GRADLE_USER_HOME=/private/tmp/user-gradle`를 지정한다. 네트워크가 없는 개발 검증은 명시적으로 `AI_PROVIDER=mock`을 설정한다. 설정 누락이나 공급자 오류를 조용히 Mock으로 바꾸지 않는다.

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

macOS에서는 **⌘ + Shift + Space**, Windows에서는 **Ctrl + Shift + Space**로 고양이와 채팅창을 표시하거나 숨긴다. 다른 앱을 사용하는 중에도 동작하며, 앱이 실행 중이어야 한다. 별도로 연 답변 새창은 화면에 유지된다. 단축키 등록에 실패하면 트레이 메뉴에 `사용 불가`가 표시되고 트레이의 표시·숨김 메뉴를 사용할 수 있다.

앱을 시작하면 로그인 화면이 열린다. 서버 API 사용자와 비밀번호로 접속하면 앱을 종료하거나 로그아웃할 때까지 유지된다. 인증 정보는 앱 메모리에만 두고 대화와 함께 파일에 저장하지 않는다. 헤더의 `−`/`＋` 또는 창 모서리로 대화창 크기를 조절하고, 완료 답변의 **새창**를 누르면 별도 창에서 본문과 출처를 볼 수 있다. 서버를 이전 버전에서 실행 중이면 새 로그인 API를 사용하도록 재시작한다.

업무 질문은 권한 있는 문서 검색 후 근거와 출처를 제공한다. 질문 모드 선택 없이 입력한다. 권한 있는 문서가 검색되면 근거로 사용하고, 검색 결과가 없으면 AI를 호출하지 않고 관련 문서를 찾지 못했다고 안내한다. 일반 지식으로 업무 절차를 보충하지 않도록 요청한다. Enter 전송, Shift+Enter 줄바꿈, Escape 닫기, 진행 중 취소와 명시적 재시도를 지원한다. 답변/출처는 안전한 텍스트로 표시하며 Markdown/코드 문법 렌더링은 아직 제공하지 않는다.

대화창과 새창에서 파일명 옆의 `다운로드`를 누르면 파일 사본이 사용자 기기의 다운로드 폴더에 저장된다. macOS와 Windows 앱에서는 지원 문서를 저장한 뒤 기본 앱으로 연다. 자동 열기에 실패해도 저장된 사본은 다운로드 폴더에서 열 수 있다. 브라우저에서는 다운로드를 제공한다. 답변이 중간에 끊긴 경우 받은 내용은 부분 답변으로 표시하며 새창에서도 읽을 수 있다.

대화창에서 문서 수정이나 새 문서 작성을 요청하거나 `/수정`, `/새문서`를 입력한다. 기존 문서를 선택하거나 새 문서의 프로젝트를 선택하고 초안을 검토한다. 동의 후 제출한 제안은 `내 제안`에서 확인한다. 별도 관리자 계정으로 로그인해 승인하면 검색에 반영된다. 기존 문서 수정은 원본에 연결된 정정 문서로 기록한다. 관리자 계정 설정은 [서버 안내](services/api/README.md)를 참고한다.

### 계정 등급과 학습 자료 등록

서버 `.secret/application.yml`의 `pet.api.accounts`에 개인 계정을 등록한다. 비밀번호 예시의 환경 변수는 서버 실행 설정에서 지정한다. 설정 변경 후 서버를 재시작한다. 기존 단일 `pet.api.user`·`password`·`projects`·`grade`, 별도 `pet.api.admin` 및 `pet.api.knowledge.direct-publish-users` 대신 아래 계정 배열로 전환한다. `accounts`가 있으면 이 배열의 `grade`만 계정 등급을 정하며 이전 단일 계정 설정은 사용하지 않는다.

```yaml
pet:
  api:
    accounts:
      - username: userA
        password: "${CHAT_USER_A_PASSWORD}"
        grade: admin
        projects: []
      - username: userB
        password: "${CHAT_USER_B_PASSWORD}"
        grade: user
        projects: [operations]
```

- `admin`은 허용 범위에서 새 프로젝트를 만들고 본인이 제출한 자료를 바로 반영하며 다른 사용자의 제안을 검토한다.
- `user`는 허용 범위에 문서·프로젝트·하위 폴더의 생성을 요청한다. 기존 `member` 설정도 `user`와 같은 일반 사용자로 처리한다.
- `projects: []`는 전체 프로젝트를 뜻한다. 범위를 지정하면 해당 ID만 사용할 수 있고, 관리자는 목록에 미리 지정한 ID를 생성할 수 있다. ID는 영문 소문자나 숫자로 시작하며 `-`, `_`를 쓸 수 있다. 생성된 폴더는 서버 `knowledge.root` 바로 아래에 생기고 재시작 없이 프로젝트 목록에 나타난다.
- 계정별 `excluded-paths`로 허용 프로젝트 안의 특정 폴더 또는 파일을 제외할 수 있다. 경로는 `knowledge.root` 기준이며 대소문자를 구분한다. 아래 예시에서는 `user/ghkang`과 그 하위 전체를 차단하고 `user/jskim`, `user/ghkang2`는 허용한다. `projects: []`로 전체를 허용한 경우에도 제외가 우선한다.

```yaml
pet:
  api:
    accounts:
      - username: jskim
        password: "${CHAT_JSKIM_PASSWORD}"
        grade: user
        projects: [e2u, paye2u, wayup, user]
        excluded-paths: [user/ghkang]
```

- `excluded-paths`를 생략하거나 `[]`로 두면 기존 동작을 유지한다. `/`로 경로를 구분하며 절대 경로, `.`·`..` 경로 요소, 역슬래시, 와일드카드, 끝의 `/`는 허용하지 않는다. 실제 폴더/파일 이름과 동일하게 입력한다.
- 제외 경로는 검색 후보를 고르기 전에 적용하며 AI 문서 선택, 원본 다운로드, 해당 원본의 보완 제안·승인 자료·첨부파일·내 제안·관리자 검토에도 적용한다. 프로젝트 전체(예: `user`)를 제외하면 해당 프로젝트의 새 문서 등록과 프로젝트 생성도 차단한다. 하위 폴더만 제외한 경우 프로젝트에 직접 등록하는 새 문서는 기존 프로젝트 범위를 따른다.
- 적용하려면 수정된 서버 JAR와 서버 `.secret/application.yml`을 반영하고 서버를 재시작한다. 시작 시 기존 원본으로 접근 경로 색인을 다시 생성한다. 원본 자료와 제안 저장소는 이동하거나 삭제하지 않는다. 이미 사용자 PC에 다운로드했거나 화면에 받은 내용은 회수하지 않는다.

- `accounts`를 사용하면 기존 단일 계정 및 관리자 계정 설정을 대체한다. 기존 제안을 계속 보려면 전환 후에도 기존 사용자명을 유지한다. `accounts`가 없는 설치는 기존 로그인·승인 설정을 사용한다.

### 프로젝트 트리와 폴더 생성 요청

**학습 자료 관리 → 새 문서 작성**에서 왼쪽 트리의 폴더를 선택한다. 선택 경로는 문서 작성 영역에 표시된다. 폴더 옆 `+`는 하위 폴더를, `+ 프로젝트`는 최상위 프로젝트를 추가한다. 생성 위치와 이름을 확인한 뒤 제출한다. 좁은 창에서는 트리와 작성 영역을 세로로 배치한다.

- `grade: admin`: 허용 범위 안에서 바로 생성하고 다른 사용자의 요청을 승인·반려한다.
- `grade: user`: 생성 요청을 제출하고 **폴더 생성 요청**에서 처리 상태를 확인한다. 기존 `member`는 호환되는 일반 사용자 등급이다.
- `pet.api.accounts`를 사용하는 경우 위 등급으로 통합한다. `direct-publish-users`를 폴더 생성 권한으로 사용하지 않는다. 기존 단일 계정 설정은 호환을 유지한다.
- 최상위 프로젝트 ID는 기존 영문 소문자·숫자·`-`·`_` 규칙을 유지한다. 하위 폴더에는 한글과 공백을 쓸 수 있으며 서버에서 안전한 상대 경로와 부모 폴더를 검사한다. 제외된 폴더와 하위 경로는 트리·생성·제안·검색·다운로드에서 차단한다.
- 새 프로젝트가 승인되어도 요청자에게 프로젝트 권한을 자동 추가하지 않는다. `projects: []` 계정은 전체 허용 정책을 따르고, 프로젝트 목록을 지정한 계정은 관리자가 YAML 허용 목록에 해당 프로젝트를 추가하고 서버를 재시작해야 접근할 수 있다.
- 문서의 선택 경로는 `folderPath`로 보관한다. 제안 원문과 업로드 첨부파일은 `knowledge-submissions`에 유지하고, 승인되면 선택한 실제 자료 폴더에 Markdown과 첨부 사본도 생성한다. 새 문서의 검색과 다운로드에도 선택한 폴더의 제외 권한을 적용한다.
- 폴더 요청은 `knowledge.store-path/folder-requests.json`에 보관한다. 기존 제안·첨부파일과 함께 백업한다. 화면과 API를 사용하려면 새 서버 JAR와 새 데스크톱 앱을 함께 배포한다.

**학습 자료 관리 → 새 문서 작성**에서 프로젝트와 설명을 입력하면서 설명 입력란 아래에 파일을 첨부하고 초안을 미리 본다. 기존 문서를 보완할 때도 첨부할 수 있다. PDF, DOCX, XLS/XLSX, 기존 지원 텍스트 파일과 PNG/JPG를 최대 3개, 파일당 8MB까지 올릴 수 있다. 이미지는 검색용 설명이 필수이며, 화면에 개인정보가 있다면 가리고 등록한다.

승인된 문서의 본문과 이미지 설명을 검색에 반영한다. 첨부파일은 검색 참고 자료 아래에 표시된다. 답변 완료 후 대화창과 답변 새창에서 관련 이미지가 자동으로 나타나며 클릭해 확대하거나 파일을 다운로드할 수 있다. 관리자 검토 화면에서도 첨부 내용을 확인할 수 있다. 제안을 철회하면 공유 검색과 출처 다운로드에서 제외된다. 제안 원문·상태·작성자는 기본 `data/knowledge-submissions/proposals.json`, 첨부파일은 `data/knowledge-submissions/attachments/`에 저장한다. 기본 위치는 `knowledge.root`와 같은 부모 폴더의 `knowledge-submissions`이며 `knowledge.store-path`로 별도 지정할 수 있다. 이 폴더를 백업하면 검색 색인이 사라져도 서버 재시작으로 제안과 승인 자료를 복원해 재색인할 수 있다. 승인 대기·반려·철회 상태도 보존한다.

기존 버전의 색인 폴더에 있는 `proposals.json`과 연결된 첨부파일은 새 서버가 처음 실행될 때 영구 저장소로 복사한다. 이관이 끝나기 전에는 기존 색인 폴더를 삭제하지 않는다. 서버는 이후 영구 저장소를 사용한다.

이미지는 긴 변이 1920px를 넘으면 비율을 유지해 축소한다. PNG는 무손실 압축, JPEG는 품질 85%로 저장하며 이미지 설명을 검색에 반영한다. 원본 크기가 필요한 이미지는 별도 보관한다.

### 승인 문서의 실제 파일 보관

승인된 제안은 기존 UUID를 문서 식별자로 사용한다. 작성자별 순번을 새로 만들지 않으며 같은 제목이나 작성자라도 다른 제안의 본문·첨부파일과 섞이지 않는다.

```text
data/knowledge/user/ghkang/개발가이드/
├── 가맹점 앱 이관 절차--<제안 UUID>.md
└── attachments/
    └── <동일한 제안 UUID>/
        ├── 1--화면.png
        └── 2--참고자료.pdf
```

- 승인 전에는 실제 자료 폴더에 파일을 만들지 않는다. 관리자 바로 반영 또는 승인 시 본문과 첨부 사본을 생성하며 본문에는 제안 ID, 작성자, 생성·승인 시각, 첨부 상대 경로를 기록한다. 같은 이름의 첨부파일은 제안 안의 위치 번호로 구분한다.
- 기존 승인 제안도 서버 시작·동기화 때 파일을 생성한다. 기존 데이터에서 폴더 정보가 없는 새 문서는 프로젝트 루트에, 원본 보완 제안은 확인 가능한 원본의 부모 위치에 별도 문서로 보관한다. 원본 본문을 덮어쓰지 않는다.
- `knowledge-submissions/proposals.json`과 업로드 첨부파일이 제안 이력의 기준이다. `published-files.json`은 앱이 생성한 파일의 정확한 경로와 해시를 추적한다. 이 관리 파일을 기준으로 생성 본문과 해당 UUID의 첨부 폴더를 일반 원본 스캔에서 제외해 중복 검색을 막는다. 첨부 폴더에 임의로 더한 파일도 자동으로 공식 자료가 되지는 않는다.
- 철회되거나 원본 버전이 달라져 비활성화된 제안의 관리 파일은 `knowledge-submissions/published-history/<제안 UUID>/`에 보관한다. 여러 발행 시도가 있으면 각 파일 묶음을 별도 버전 경로로 구분한다. 제안 이력과 업로드 원본은 유지한다.
- 기존 파일과 충돌하거나 생성 사본을 외부에서 수정한 경우 임의로 덮어쓰거나 삭제하지 않는다. 활성 승인본의 파일 충돌·무결성 오류는 해당 제안만 검색과 공유 다운로드에서 제외한다. 정상 원본과 다른 승인 제안은 계속 제공한다. 생성 사본을 승인된 내용 그대로 복원하면 다음 동기화에서 자동으로 다시 포함한다. 전체 소유 경로를 확인할 수 없는 관리 목록 누락·손상이나 원본/색인 저장소 장애는 전체 검색을 중단한다. 앱에서 생성한 파일을 직접 편집해도 `proposals.json`의 승인 본문을 바꾸지는 않는다.
- `data/knowledge`와 `knowledge-submissions` 전체를 함께 백업한다. 관리 파일과 초기화 표시도 보관해야 철회·충돌 상태를 안전하게 복원할 수 있다.

## 파일 기반 RAG

MyChatBot은 **Lucene 키워드 검색 기반 RAG(검색 증강 생성)**를 사용한다. 파일을 등록할 때 모델을 재학습하는 대신, 문서에서 뽑은 텍스트를 로컬 검색 색인에 저장하고 질문마다 관련 부분만 AI에 전달한다. 검색에는 벡터 DB나 임베딩 API를 사용하지 않는다.

1. **색인** — `data/knowledge/`의 파일을 읽어 엑셀은 시트·행, Markdown은 제목과 본문 구간 단위로 나눈다. 검색 색인은 `.local/knowledge-index/`에 두고, 관리자 승인과 공유 동의를 거친 문서 제안도 해당 프로젝트에 색인한다.
2. **검색** — 로그인 계정의 프로젝트 권한으로 범위를 제한한 뒤 Lucene의 한국어 형태소 분석기(Nori)와 정확한 키워드 일치를 사용한다. 질문의 핵심 용어가 같은 구간에 함께 나오거나 가까이 있으면 우선한다.
3. **답변과 출처** — 관련 문서 조각을 최대 4개, 합계 최대 12,000자로 제한해 질문과 함께 선택된 AI 공급자에 보낸다. 모델에 근거 번호를 인용하도록 요청하고, 화면의 **검색 참고 자료**에는 AI에 전달한 파일명과 확인 가능한 시트·행을 표시한다. 이 목록은 검색 근거이며 모델이 답변에 실제로 인용한 자료와 다를 수 있다. 원본 다운로드 시 권한과 파일 버전을 다시 확인한다.

서로 다른 문서가 여러 개 검색되면 먼저 번호가 붙은 문서 선택 목록을 보여준다. 문서 제목을 누르거나 `1`, `2번`처럼 입력하면 문서별 답변을 펼치거나 닫을 수 있다. 처음 펼칠 때 선택한 문서의 검색 구간만 AI에 전달한다. 같은 질문의 답변은 화면에서 유지되어 다시 펼쳐도 재요청하지 않으며, 각 문서의 새창·복사·첨부파일을 따로 사용할 수 있다. 문서가 하나라면 바로 답변하며, 같은 문서의 여러 관련 구간은 함께 사용할 수 있다. 선택 후에는 프로젝트 권한과 문서 버전을 다시 확인한다.

검색어는 한국어 형태소 분석으로 정리하며 `앱`, `웹`처럼 한 글자인 명사도 유지한다. 같은 용어가 여러 필드에서 일치해도 용어 수는 한 번만 센다. 핵심 용어가 4개 이하이면 전부, 5개 이상이면 최소 80%가 같은 검색 구간에서 일치해야 후보가 된다. 테이블·코드 식별자와 명시한 파일명은 정확히 일치해야 한다. 제목·절 제목과 가까운 단어 일치에 가중치를 주고 승인 상태만으로 관련도를 올리지 않는다. 일반 질문에서는 폴더 경로를 관련도 근거로 사용하지 않으며 파일명을 명시한 검색은 지원한다.

검색 기준 변경 후 첫 서버 실행에서는 원문과 제안 저장소로부터 자동 재색인한다. 원본 자료와 `data/knowledge-submissions`는 보관한다.

문서를 추가하거나 바꾸면 색인만 갱신된다. 키워드 기반이므로 문서와 질문이 같은 뜻을 전혀 다른 표현으로 쓰면 관련 자료를 놓칠 수 있다.

## 문서와 동기화
사용자가 보관한 기존 `data/knowledge` 원본은 읽기만 한다. 앱에서 생성한 승인 사본은 위의 소유 경로·해시 기록으로 별도 관리한다. 메타데이터 분류·색인·CSV는 별도 `.local`에 생성한다. XLSX/XLS는 POI로 시트/행/열/병합/수식과 캐시값을 구분하고 수식을 재계산하지 않는다. Markdown은 제목 계층과 코드 문맥을 보존한다. DOCX/PDF는 제한된 Tika 추출, Java/XML/JSON/TXT/SQL은 텍스트 추출을 사용한다.

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
