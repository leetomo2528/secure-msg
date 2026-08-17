# QR 기기 페어링 설계 (v0.11 설계안, 미구현)

상태: **설계 문서. 구현은 v0.11.** 현재(v0.10.8) 기기 추가는 "새 기기가 비밀번호로 pending 등록 → 기존 기기가 지문 비교 후 Ed25519 서명 승인" 절차를 따른다. 이 설계는 그 절차의 1·2·3단계(요청 전달·지문 비교)를 QR과 짧은 안전번호로 교체해 실수·중간자 표면을 줄이는 것이 목적이며, **승인 서명 구조(`approval_statement`, 보안 epoch, 인증서 체인)는 그대로 재사용한다.**

## 설계 원칙

1. **QR 자체를 인증으로 간주하지 않는다.** QR은 데이터 운반 수단일 뿐이고, 최종 인증은 양쪽 화면에 뜨는 짧은 안전번호(safety number)의 사람 대면 비교다.
2. **기존 신뢰 모델을 확장하지 않는다.** 승인은 여전히 승인 기기의 Ed25519 서명이고, 서버는 여전히 서명·epoch만 검증한다. QR 경로가 생겨도 서버의 신뢰 판단 규칙은 한 줄도 늘어나지 않는다.
3. **재생 방지**: 페어링 세션은 논스 2개(신규 기기 → 승인 기기 방향 `nonce_new`, 승인 기기 → 신규 기기 방향 `nonce_approver`)를 바인딩하고 단일 사용·단기 TTL을 가진다.
4. **역사 복호화는 범위 밖.** 이 설계는 새 기기 등록·승인만 다룬다. 과거 메시지 전송(키 이전)은 포함하지 않는다 — 기존 "등록 이전 envelope는 새 기기가 복호화 불가" 정책 유지. 키 이전이 필요해지면 MLS(crypto-core) 마이그레이션과 함께 설계한다.

## 프로토콜 흐름

```text
신규 기기                      relay 서버                     승인 기기(기존)
   │ device-register(pending)     │                                │
   │ ───────────────────────────► │── device_pending 이벤트 ──────► │
   │                              │                                │ (QR이 없어도 폴백 UI 유지)
   │ pairing_qr = {               │                                │
   │   v: 1, type: "securemsg-pairing",                          │
   │   server: <origin>,            │                                │
   │   username, sid,                │                                │
   │   challenge,                    │                                │
   │   box_pk, sig_pk,               │                                │
   │   nonce_new, expires_at }       │                                │
   │ [QR 표시] ────────── 물리적 스캔(사람이 기존 기기로 촬영) ─────────► │
   │                              │                                │ QR 파싱·스키마 검증
   │                              │                                │ sid 상태를 서버에서 재확인
   │                              │                                │ (pending + challenge 일치)
   │                              │ ◄──── POST /api/pairing/session ─ │
   │                              │   {sid, nonce_new, nonce_approver} │
   │                              │    (서버 생성, TTL 120s, 1회)     │
   │                              │ ── pairing_session 이벤트 ─────► │
   │                              │                                │
   │   양쪽 화면에 동일 안전번호 표시:                                 │
   │   safety = 그룹화된 Base64(SHA256(nonce_new ‖ nonce_approver ‖ sid ‖ box_pk ‖ sig_pk))
   │                              │                                │
   │                              │ ◄─ POST /api/device-approve ──── │ (기존 API 그대로)
   │                              │   승인 서명 명세문에 pairing 세션 논스 추가 바인딩
   │ ◄─ device_approved + session ─ │ ── device_approved 이벤트 ────► │
```

핵심: QR에 담긴 값은 전부 **공개 정보**다(공개키·논스·계정명·서버 주소). QR이 유출·스크린샷되어도 공격자가 할 수 있는 일은 (a) 승인 기기인 척 페어링 세션을 만드는 것뿐인데, 이 경우 안전번호가 신규 기기 화면과 일치하지 않아 사용자가 거부하고, (b) 신규 기기인 척 승인을 요청하는 것은 서버의 pending 상태·challenge 대조로 실패한다.

