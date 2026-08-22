# 업데이트 내역

## v0.11.1 (2026-08-21) — 하나의 라이트 팔레트

Android·웹 로그인·웹 앱 화면이 서로 다른 세 가지 톤으로 갈라져 있던 것을 웹 앱 셸(`family.css`) 기준의 라이트 팔레트 하나로 통일했다.

- **Android를 라이트로 이식.** `Sm` 팔레트 전면 교체(`#F6F7FB` 배경, 흰 카드, blue-600 액센트). 액센트 역할을 분리해 텍스트·포커스·프로그레스는 blue-600, 브랜드 채움(워드마크·아바타·내 말풍선)은 인디고를 쓴다.
- **다크 전제였던 지점들 수정.** 그라디언트 버튼의 검은 라벨(blue-600 위 3.09:1)을 흰색으로, 수신 말풍선에 테두리 추가(흰 배경 위 1.05:1로 사라졌음), 입력 필드·고스트 버튼 테두리를 3:1 이상으로, 진행 표시줄 트랙을 blue-100으로, 카드에 그림자 추가(다크에서는 surface가 더 밝아 분리됐지만 라이트에서는 휘도 차가 5% 미만).
- **상태바 아이콘.** `Theme.DeviceDefault.NoActionBar`는 DayNight이 아니라 다크 고정이라 상태바 아이콘이 항상 흰색이었다. 라이트 배경에서 완전히 사라지므로 `Theme.SecureMsg`(Light) 신설 + `enableEdgeToEdge(SystemBarStyle.light)`로 처리. 콜드 스타트의 어두운 플래시도 함께 사라진다.
- **웹 팔레트 단일화.** 토큰을 `index.css :root` 한 곳으로 모으고 `.app-shell`·`.family-shell`의 중복 재선언과 하드코딩 다크 rgba를 제거. 온보딩만 틸(`#0f766e`)이고 앱은 블루였던 불일치 해소.
- **다크 테마 제거.** `.dark` 토큰 블록과 프리페인트 분기를 걷어냈다. 앱 셸은 애초에 다크 변형이 없어 토글이 절반만 동작했고, `src/theme.ts`는 아무 곳에서도 import되지 않는 죽은 코드였다(삭제).
- 브랜드 그라디언트 끝점을 `#6366f1` → `#5b52e8`로. 흰 라벨이 전자에서 4.47:1로 AA에 못 미친다.
- Android versionCode 22 / versionName 0.11.1. 웹 153 / Android 159 테스트 통과, lint·debug APK 빌드 통과.

## v0.11.0 (2026-08-20) — QR 기기 페어링 · 온보딩 리디자인 · 보안 정리

### QR 기기 페어링 (docs/QR_PAIRING_DESIGN.md)

- 새 기기가 QR을 띄우고, 기존 기기가 카메라로 스캔 → 양쪽 화면의 5×6자리 안전번호를 사람이 비교 → 승인. 기기 지문을 눈으로 대조하던 절차를 대체한다.
- 승인 서명이 `securemsg-device-approval-v2` 명세문으로 페어링 세션(nonce 2개)을 바인딩해 다른 스캔으로 재생할 수 없다. v1 승인은 계속 수용되며 한 체인에 섞여도 검증된다.
- **웹·Android 클라이언트가 v2 인증서를 검증하지 못하던 문제 해결.** 서버는 v2를 발급할 수 있었지만 클라이언트 검증기는 v1 정본만 만들었다 — v2 승인이 하나라도 생기면 해당 계정의 전 기기가 디렉터리 검증에 실패해 잠겼다.
- 웹 승인 기기도 스캔 가능(`BarcodeDetector`, 미지원 브라우저는 QR 내용 붙여넣기). Android는 CameraX + ML Kit 오프라인 디코딩, 카메라 권한은 스캐너를 열 때만 요청.
- 안전번호 골든 벡터를 웹·Android에, 승인 명세문 v1/v2 형식을 서버까지 3면에 고정.

### 보안

