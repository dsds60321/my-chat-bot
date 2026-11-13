# MyChatBot desktop

Tauri 2 + Vanilla TypeScript + Vite 8 미니펫입니다. 루트 `pet.png` 원본을 그대로 참조합니다. 시작 화면에서 서버 계정으로 로그인합니다. 서버 로그인은 앱을 종료하거나 로그아웃할 때까지 유지됩니다. 로컬 API 기본 주소는 `http://127.0.0.1:9001`입니다. 대화와 API 인증은 메모리에서만 유지하고 Groq 키는 앱에 넣지 않습니다.

```sh
npm ci
npm test
npm run build
npm run dev                      # 브라우저 UI 체험, 127.0.0.1:1420
npm run tauri -- dev              # 실제 데스크톱
npm run tauri -- build --debug --no-bundle
```

Node 22.12 이상과 검증된 Rust stable 1.99.1, 해당 OS의 Tauri 빌드 도구가 필요합니다. Node 23.7.0에서 설치/프론트 빌드/테스트를 확인했습니다. Vite 8.3.8의 공식 engines(`^20.19.0 || >=22.12.0`)에 맞춥니다. 재현 가능한 의존성은 npm lockfile/Cargo.lock에 기록합니다. 테스트는 Node 내장 test runner와 type stripping을 사용하며 Node 23에서는 experimental 경고가 표시됩니다.

펫 클릭 또는 Enter/Space로 질문창을 열고, 닫기/Escape로 닫습니다. Enter는 전송, Shift+Enter는 줄바꿈입니다. IME 조합 Enter는 전송하지 않습니다. 6px 이상 이동은 드래그로 구분합니다. 브라우저 체험에서는 OS 창 이동과 트레이를 제공하지 않습니다.

질문은 하나의 입력창에서 보냅니다. 관련 문서가 있으면 출처를 표시하고, 없으면 일반 답변을 시도합니다. 완료 답변은 **새창**으로 띄울 수 있습니다. 로그인 화면의 API 사용자/비밀번호는 서버 PET_API_USER/PET_API_PASSWORD와 같아야 하며 Groq 키와 별개입니다. 스트리밍, 응답 취소, 실패 후 재시도, 중복 전송 방지를 지원합니다. Markdown과 코드는 현재 안전한 일반 텍스트로 표시하며 복사 실패 시 선택하여 수동 복사가 가능합니다.

실제 데스크톱은 128×128 투명 창을 시작하고 팝업을 열면 최대 392×580으로 확장합니다. 작업영역과 배율을 기준으로 크기/위치를 제한하고 음수 좌표의 보조 모니터도 허용합니다. 플러그인은 창 위치와 크기만 저장하며, 저장 실패가 팝업 사용을 막지 않도록 처리합니다. 트레이 메뉴는 표시/숨김/종료를 제공하며 창 닫기 요청은 숨김으로 바꿉니다. 중복 실행은 기존 프로세스를 표시합니다.

macOS 투명을 위해 Tauri의 `macos-private-api`가 필요합니다. 현재 배포 번들은 비활성입니다. 서명/스토어 배포, 실기기 다중 모니터/화면 배율/스크린리더 동작은 별도 검증이 필요합니다.

배포 빌드 명령, 서버 주소 설정, macOS·Windows 산출물 경로와 전달 방법은 [루트 README의 앱 빌드와 사용자에게 전달하기](../../README.md#앱-빌드와-사용자에게-전달하기)를 따른다. 앱 이름은 프로젝트 루트 `branding.json`의 `appTitle`에서 지정한다.

화면의 이름·배지·테마는 [루트 README](../../README.md#이름배지테마-변경)의 `branding.json`으로 설정합니다.