## 서버 스키마 (추가)

```sql
CREATE TABLE pairing_sessions (
    pairing_id   TEXT PRIMARY KEY,          -- token_urlsafe(18)
    user_id      INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    subject_sid  TEXT NOT NULL,             -- 신규(pending) 기기
    approver_sid TEXT NOT NULL,             -- QR을 스캔한 승인 기기
    nonce_new    TEXT NOT NULL,             -- QR에서 옴, base64url 32B
    nonce_approver TEXT NOT NULL,           -- 서버 생성, base64url 32B
    expires_at   INTEGER NOT NULL,          -- created_at + 120
    consumed_at  INTEGER,                   -- 승인 커밋 시 1회
    created_at   INTEGER NOT NULL
);
```

## API (추가 1개, 변경 1개)

### `POST /api/pairing/session` (승인 기기 토큰 필요)

요청 `{sid, challenge, nonce_new}` → 승인 기기가 QR에서 읽은 값.
검증: 요청자(approver) 승인 상태 · 대상 sid가 같은 계정의 `pending` · challenge 일치 · `nonce_new` 형식.
응답 `{pairing_id, nonce_approver, expires_at}` + 신규 기기 소켓에 `pairing_session` 이벤트.
Rate limit: sid당 10/분.

### `POST /api/device-approve` (기존, 서명 명세문 확장)

`securemsg-device-approval-v2` 명세문에 다음 줄 추가:
`pairing_id=...\nnonce_new=...\nnonce_approver=...`
- v2 필드가 있으면 `pairing_sessions` 행을 `BEGIN IMMEDIATE`로 소비(consumed_at, 만료·중복·sid 불일치 시 거부)하고 승인 커밋과 같은 트랜잭션에 넣는다.
- v1 서명(논스 없음)은 폴백으로 계속 수용 — 구 클라이언트 호환.

## 클라이언트 UX

- **신규 기기(웹/Android)**: pending 등록 직후 "기존 기기에서 스캔하세요" 화면에 QR 표시 + 안전번호 대기 상태. `pairing_session` 이벤트 수신 시 안전번호 표시로 전환.
- **승인 기기(Android)**: 설정 → 기기 보안에서 "QR 스캔" (CameraX + ML Kit barcode, 카메라 권한). 파싱 후 서버 상태 재확인 → 세션 생성 → 안전번호 표시 → 사용자 확인 → 승인 서명(기존 signDeviceApproval 로직 재사용).
- **승인 기기(웹)**: 카메라가 없는 데스크톱은 기존 지문 비교 UI 유지. 노트북 웹캠 스캔은 getUserMedia + jsQR으로 동일 흐름 제공 가능(선택 구현).
- **안전번호**: SHA256 출력을 30비트씩 5그룹(각 6자리 숫자)으로 표시. 두 기기에서 각각 로컬로 계산하며 서버를 경유하지 않는다.

## 거부·취소

- 신규 기기 취소: 기존 `device-pending-revoke` 그대로.
- 승인 기기 거부: 기존 `device-reject-pending` 그대로. 페어링 세션은 TTL로 자동 소멸(별도 삭제 불필요, 서버는 만료 행을 주기적으로 정리).
- 승인 기기에서 안전번호 불일치 발견 = 중간자·서버 이상 징후 → 세션 폐기 + 보안 경고 배너(기존 trust warning 채널 재사용).

## 테스트 계획 (구현 시)

- 만료·소비 완료·sid 불일치 페어링 세션으로 승인 시도 → 거부.
- v2 서명에서 논스 하나라도 변조 → 서명 검증 실패.
- QR payload 재사용(재생): 같은 `nonce_new`로 두 번 세션 생성 → 첫 세션만 유효, 승인은 1회.
- v1 폴백 승인이 v0.10 클라이언트에서 계속 동작.
- 안전번호 양측 계산 일치 golden vector (Rust/TS/Kotlin 3개 구현 공유).