- **`/api/password-reset/confirm`이 코드 검증 전에 bcrypt를 계산하던 문제 수정.** 레이트리밋 키도 호출자가 정하는 `challenge_id`에서 `username`으로 교체 — 이전에는 challenge_id만 바꾸면 버킷이 새로 생겨 단일 워커 CPU를 무제한으로 소모시킬 수 있었다.
- **`/api/conversation/<cid>/members`에서 다른 계정의 기기 이름 제거.** 아무 사용자나 상대를 대화 멤버로 지목해 기기 목록·기기명을 열람할 수 있었다. 봉투 암호화에 필요한 건 키와 SID뿐이다.
- **대화 생성의 사용자 열거 차단.** 존재하지 않는 멤버와 형식 오류가 같은 응답을 반환한다.
- **이메일 미인증 `/api/register` 엔드포인트 제거.** 가입은 이메일 인증 경로만 남는다.
- **`/api/key-directory` 404 다운그레이드 경로 제거.** 적대적 릴레이가 이 응답만 404로 돌려 로그인 시점의 디렉터리 검증·신원 pin을 건너뛸 수 있었다.
- 차단 규칙 계정당 상한(`SECUREMSG_MAX_BLOCK_RULES`, 기본 500), 만료·소비된 챌린지 4종 자동 정리(`SECUREMSG_CHALLENGE_RETENTION`, 기본 24시간) — 이전에는 삭제 경로 자체가 없었다.

### 온보딩·디자인

- 로그인/가입 화면을 랜딩형에서 폼 중심으로 재작성. 보안 설명 11개 → 1개, 모바일 375px 첫 화면에 입력 2개와 버튼이 모두 들어온다(이전엔 0개).
- 온보딩이 사용자 테마를 따른다. 다크 강제 override와 장식용 orb 제거.
- **경고 문구가 실제 기능과 모순되던 것 수정**: "비밀번호 재설정·계정 복구 수단이 없습니다"가 v0.10.6의 이메일 재설정 도입 후에도 남아, 바로 아래 "비밀번호를 잊으셨나요?" 버튼과 충돌했다. 경고는 가입 모드에서만 표시한다.
- `theme.ts` 기본값을 `index.html` 프리페인트와 맞춰 `system`으로 통일(첫 방문 플래시 제거).

### 개발 편의

- `SECUREMSG_EMAIL_PROVIDER=console`: 메일 제공자 계정 없이 인증 코드를 로컬 아웃박스/로그로 받는다. 프로덕션에서는 기동 자체가 거부된다.
- `/api/conversations`의 N+1 쿼리 제거.

- 서버 79 / 웹 153 / Android 159 테스트 통과, Android versionCode 21 / versionName 0.11.0, Android lint·debug APK 빌드 통과.

## v0.10.8 (2026-08-17) — 보안 경직화·신뢰 문서·CI

전 코드베이스 리뷰 기반 보안 패치와 "신뢰할 수 있는 E2EE SMS 동기화" 로드맵 1차 산출물.

보안
- 이메일 인증·비밀번호 재설정 4개 엔드포인트에 rate limit 추가(발송 5/분·검증 10/분, IP+대상 기준) — 메일 폭격·코드 무차열 대입 차단
- Socket.IO 연결 거부에 기계 판독 `auth_rejected:` 코드 접두 추가, 웹·Android 클라이언트 우선 판정(구 문구 매칭은 폴백 유지)
- Android 로그아웃 시 복호화 로컬 평문 삭제(메시지·스레드·차단 격리함·ACK 완료 outbox 평문). 기기 키는 보존해 재로그인 후 서버 이력 재동기화
- 웹 CSV 내보내기 스프레드시트 수식 인젝션 방지(=+-@ 탭 CR 접두 중화), 첨부 이미지 인라인 렌더를 png/jpeg/gif/webp/bmp 화이트리스트로 제한
- Android 로그인 실패를 "미가입"으로 오해석해 인증 메일을 몰아 보내던 폴백 수정 — 가입 이메일 미입력 시 자격증명 오류로 안내
- logcat 사용자명 평문 제거, SMS 수신 시뮬레이션 함수 자체에 DEBUG 가드, relay API cid URL 인코딩

복구 경험
- 비밀번호 재설정 완료 → 계정 전 기기 세션·소켓 즉시 폐기 회귀 테스트 추가(서버)
- envelope 변조(wrapped key·nonce 교체·송신자 사칭) 복호화 거부 테스트 추가(웹), Argon2id salt 필수화로 조용한 인증 실패 원천 차단

