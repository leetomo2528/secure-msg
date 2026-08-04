# 업데이트 내역

## v0.6.1 (2026-08-05) — 에뮬레이터 실검증에서 발견한 연동 버그 수정

- **웹: 다른 기기가 만든 대화가 사이드바에 안 보이던 문제** — 게이트웨이가 새 SMS 스레드를 열어 메시지를 릴레이하면 웹이 메시지는 저장하지만 대화 목록을 갱신하지 않던 문제를 `message_new` 시 자동 갱신으로 수정 + 회귀 테스트 추가 (릴레이 E2E 7개)
- **Android: 기본 SMS 판정 통일** — `Telephony.Sms.getDefaultSmsPackage`가 일부 이미지에서 RoleManager와 어긋나 브리지가 idle로 남던 문제를 RoleManager 기준으로 통일(레거시 값은 로그로 보존)
- 로컬 테스트용 HTTP 허용 목록에 에뮬레이터 호스트 별칭 10.0.2.2 추가
- 내보내기 파일명 한글(유니코드) 보존
- **에뮬레이터 실시간 연동 검증**: SMS 수신→서버 릴레이→웹 실시간 복호화 표시(새 스레드 자동 포함), 웹에서 추가한 차단 키워드가 폰에 동기화되어 실제 수신 차단(격리)에 적용, 폰에서 추가한 키워드가 서버에 반영 — 전 방향 확인

## v0.6.0 (2026-08-05) — 기기 간 연동 강화 + 신규 기능 5종

### 연동 문제 해결
- **차단 키워드·발신번호 기기 간 동기화**: 서버에 계정 단위 `block_rules` 테이블 신설, 웹·Android 양쪽에서 추가/삭제 시 REST + `blocklist_updated` 소켓 팬아웃으로 즉시 공유. Android는 수신 차단 판정(SmsReceiver/MMS)에 공유 규칙을 합산 적용
- **연동 전 구간 E2E 테스트**(`frontend/src/e2e/relay.e2e.test.ts`): 실제 로컬 서버에서 웹 가입→게이트웨이 등록→SMS 릴레이→웹 복호화 표시→차단 규칙 공유→대화 이름 변경까지 검증(6개 시나리오). 서버 테스트 32개(+7) 전부 통과
- 대화 이름 변경(`POST /api/conversation/rename`) 시 멤버 기기 전체에 `conv_updated` 팬아웃 — Android 스레드 이름도 갱신

### 신규 기능
1. 차단 목록 동기화 (키워드 + 발신번호, 위 연동 수정 포함)
2. 웹 데스크톱 알림 (권한 요청·설정 지속, 포커스 중일 때는 조용히)
3. 메시지·대화 통합 검색 (사이드바)
4. 대화 이름 변경 (웹 헤더 인라인 편집 → 전 기기 반영)
5. 대화 내보내기 (CSV with UTF-8 BOM / JSON)

### 기타
- Android: debug 빌드에 "SMS 수신 시뮬레이션" 개발자 도구 추가(SIM 없이 수신 pipeline 검증), 로컬 서버 테스트용 localhost cleartext만 허용(network security config)
- Android versionCode 8 / versionName 0.6.0, 유닛 테스트 25개·lint·빌드 통과
- 웹 테스트 47개(릴레이 E2E 6개 포함)·빌드 통과, Oracle 백엔드 재빌드·프론트 배포 완료

## v0.5.0 (2026-08-05) — 테마: 라이트·다크·시스템

- **테마 전환 추가**: 사이드바 헤더의 테마 버튼으로 **시스템 설정 따라가기 / 라이트 / 다크** 순환 전환 (선택값은 localStorage에 저장, 새로고침 후에도 유지)
- 다크 전용이던 스타일을 **CSS 변수 디자인 토큰**으로 전환(`src/index.css` :root/.dark, Tailwind color 매핑): 배경·표면 오버레이·텍스트 4단계·강조/위험색·그림자까지 라이트/다크 세트 정의
- 첫 페인트 전 인라인 스크립트로 테마 클래스 적용(FOUC 없음), `theme-color` 메타도 테마에 맞춰 갱신, OS 색상 모드 변경 시 시스템 모드 실시간 반영
- headless Chromium으로 4개 조합(다크/라이트/시스템+OS다크/시스템+OS라이트) 전부 스크린샷 검증, 테스트 41개·빌드 통과 후 Oracle 배포

