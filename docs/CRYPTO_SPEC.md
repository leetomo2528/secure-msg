# SecureMsg 암호 명세 (v0.10 envelope 프로토콜)

이 문서는 현재 운영 중인 v0.10 메시지 동기화 프로토콜의 암호학적 구성요소를 코드 기준으로 명시한다.
구현 위치: 웹 `frontend/src/crypto/keys.ts`, Android `CryptoUtil.kt` (LazySodium), 서버 `server/` (PyNaCl·bcrypt·PyJWT).
향후 프로토콜(MLS 기반)은 [`crypto-core/README.md`](../crypto-core/README.md)와 [QR 페어링 설계](QR_PAIRING_DESIGN.md)를 참고한다.

## 1. 키 계층

| 키 | 알고리즘 | 생성 시점 | 저장 위치 | 서버 전송 |
|---|---|---|---|---|
| 기기 암호키쌍 (box) | X25519 (`crypto_box_keypair`) | 기기 최초 등록 | 웹: IndexedDB / Android: Keystore AES-256-GCM 암호문(DataStore) | 공개키만 |
| 기기 서명키쌍 (sign) | Ed25519 (`crypto_sign_keypair`) | 기기 최초 등록 | 위와 동일 | 공개키만 |
| message key | 대칭 32바이트 (`randombytes_buf`) | 메시지 전송마다 | envelope 안에 수신기기 공개키로 감싸 전달, 발신 기기 메모리에서 폐기 | wrapped 형태만 |
| JWT (HS256) | 서버 시크릿으로 서명 | 로그인·기기 등록·토큰 갱신 시 | 웹: 메모리 전용 / Android: Keystore 암호문 | 토큰 자체 |

- 개인키는 어떤 경로로도 서버로 전송되지 않는다. 공개키는 기기 등록 시 1회 업로드되고 DB trigger(`devices_keys_immutable`)로 이후 변경이 불가능하다.
- Android의 자격증명은 Android Keystore의 비내보내기 AES-256-GCM 키로 봉인되며 AAD로 패키지 무결성을 묶는다. `allowBackup=false`와 백업 제외 규칙으로 클라우드/adb 백업 경로를 차단한다.
- 웹 개인키는 IndexedDB에 저장된다(브라우저 프로필 보호 수준). JWT는 메모리에만 두고 새로고침 시 재로그인을 강제한다.

## 2. 비밀번호 처리 (KDF)

1. 클라이언트는 `salt = BLAKE2b-128(NFKC(username).lower())` — 사용자명에서 결정적으로 유도.
2. `pw_hash = Argon2id(password, salt, ops=INTERACTIVE, mem=INTERACTIVE, out=32B)` — libsodium 기본 파라미터, base64url로 인코딩.
3. 서버는 수신한 `pw_hash`를 다시 bcrypt(cost 12)로 감싸 저장한다. 원본 비밀번호와 Argon2id 출력은 클라이언트에 저장되지 않는다.
4. 로그인 시점 계정 열거 방지: 미가입 사용자 조회에도 더미 bcrypt 해시로 동일 시간의 검증을 수행한다(`auth.py _DUMMY_PW_HASH`).

제약: Argon2 salt가 사용자명에서 유도되므로 같은 (username, password) 쌍의 해시는 항상 동일하다. salt는 비밀이 아니며 무차별 대입의 단위만 사용자별로 분리하는 역할을 한다.

## 3. 메시지 envelope (AEAD)

```
plaintext ── secretbox(XSalsa20-Poly1305) ──► ct, nonce(24B 랜덤)
message_key(32B) ── box(X25519-XSalsa20-Poly1305) ──► keys[sid] = { ek, n(24B 랜덤), by? }
envelope = { ct, nonce, keys: { device_sid: {ek, n, by?} } }
```