안정성·성능
- 웹 주요 컴포넌트 zustand 셀렉터 좁히기 — 메시지 수신마다 앱 전체 리렌더 제거
- Android 승인 대기 폴링 5초→15초 + 401 감지 시 중단, 설정 탭 중복 폴링 루프 제거
- 기기 폐기·거부·보안 업그레이드 실패 시 사용자 오류 표시(웹), 검색 프로미스 catch, 재설정 중복 클릭 가드, ack 타임아웃 재시도 판정 구조화

문서·인프라
- `docs/CRYPTO_SPEC.md` 신설 — 키 계층·KDF·AEAD·논스·재생 방어·세션 폐기·전방 비밀성 미지원 명시
- `docs/THREAT_MODEL.md` 이메일 복구·로그아웃 평문 삭제·MLS 실험 현황 반영 갱신
- `docs/QR_PAIRING_DESIGN.md` 신설 — v0.11 QR 페어링 프로토콜·서버 스키마·API 설계안
- GitHub Actions CI 추가: 서버 unittest·웹 vitest+E2E+빌드·Android gradle·Rust crypto-core cargo test·gitleaks
- 서버 68 / 웹 142 / Android unit 테스트 통과, Android versionCode 20 / versionName 0.10.8

## v0.10.7 (2026-08-15) — Android 이메일 계정 복구·가입 인증

- Android 로그인 화면에서 이메일 인증코드 기반 비밀번호 재설정 제공
- Android 신규 가입도 이메일 인증 후 계정을 생성하도록 웹과 동일한 흐름으로 통합
- 인증코드 재전송·새 비밀번호 설정·서버 오류 안내 추가
- Android versionCode 19 / versionName 0.10.7
- Android unit test·lint·debug/release APK 빌드 통과

## v0.10.6 (2026-08-15) — 이메일 계정 복구 연동 및 최신 운영 서버 호환

- 웹 로그인 화면에서 이메일 인증코드를 이용한 비밀번호 재설정 기능 제공
- Resend 기반 이메일 발송 서버와 연동된 계정 복구 흐름 안내
- Android 자동 업데이트 경로를 최신 운영 릴리스로 갱신
- Android versionCode 18 / versionName 0.10.6
- Android unit test·lint·debug/release APK 빌드 통과

## v0.10.5 (2026-08-14) — Android 자동 업데이트 배너 복구

- GitHub 릴리스에서 새 버전을 감지해도 메인 화면에 업데이트 배너가 표시되지 않던 누락 수정
- v0.10.0 설치본에서도 v0.10.4 이상 릴리스를 감지·다운로드·설치할 수 있도록 회귀 테스트 추가
- Android versionCode 17 / versionName 0.10.5
- Android unit test·lint·build 통과

## v0.10.4 (2026-08-14) — 웹 패밀리룩·설정 동선 프리뷰

- 첨부 모바일 시안과 맞춘 밝은 웹 패밀리룩(메시지 목록·대화·연락처 상세) 추가
- 인증 없이 확인할 수 있는 `/design-preview` 로컬 디자인 보드 추가
- 채팅 헤더 삼선 메뉴는 대화·연락처 상세 설정, 하단 설정 탭은 기존 SecureMsg 설정으로 분리
- 기존 보안·인증·메시지 처리 로직과 사용자 디자인 CSS는 유지
- 웹 137개 테스트·production build 통과
- Android versionCode 16 / versionName 0.10.4 (릴리스 APK는 기존 debug 서명 키 유지)

## v0.10.3 (2026-08-14) — 보안·수신 안정성 패치

- 기존 기기 로그인에 Ed25519 개인키 소유 증명 challenge-response 추가
- Room v11 provider epoch/fingerprint 기반 SMS·MMS 중복·Provider ID 재사용 방어
- provider-less 이벤트 ACK tombstone 및 pending provider ID alias 처리
- MMS placeholder·비표준 MIME·multipart carrier callback 처리 안정화
- 모호한 carrier 발송 상태의 자동 재전송을 막아 중복 SMS/MMS 발송 방지
- relay malformed/decrypt 실패 시 cursor를 건너뛰지 않는 fail-safe 동기화
- 서버 message MID payload 충돌 검증 및 폐기 기기 fanout 경쟁조건 수정
- 웹 trust-mode downgrade, stale session mutation, trust-anchor 보존 처리 강화
- 서버 65개, 웹 137개, Android debug/release 148개, Rust 13개 테스트 통과