## v0.4.4 (2026-08-05) — 웹 UI 리디자인

- 전체 웹 UI 재디자인: 납작한 slate+원색 cyan 테마를 깊이 있는 다크 팔레트(night #0a0f16 계열) + teal→sky 그라디언트 브랜드로 교체
  - 새 로고 마크(방패+메시지)·그라디언트 CTA·글로우 포커스, 대화 목록 이니셜 그라디언트 아바타
  - 채팅 버블: 내 메시지 그라디언트 버블·상대 메시지 반투명 링 버블, 말풍선 비대칭 라운드, 전송 버튼 아이콘화(전송 중 스피너)
  - 온보딩: 상단 라디얼 글로우, 세그먼트 탭 필 스타일, 입력 필드 링 포커스
  - 사이드바 패널(차단 키워드·기기 관리) 공통 접이식 카드(`ui.tsx` CollapsibleCard/Segmented)로 통일
  - 앱/PWA 아이콘 신규 제작(SVG + 192/512 PNG), theme-color 통일
- 로직 무변경(스타일·마크업만), headless Chromium으로 데스크톱/모바일 전 화면 스크린샷 검증, 테스트 41개·빌드 통과 후 Oracle 배포

## v0.4.3 (2026-08-05) — 웹 수정

- **웹 로그인·가입 마무리 단계 실패 버그 수정**: `meta` IndexedDB 스토어가 out-of-line 키(keyPath 없음)인데 `setMeta`가 키를 생략하고 put해 `DataError: Data provided to an operation does not meet requirements.`가 발생, 모든 웹 로그인/가입이 기기 키 저장 단계에서 실패하던 문제 (frontend/src/store/db.ts). 명시적 키 전달로 수정
- 회귀 테스트 2개 추가(meta round-trip·덮어쓰기) — 웹 테스트 36개 전부 통과
- 로그인 실패 반복으로 서버에 쌓인 고아 기기(프라이빗 키 미저장) 3건 정리
- **인증 E2E 테스트 추가** (`frontend/src/e2e/auth.e2e.test.ts`): 실제 로컬 Flask relay를 자식으로 띄우고 회원가입→기기 저장→재로그인→새 브라우저 로그인→잘못된 비밀번호→중복 가입 전체를 실 HTTP·실 Argon2id·실 IndexedDB로 검증. 목 기반 단위 테스트가 놓친 계층 간 버그(라이브러리 빌드·와이어 포맷·브라우저 저장소 시맨틱)를 앞으로 이 단계에서 차단

## v0.4.2 (2026-08-05)

- **Android 로그인·가입 불가 버그 수정**: Lazysodium의 String `cryptoPwHash` 오버로드가 결과를 표준 base64(`+`, `/`, `=`)로 인코딩해, base64url만 허용하는 서버(`B64U_RE = [A-Za-z0-9_-]+`)가 `pw_hash must be base64url for 32 bytes`로 거부하던 문제. raw 버퍼 오버로드로 교체 후 웹과 동일하게 url-safe base64(패딩 없음)로 인코딩 (CryptoUtil.kt)
- 인코딩 계약 회귀 테스트 추가(CryptoEncodingTest): 32바이트 → 43자 url-safe, `+`/`/`/`=` 불가, 라운드트립 — Android 테스트 25개 전부 통과
- Android versionCode 7 / versionName 0.4.2

## v0.4.1 (2026-08-05)

- 비밀번호 정책 명시: **총 8자 이상, 영문·숫자·특수문자 자유롭게 조합 가능** (문자 종류·조합 제한 없음). 웹/Android 입력 라벨·오류 메시지 갱신
- **웹 회원가입/로그인 불가 버그 수정**: npm `libsodium-wrappers` 기본 빌드에 `crypto_pwhash`(Argon2id)가 빠져 웹에서 비밀번호 해시 자체가 실패하던 문제를 `libsodium-wrappers-sumo` 교체 + Vite/Vitest alias 수정으로 해결 (vite.config.ts·vitest.config.ts·crypto/keys.ts·package.json)
- 비밀번호 정책 회귀 테스트 2개 추가 (8자 미만 거부, 영문+숫자+특수문자+한글 혼용 8자 이상 허용) — 웹 테스트 34개 전부 통과
- Android versionCode 6 / versionName 0.4.1

## v0.4.0 (2026-08-04)

- 전수 코드 리뷰 및 수정
  - 서버 6건: X-Forwarded-For 스푸핑 rate limit 우회, 로그인 타이밍 오라클(더미 bcrypt), 폐기 기기 소켓 미차단, typing 팬아웃 발신자 타 기기 누락, live envelope created_at 불일치, requirements transitive 핀
  - 웹 8건: REST 401 시 로그아웃 훅, selectConversation/syncConversation/sendContent 스테일 상태 레이스, IndexedDB read-modify-write 원자화, Caddyfile.frontend CSP 등 보안 헤더 보강
  - Android 8건: MMS PDU WSP 텍스트 이스케이프(한글 subject 깨짐), MmsSender/MmsReceiver PendingIntent 콜백 병합 버그, carrier part 결과 원자적 consume, OutgoingSmsDispatcher 트랜잭션 원자화, 수신 MMS 무한재시도 루프 제거, FGS outbox 루프 이중시작 가드
- 회귀 테스트 31개 추가 — 서버 25·웹 32·Android 23 테스트 + Lint + Debug/Release 빌드 통과, 실기기 설치

## v0.3.2 (2026-08-04)

- JNA가 일반 JAR로 패키징되어 ARM64 `libjnidispatch.so`가 APK에서 누락되던 문제 수정: Lazysodium의 JNA transitive JAR 제외 + JNA AAR 교체, LinkageError 발생 시 안내 화면 처리

## v0.3.1 (2026-08-03)

- Android 로그인 직후 크래시 수정: SMS 권한·기본 SMS 역할 미완료 상태에서 `remoteMessaging` foreground service를 무조건 시작하던 경로 차단, 서비스 SecurityException/RuntimeException 방어 추가

## v0.3.0 (2026-08-03)

- MMS 구현: Android MMS 수신·발신(통신사 MMSC), 첨부파일 기기 간 동기화, SENT·DELIVERED 상태 리포트
- Room durable `relay_outbox` 재시도, MMS WAP PDU 작성·보정, Release R8 APK
- 서버 `carrier_status` 권한·영속화, 웹 MMS UI·첨부파일 표시
- Oracle DB migration 및 재배포, 외부 Socket.IO·보안 헤더 검증

## v0.2.0 (2026-08-03)

- 코드 리뷰 기반 운영 보강: 서버 인증·Socket.IO 중복 전송·메시지 멱등성·속도 제한·계정당 단일 Android SMS 게이트웨이
- 웹 재동기화·차단 규칙·세션 처리 개선, Android 암호화 오류·수신 유실·중복·오프라인 동기화 수정
- Oracle 재배포 및 공개 WebSocket·보안 헤더 검증

## v0.1.0 (2026-08-03)

- 최초 구현: Android 기본 SMS 역할 기반 SMS_DELIVER 수신, 키워드·발신번호·오프라인 스팸 차단과 로컬 격리함, 기기별 envelope E2E 암호화 relay(Flask + Socket.IO), 웹 PWA 채팅·새 SMS 작성
- 서버/웹/Android 테스트 통과, Caddy 자동 TLS로 Oracle Cloud 배포(msg.yunjelee.com)