- **AEAD**: `crypto_secretbox_easy` (XSalsa20-Poly1305). MAC이 붙고 1비트 변조 시 복호화 실패(회귀 테스트 `keys.test.ts`).
- **키 래핑**: 메시지마다 새 message key를 생성하고, 수신·발신 각 기기의 X25519 공개키에 대해 `crypto_box_easy`로 개별 감싼다. 서버와 수신자가 아닌 기기는 message key에 접근할 수 없다.
- **논스 정책**: `nonce`와 `n`은 매번 24바이트 랜덤 생성. 논스 원장은 사용하지 않는다 — 24바이트 랜덤 논스의 충돌 확률(≈2⁻¹⁰⁰ 이하, 메시지 수 기준)이 무시 가능하고 message key 자체가 메시지마다 새로 생성되어 같은 키·논스 재사용이 구조적으로 발생하지 않는다.
- **송신자 인증(대체 AAD)**: secretbox는 별도 AAD 파라미터가 없으므로, 송신자 바인딩은 (a) wrapped key의 X25519 DH가 송신 기기 개인키 없이는 생성될 수 없다는 성질과 (b) 수신 클라이언트가 서버 제공 `sender_pub_key` 스냅샷을 **서명된 기기 디렉터리 proof**의 해당 기기 키와 대조한 뒤에만 복호화에 사용하는 `verifiedSenderPublicKey` 검증으로 달성한다. 디렉터리와 불일치하면 `TrustViolationError`로 전체 동기화가 중단(fail-closed)된다.
- **재래핑 표식 `by`**(v0.14.0): 나중에 등록한 기기에 과거 내역을 넘길 때, 이미 승인된 기기가 자기 키로 message key를 열어 대상 공개키로 다시 감싸고 그 항목에 자기 `sid`를 `by`로 남긴다. 수신 측은 `by`가 있는 항목을 발신자 키가 아니라 **로컬에 고정된 디렉터리에서 찾은 그 wrapper의 공개키**로 연다. `by`를 로컬에서 해석하지 못하면 발신자 키로 폴백하지 않고 복호화를 거부한다 — 서버가 임의의 wrapper를 지목하고 그 공개키까지 제공할 수 있다면, 그것은 서버가 개인키를 쥔 키를 지목하는 것이고 고정 디렉터리가 막으려는 사칭 그 자체다.
- **키 공유 경로**: `/conversation/<cid>/missing-keys`가 대상이 아직 못 여는 seq를 알려주고, `/conversation/<cid>/share-keys`가 재래핑한 항목을 올린다. 대상은 **호출자 자기 계정의 승인된 다른 기기**여야 하며, 서버는 없는 `sid`만 추가하고 기존 항목은 덮어쓰지 않는다(덮어쓰기는 해당 기기에 대한 서비스 거부가 된다). 항목당 `ek`는 48바이트, `n`은 24바이트로 길이가 고정되고 요청당 200개까지다.
- **서버 제약**: 서버는 envelope를 `{ct, nonce, keys}` 구조 검증, 크기 상한(기본 1.5MB), `keys`의 sid 집합이 대화의 승인 기기 집합과 정확히 일치하는지만 확인하고 내용을 보지 않는다. 이 정확 일치는 **전송 시점** 검사이고, 그 뒤 share-keys가 같은 envelope에 나중에 등록한 기기의 `sid`를 추가할 수 있다.

## 4. 순서·재생 방어

- 서버는 대화별 단조 증가 `seq`를 `BEGIN IMMEDIATE` 트랜잭션에서 할당한다(`insert_message`).
- 클라이언트는 기기·대화별 cursor로 연속된 범위만 수용하고, 건너뛴 seq는 히스토리 REST로 회수한다.
- 발신 멱등성: 클라이언트 생성 `mid`(≥16자)와 `(sender_sid, client_mid)` 유니크 인덱스로 ACK 유실 재전송이 중복 메시지를 만들지 않는다. 재전송 payload가 원본과 다르면 거부한다.
- Android 통신사 발신은 `(cid, seq)` 단위 Room 영수증(tombstone)으로 at-least-once 재시도에서도 실제 SMS 이중 발신이 발생하지 않게 한다(통신사 API 호출 경계의 극히 좁은 중복 창은 제외 — 위협 모델 참고).

## 5. 기기 신뢰·세션 폐기