## v0.10.2 (2026-08-11) — Android 화면 단순화

- 메시지 화면에서 버전·업데이트·장문 권한 설명을 제거하고 핵심 상태와 작업만 표시
- SMS 역할/권한 안내를 한 줄 액션으로 축소
- 상세 보안·알림·업데이트 설명은 `차단·설정` 화면에서 확인하도록 정리

## v0.10.1 (2026-08-11) — 신규 기기 승인 요청 표시 개선

- Android 메인 화면에서 승인 대기 중인 새 기기를 자동 조회하고 승인 요청 배너 표시
- `기기 보안 열기` 버튼으로 승인 화면에 바로 진입
- 웹의 기기 승인 대기 화면에 Android 승인 경로 안내 추가
- Android `testDebugUnitTest` 및 Debug APK 빌드 통과

## v0.10.0 (2026-08-09) — 인증정보 보호 + 알림 진입 복구 + 연락처 다기기 동기화

### 인증·세션 보안

- Android DataStore에 평문으로 남던 JWT와 X25519/Ed25519 개인키를 Android Keystore 기반 AES-256-GCM으로 암호화
  - 저장할 때마다 12바이트 랜덤 nonce와 인증 태그 사용
  - 기존 평문 `token`·`box_sk`·`sign_sk`는 최초 로드 시 암호문으로 자동 이전 후 삭제
  - 암호문 변조·Keystore 키 소실 시 앱이 종료되지 않고 해당 로컬 인증정보를 폐기한 뒤 재로그인 요구
- 서버 `devices.session_version`과 JWT `sv`를 추가해 기기별 세션을 즉시 폐기 가능하도록 변경
  - 명시적 로그아웃과 동일 기기 재로그인 시 이전 JWT 무효화
  - REST, Socket.IO 연결 및 모든 클라이언트 이벤트에서 DB 세션 버전 재검증
  - 소켓 room도 세션 버전별로 격리해 로그아웃 전 기존 연결의 후속 서버 푸시 차단
  - 다른 기기의 로그인 세션과 기기 공개키는 유지
- 웹과 Android 로그아웃이 `POST /api/logout`으로 서버 토큰을 먼저 폐기한 뒤, 네트워크 실패나 이미 만료된 토큰에도 로컬 세션을 반드시 정리하도록 변경
- 비밀번호 원문과 재사용 가능한 클라이언트 검증값은 로컬 저장하지 않음. 웹 JWT는 계속 메모리에만 유지

### Android 대화 화면

- Room 메시지 조회를 `createdAt DESC, id DESC`로 변경하고 Compose 목록에 `reverseLayout`을 적용
- 대화를 열면 최신 문자가 화면 최하단에 보이며, 새 문자 도착 시 기존 대화가 위로 밀리는 일반 메신저 방식으로 변경
- 사용자가 과거 대화를 읽는 중에는 최신 문자로 강제 이동하지 않고, 최하단에 있을 때만 새 문자를 계속 따라감
- 대화방 전환과 검색 시작·초기화 시 해당 대화 또는 검색 결과의 최신 문자부터 표시

### 알림에서 수신 문자 열기

- 수신 SMS를 알림보다 먼저 Room의 `sms_threads`·`messages`·`relay_outbox`에 한 트랜잭션으로 저장해, 릴레이 서비스가 늦거나 오프라인이어도 앱 화면에 즉시 표시
- 알림마다 고유 message identity·CID·정규화 전화번호를 담은 독립 PendingIntent를 생성해 여러 알림이 서로 덮어쓰지 않도록 수정
- 앱 종료 상태의 `onCreate`와 실행 중 `singleTask onNewIntent`를 모두 처리해 알림을 누르면 메시지 탭의 해당 대화를 직접 열도록 변경
- 로컬 CID가 서버 CID로 병합됐거나 Room Flow가 늦게 도착해도 전화번호 fallback으로 한 번만 대화를 선택하고, 앱 복귀 시 Provider import/outbox flush 재시작

