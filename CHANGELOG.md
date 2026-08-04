# 업데이트 내역

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