- 기기 승인/폐기/보안 업그레이드는 도메인 분리된 정규화 명세문에 대한 Ed25519 서명으로 수행되고, 그 서명이 서버 DB에 인증서로 보관된다. 기기·계정 키 변경은 불가(immutable trigger)하다.
- JWT에는 `{uid, sid, sv}`가 담긴다. `sv`(세션 버전)는 REST 요청·Socket.IO 연결·이벤트마다 DB 현재값과 대조한다.
- **슬라이딩 갱신**(v0.12.3): `POST /api/token-refresh`가 아직 유효한 토큰을 새 토큰으로 교체한다. Android 브리지는 릴레이 접속에 성공할 때 6시간에 한 번, 웹은 로그인된 앱 로드마다 호출한다. 유효한 토큰이 이미 가진 권한 이상을 주지 않으며, 세션 버전이 회전됐거나 기기가 폐기됐으면 갱신도 같이 거부된다. TTL은 `SECUREMSG_JWT_TTL`(기본 604800초 = 7일).
- **세션 절대 상한**(v0.19.0): `exp`는 `session_started_at + SECUREMSG_SESSION_MAX_AGE`(기본 15552000초 = 180일)로 잘린다. 기준 시각은 클라이언트가 돌려주는 `iat`가 아니라 기기 행의 컬럼이다 — 이 상한은 탈취된 토큰을 견디려고 있는 것이므로 나이를 클라이언트가 주장하게 두지 않는다. 상한을 넘으면 `/token-refresh`가 거부하고, 기기는 이미 보관 중인 기기 키로 `/device-login`을 다시 수행한다(같은 `sid`·같은 키쌍, 재승인·이력 손실 없음). `exp`가 잘리므로 상한을 넘은 토큰은 모든 엔드포인트에서 만료로 보이는데, 그 401은 서명을 다시 확인해 `session_expired` 코드로 구분해 준다. 설정은 `SECUREMSG_JWT_TTL <= SECUREMSG_SESSION_MAX_AGE <= 2년`을 만족해야 하며 아니면 기동을 거부한다.
- 폐기 정책:
  - 로그아웃 → 해당 기기 세션 버전 회전(토큰·소켓 즉시 무효, 기기 키는 보존해 재로그인 재사용).
  - 기기 폐기 → 승인 디렉터리에서 제외, 세션 버전 회전, 보안 epoch 증가, 활성 소켓 종료. 이후 fan-out에서 배제되지만 과거 envelope의 wrapped key는 남으므로 **폐기 기기가 과거에 확보한 평문/암호문을 회수하지는 못 한다**.
  - 비밀번호 재설정 완료 → 계정의 **모든** 기기 세션 버전을 회전해 전 세션·소켓을 즉시 폐기한다(회귀 테스트 `test_password_reset_revokes_every_device_session`).
  - Android 로그아웃 → 로컬 복호화 평문(`messages`, `sms_threads`, `blocked_sms`, ACK 완료 outbox 평문)을 삭제하고 기기 키만 보존한다.
- Socket.IO 연결 거부 사유는 `auth_rejected:` 기계 판독 코드 접두를 가지며, 클라이언트는 이 코드로 세션 무효화를 판정한다(구 서버 문구 매칭은 폴백).

## 6. 전방 비밀성 — 미지원 (명시)

현재 envelope 프로토콜은 **전방 비밀성(forward secrecy)을 제공하지 않는다.** 기기 개인키가 유출되고 서버 암호문이 함께 확보되면 해당 기기로 전달된 과거 메시지를 복호화할 수 있다. Double Ratchet도 아니다.

대안 준비 상태: `crypto-core/`(Rust, OpenMLS 기반 RFC 9420 MLS)가 계정 단위 기기 그룹의 epoch 기반 키 갱신, `max_past_epochs=0`, 재정렬 창(32세대/전방 1000), 트랜잭션 영속화, 그룹 소거(destroy_group)까지 구현해 두었다. 이 프로토콜로의 전환은 별도 마이그레이션 설계가 필요하며 v0.10 선에서는 실험 코드로만 존재한다.

## 7. 이메일 인증·복구 코드

- 인증 코드는 6자리 난수이며 서버에는 `HMAC-SHA256(JWT_SECRET, "securemsg-email-code-v1\n{challenge_id}\n{code}")` 다이제스트만 저장한다.
- 챌린지는 TTL 10분(기본), 시도 5회 제한, 단일 소비. 새 챌린지 발급은 같은 이메일/사용자의 이전 챌린지를 무효화한다.
- 발송·검증 엔드포인트는 IP+대상별 rate limit(발송 5/분, 검증 10/분)로 메일 폭격·무차열 대입을 차단한다. 재설정 요청 응답은 계정 존재 여부를 노출하지 않는다.