### 연락처 이름 다기기 동기화

- 서버 `conversations.synced_contact_name`과 Android Room `syncedContactName`을 추가해 SMS 전화번호 identity를 바꾸지 않고 별도의 표시명으로 동기화
- Android 연락처 동기화 시 SecureMsg의 본인 단독 SMS 대화와 매칭된 이름·삭제 상태만 최대 500개 bulk snapshot으로 서버에 원자 반영
- 업로드는 계정의 Android SMS gateway만 허용하고, 그룹·타 계정 대화·잘못된 타입·제어문자·중복 CID는 전부 거부
- `contacts_updated` Socket.IO 이벤트와 재연결 목록 조회로 웹·다른 Android 기기에서 즉시/오프라인 복구 표시
- 표시 우선순위는 현재 기기 연락처 → 동기화된 연락처 이름 → 서버 대화명 → 전화번호. 실제 SMS 발신은 계속 전화번호 필드만 사용
- 전체 주소록과 메시지 내용은 업로드하지 않지만, 매칭된 연락처 이름은 다른 기기 표시를 위해 relay SQLite에 메타데이터로 저장된다는 점을 설정 화면에 명시

### 검증

- 서버 41개 테스트 통과(로그아웃·토큰 회전·연락처 bulk 원자성·권한 경계·DB 마이그레이션 포함)
- 웹 68개 테스트 + TypeScript + production build 통과
- Android 96개 단위 테스트 + lint + Debug/Release APK 빌드 통과
- versionCode 12 / versionName 0.10.0

## v0.9.0 (2026-08-08) — GitHub 이슈 4건 해결 + Android 구조 리팩토링

### GitHub 이슈 수정

- **#1 차단 키워드 동기화**
  - 이전 서버 캐시를 tombstone 기준선으로 사용해 다른 기기에서 삭제한 규칙을 Android가 다시 올리는 “삭제 부활” 문제 해결
  - 동기화·추가·삭제를 단일 Mutex로 직렬화하고, 명시적 로컬 재추가와 네트워크 실패 재시도를 구분
  - Android 설정 화면에 다른 기기에서 추가한 키워드·발신번호도 표시하고 직접 삭제 가능
  - 에뮬레이터+운영 relay에서 서버 삭제 → 앱 재연결 → Room prune → 서버 미부활 전 구간 검증
- **#2 앱내 업데이트 실패**
  - `PackageInstaller.Session` 기반 설치와 결과 콜백 추가, Play Protect 차단·사용자 취소·서명 충돌·저장공간 부족별 안내/재시도 UI 제공
  - `STATUS_PENDING_USER_ACTION` 시스템 설치 확인 Intent 처리 및 콜백 UUID 검증
  - 다운로드 바이트 수·패키지명·설치된 앱과 APK 서명 인증서 SHA-256 일치 검증
  - 프로세스 재생성에도 pending 설치 상태 복원, 실패 세션 abandon, legacy installer 반복 실행 방지
- **#3 휴대폰 연락처 동기화**
  - 설정에서 명시적으로 실행하는 연락처 이름 동기화 추가 (`READ_CONTACTS`는 실행 시에만 요청)
  - 번호를 정규화해 기존 SMS 스레드의 로컬 전용 이름에 반영하고 마지막 동기화 시각·번호/일치 개수 표시
  - 서버 대화명과 로컬 주소록 이름을 Room 컬럼으로 분리해 어느 쪽도 동기화 과정에서 덮어쓰거나 삭제하지 않음
  - 연락처 이름·번호는 relay/API로 전송하지 않고 Android 기기 내부에서만 처리
- **#4 상대방 답장 표시 불가**
  - 한국 로컬 번호(`010`, `02`, `0xx`, `050x`)와 `+82`/`82`/`0082` 표기를 같은 canonical 대화 키로 통일(Android·웹)
  - 수신 SMS/MMS를 relay 연결/서버 ACK 전에 Room 메시지+내구성 outbox에 원자적으로 저장해 오프라인에서도 즉시 표시
  - stale/provisional CID를 현재 서버 CID로 병합하고 Socket.IO echo와 ACK 순서 경합 시 중복/UNIQUE 오류 방지
  - 에뮬레이터에서 `01012340000` 답장이 기존 `+821012340000` 스레드로 합쳐지고 seq 확정·outbox 삭제되는 것까지 실검증

### 리팩토링·품질

- `MainActivity.kt` 1,454줄을 약 550줄로 축소하고 로그인·메인·메시지·설정·업데이트 UI를 `ui/` 파일로 분리
- 서버 URL 설정과 foreground 알림을 `ServerConfig`·`BridgeNotifications`로 분리, 웹 `useStore` 순수 helper 추출
- SMS 핵심 권한과 Android 13+ 알림 권한 상태를 분리해 알림 거부가 브리지 장애로 표시되지 않도록 수정
- Android debug 빌드에서 같은 LAN의 로컬 relay HTTP 테스트 허용(릴리스 빌드는 HTTPS-only 유지)
- 신규 기능: Android 대화 상대·전화번호 및 대화 내 메시지 로컬 검색(차단 메시지 본문은 검색 결과에서 제외)
- Android 79개 단위 테스트 + lint + Debug APK 빌드, 웹 61개 테스트 + TypeScript + production build, 서버 32개 테스트 통과
- versionCode 11 / versionName 0.9.0

## v0.8.0 (2026-08-05) — Android UI 리디자인: 웹 브랜드 이식

- **앱 전체 디자인을 웹 브랜드(v0.4.4 토큰)로 통일**: 딥 다크 네이비 배경(#0A0F16) + teal→sky 그라디언트 강조 + 텍스트 4단계 위계
- `ui/Theme.kt` 디자인 시스템 신규: SmCard·SmGradientButton·SmGhostButton·SmTextField·SmAvatar·SmTabs·SmChip·ChatBubble 공용 컴포넌트
- 로그인 화면: 그라디언트 로고 타입, 카드 폼, 그라디언트 CTA, 버전 푸터
- 메인 화면: 그라디언트 브랜드 헤더 + 버전 칩·상태 칩(정상 teal/경고 amber), pill형 탭
- 대화 목록: 그라디언트 이니셜 아바타 + 이름·번호 2행 로우
- 대화 화면: 챗 버블(보낸 메시지 우측 teal, 수신 좌측 surface) + 원형 그라디언트 전송 버튼
- 설정 탭: 섹션별 카드화(차단 키워드·발신번호·앱 업데이트·격리된 스팸·개발자 도구), 로그아웃 danger 강조
- 업데이트 배너·다이얼로그도 디자인 시스템 적용
- versionCode 10 / versionName 0.8.0, Android 테스트 35개·lint·빌드 통과, 에뮬레이터 전 화면 스크린샷 검수

## v0.7.0 (2026-08-05) — Android 인앱 자동 업데이트 (게임처럼)

- **인앱 업데이트**: APK 수동 재설치 불필요. 앱이 GitHub 릴리스를 스스로 확인해 새 버전을 내려받고 설치까지 진행한다
  - 12시간마다 자동 확인(차단·설정에서 토글) + 수동 `업데이트 확인` 버튼, 현재 버전 표시
  - 새 버전 감지 시 메인 화면 상단 배너(`업데이트` / `나중에`), 다운로드 진행률 표시, 완료 시 시스템 패키지 설치기 자동 실행
  - `REQUEST_INSTALL_PACKAGES` + FileProvider 연동. 최초 1회 '이 앱의 설치 허용' 권한을 켜면 이후 설치는 자동으로 이어짐(복귀 시 자동 재시도)
  - 릴리스 소스: `api.github.com/repos/leetomo2528/secure-msg/releases/latest`의 `.apk` 자산
- `AppUpdater.kt` 신규: 버전 비교(semver)·GitHub JSON 파싱은 순수 함수로 분리, 단위 테스트 9개 추가 (`AppUpdaterTest.kt`)
- debug 빌드 개발자 도구에 `업데이트 흐름 테스트`(최신 릴리스 강제 다운로드→설치) 추가
- versionCode 9 / versionName 0.7.0, Android 테스트 35개·lint·빌드 통과
- **에뮬레이터 전 구간 실검증**: 구버전 설치 → 앱 실행 시 배너 자동 표시 → 업데이트 탭 → 다운로드(진행률) → 시스템 설치기 자동 전환 → 설치 완료까지 확인

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
