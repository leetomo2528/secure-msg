# SecureMsg

Android 휴대폰의 실제 SMS 번호를 소스(source)로 삼아, Google Messages처럼 웹 PWA와 여러 기기에서 SMS를 확인하고 답장하는 자가호스팅 앱이다.

핵심 동작은 다음과 같다.

1. Android 앱이 사용자가 선택한 **기본 SMS 앱**이 된다.
2. 수신 SMS를 휴대폰에서 먼저 판정한다.
   - 사용자 키워드/문구 차단
   - 차단 발신번호
   - URL·금융·도박·홍보 문구를 조합한 보수적 자동 스팸 판정
3. 차단된 문자는 Android 로컬 격리함에만 저장하고, 시스템 SMS Provider에도 쓰지 않으며, 알림·서버 동기화를 하지 않는다.
4. 통과한 문자는 Android SMS Provider에 저장한 뒤 기기별 envelope E2E 암호화로 relay 서버에 전달한다.
5. 웹/PWA에서 보낸 답장은 Android가 복호화해 통신사 SMS 또는 MMS로 발신한다.
6. MMS는 텍스트·제목·첨부파일을 암호화 envelope 안에 넣어 웹/Android 기기에 동기화하고,
   Android framework의 carrier-configured MMSC 경로로 발신한다.

Android 대화 화면은 오래된 문자부터 위에 쌓고 최신 문자를 최하단에 고정한다. 최하단을 보고 있을 때 새 문자가 오면 기존 대화가 위로 밀리며, 과거 기록을 읽는 중에는 화면을 강제로 이동하지 않는다.

SMS의 통신사 구간 자체는 SMS 표준 특성상 E2E 암호화가 아니다. 서버는 통과한 SMS의 평문을 볼 수 없지만, 대화 라벨(현재는 전화번호)·기기·시간·메시지 크기 같은 메타데이터는 볼 수 있다.

## 계정과 비밀번호 정책

개인정보 없이 임의의 아이디만 사용한다.

- **아이디**: `a-z`, `0-9`, `_` 만 허용, 3~20자. 실명·전화번호·이메일을 쓰지 않는다.
- **비밀번호**: **총 8자 이상**이면 된다. 영문·숫자·특수문자(한글 포함)를 자유롭게 섞을 수 있으며, 문자 종류별 개수나 조합 같은 추가 제한은 없다. 상한은 1,024자.
- 비밀번호는 클라이언트(웹/Android)에서 **Argon2id**로 해시된 값만 서버로 전송되므로 서버는 원문 비밀번호를 보지 못하고, 서버는 이를 다시 bcrypt로 감싸 저장한다.
- 비밀번호와 Argon2id 결과는 클라이언트에 저장하지 않는다. 웹 JWT는 메모리에만 두고, Android JWT와 기기 개인키는 Android Keystore 기반 AES-256-GCM 암호문으로만 DataStore에 저장한다.
- 로그아웃 시 서버의 기기별 세션 버전을 회전해 현재 토큰과 기존 Socket.IO 세션을 즉시 무효화한다. 기기 키는 보존하므로 다음 로그인에서 같은 기기를 안전하게 재사용할 수 있다.
- **가입은 이메일 인증 경로만 가능하다**(v0.11+). 인증 없이 계정을 만들던 `/api/register`는 제거됐다.
- **비밀번호 재설정은 가입 시 인증한 이메일의 6자리 코드로만 가능하다**(v0.10.6+). 코드는 10분·5회 시도 제한이며, 재설정 완료 시 계정의 모든 기기 세션(토큰·Socket.IO)이 즉시 폐기된다. 이메일을 등록하지 않은 구 계정은 재설정 수단이 없다. 비밀번호는 메시지 암호화 키가 아니므로, 재설정으로 과거 메시지를 새 기기에서 읽을 수는 없다.
- **새 브라우저·새 앱 설치는 해당 기기 등록 이전의 메시지를 복호화할 수 없다.** 과거 envelope에는 새 기기의 wrapped key가 없기 때문이다. v0.14.0부터는 이미 승인된 기기에서 **과거 내역 공유**를 실행하면 그 기기가 message key를 대상 공개키로 다시 감싸 넘겨주므로 새 기기도 과거 대화를 읽을 수 있다. 공유 대상은 같은 계정의 승인된 기기로 서버가 제한하고, 이 과정에서도 서버는 wrapped key만 본다. 암호화된 서버 백업은 제공하지 않으므로, 승인 기기가 하나도 남지 않은 상태에서 브라우저 사이트 데이터·앱 데이터·기기 개인키를 삭제하면 과거 내역을 복구할 수 없다. Android 로그아웃 시 복호화 평문은 기기에서 삭제되고(기기 키는 보존) 재로그인 후 서버 이력을 다시 동기화한다.

변경 이력은 [`CHANGELOG.md`](CHANGELOG.md)에 정리되어 있다.

## 구조

```text
통신사 SMS
   ↓ SMS_DELIVER (Android 기본 SMS 앱만 수신)
SmsReceiver → BlocklistManager / SpamClassifier
   ├─ 차단 → blocked_sms (Android Room 격리함)에서 종료
   └─ 허용 → Telephony.Sms Provider + SmsBridgeService
                         ↓ envelope E2E
                    Flask + Socket.IO relay
                         ↓
              PWA / 여러 브라우저 + Android SMS/MMS 게이트웨이 1대
                         ↓ 답장
                  Android → SmsManager/MMSC → 통신사
```

### 디렉터리

```text
secure-msg/
├── android/
│   └── app/src/main/java/com/yunjelee/securemsg/
│       ├── SmsReceiver.kt          # 기본 SMS 수신·차단·Provider 저장
│       ├── SmsBridgeService.kt     # 웹/기기 간 암호화 relay와 carrier bridge
│       ├── ContactSync.kt          # 연락처 이름 로컬 매칭 + 로그인 기기 간 표시명 동기화
│       ├── PhoneNumberNormalizer.kt # 한국 번호 +82 canonical identity
│       ├── SpamClassifier.kt       # 오프라인 자동 스팸 점수 판정
│       ├── BlocklistManager.kt     # 키워드/발신번호/자동 스팸 정책
│       ├── SmsProvider.kt          # 시스템 SMS Provider 입출력
│       ├── MmsProvider.kt           # MMS Provider/part 읽기·수신 저장
│       ├── MmsPduComposer.kt        # WAP multipart/related MMS PDU
│       ├── MmsSender.kt             # framework MMSC MMS 발신
│       ├── Pairing.kt              # QR 페어링 payload 파싱 + 안전번호
│       ├── RelayContent.kt          # text/MMS envelope 내부 포맷
│       ├── CarrierStatusReceiver.kt # SMS/MMS SENT·DELIVERED 결과
│       ├── Database.kt             # Room: 스레드·격리함·처리 이력
│       ├── MainActivity.kt         # 권한·기본 SMS 역할·업데이트·화면 호스트
│       └── ui/                     # 로그인·메시지·설정·업데이트 Compose 화면
├── frontend/                       # React + TypeScript + Vite PWA
│   └── src/
│       ├── store/useStore.ts       # 로그인·암복호화·동기화 orchestration
│       ├── store/blocklist.ts      # 계정 차단 규칙을 복호화 후 로컬 적용
│       └── components/             # 채팅·기기·차단·새 SMS UI
├── server/                         # Flask + Socket.IO + SQLite
├── deploy/oracle/                  # 운영 배포 스택: docker-compose.yml + 내부 Caddyfile
├── tools/release-apk.sh            # 릴리스 APK 빌드·서명(계보 포함)
├── docs/                           # THREAT_MODEL · CRYPTO_SPEC · QR_PAIRING_DESIGN
└── Caddyfile                       # 80/443에서 직접 TLS를 종료하는 일반 배포용 참고 설정
```

## 로컬 실행

### 1. 서버

```bash
cd ~/secure-msg/server
source .venv/bin/activate
pip install -r requirements.txt
export SECUREMSG_JWT_SECRET="$(openssl rand -hex 32)"
export SECUREMSG_CORS="http://localhost:5173"
# 메일 제공자 없이 개발할 때: 인증 코드를 서버 로그로 받는다.
# (프로덕션에서 이 값을 쓰면 기동이 거부된다)
export SECUREMSG_EMAIL_PROVIDER=console
python app.py                         # http://127.0.0.1:5050
```

### 2. 웹 PWA

```bash
cd ~/secure-msg/frontend
npm install
npm run dev                            # http://127.0.0.1:5173
npm test -- --run
npm run build
```

웹은 기본적으로 Vite proxy를 통해 로컬 Flask 서버에 연결한다. 운영 배포에서는 Caddy가 `/api`와 Socket.IO를 Flask로 역프록시한다.

### 3. Android APK

Android Studio에 포함된 JDK를 사용하면 별도 Java 설치 없이 빌드할 수 있다.

```bash
cd ~/secure-msg/android
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew testDebugUnitTest lintDebug assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk

# 운영/배포 APK — 빌드·서명·검증을 한 번에
cd ~/secure-msg && ./tools/release-apk.sh
```

`assembleRelease`를 단독으로 돌리지 않는다. 릴리스 빌드 타입은 **일부러 서명 없이** 나오고(v3 회전 계보는 Gradle signingConfig로 표현할 수 없다), R8 난독화·리소스 축소도 꺼져 있다 — Room·socket.io-client·lazysodium이 모두 이름을 리플렉션으로 찾는데 축소된 앱이 실제로 뜨는지 확인할 계측 테스트가 아직 없기 때문이다. `tools/release-apk.sh`가 `:app:clean`부터 시작해 서명·계보를 붙이고, 앱 클래스에 `build.gradle.kts`의 버전과 다른 버전 리터럴이 인라인돼 있으면 배포를 거부한다.

디버그 APK는 개발용이며 더 이상 배포하지 않는다. v0.18.0 이후 릴리스 키로 회전이 끝난 기기는 디버그 키로 서명된 APK를 거부하므로, 그런 기기에 `adb install -r`을 하려면 앱을 먼저 삭제해야 한다 — Keystore에 봉인된 기기 키와 로컬 메시지가 함께 사라진다.

앱을 처음 실행한 뒤 다음 순서로 설정한다.

1. SecureMsg 계정 로그인/가입
2. `기본 SMS 앱으로 설정`을 눌러 시스템 역할 승인
3. 이어서 표시되는 SMS·MMS·알림 권한 허용
4. Android 앱의 `차단 키워드`와 `발신번호 차단` 설정
5. 필요하면 `설정 → 연락처 이름 동기화`를 눌러 연락처 이름을 현재 기기와 다른 로그인 기기의 대화 목록에 반영
6. 브리지 foreground 알림이 유지되는지 확인

기본 SMS 앱 역할을 승인하지 않으면 Android는 SMS를 읽을 수 있어도 다른 SMS 앱의 수신·알림을 차단할 수 없다. 따라서 “문자가 들어오면 실제로 자동 차단”하는 기능은 역할 승인 후에만 유효하다.

연락처 동기화는 전체 주소록을 업로드하지 않는다. SecureMsg에 이미 존재하는 본인 단독 SMS 대화와 매칭된 연락처 이름 및 삭제 상태만 relay 서버의 표시명 메타데이터로 저장한다. 이 값은 메시지 본문처럼 E2E 암호화되지 않으므로, 서버 운영자나 DB 유출 공격자가 연락처 표시명을 볼 수 있다는 점을 알고 사용해야 한다. 실제 SMS 발신 주소는 이 표시명과 별도의 전화번호 필드에서 유지된다.

### Android 인앱 자동 업데이트 (v0.13)

업데이트마다 APK를 직접 받아 설치할 필요가 없다. 기본값은 사용자 조작이 전혀 없는 무인 설치다.

1. 브리지가 12시간마다 GitHub 릴리스 API에서 최신 버전을 확인한다(`설정 → 앱 업데이트`의 `자동 업데이트 확인` 토글).
2. 새 버전이 있으면 **요금제가 붙지 않고 인터넷 연결 검증까지 끝난 네트워크**에서만 APK를 내려받는다. 사실상 Wi-Fi 전용이며, 미터드·미검증 네트워크에서는 12시간 스탬프도 소모하지 않는다.
3. 검증을 통과한 APK는 앱 화면이 보이지 않는 동안 조용히 설치된다(`자동 설치` 토글, 기본 켜짐). 설치가 프로세스를 교체하므로 화면이 보이는 동안에는 미뤘다가 나중 tick에서 커밋한다.
4. 설치 직후 새 버전이 브리지를 다시 띄우고, 방금 설치한 버전의 패치 노트를 저중요도 알림으로 알린다(v0.19.0). 밤사이 업데이트돼도 문자 동기화 공백이 남지 않는다.
5. 무인 설치는 이 앱이 자기 자신의 installer of record가 된 뒤부터 가능하다. 그 전까지는 배너 → `업데이트` → 시스템 설치 확인이라는 수동 경로로 떨어지고, 최초 1회 시스템 설정에서 `이 앱의 설치 허용`(알 수 없는 앱 설치)을 켜야 한다. `자동 설치`를 끄면 이 수동 경로만 남는다.
6. 배너에서 `나중에`로 미룬 버전도 `자동 설치`가 켜져 있으면 결국 설치된다.

- APK는 GitHub 릴리스의 공식 자산을 HTTPS로 내려받고, 앱이 자산 크기·패키지명·서명을 먼저 검증한 뒤 시스템 패키지 설치기로 넘긴다. 서명 검증은 인증서 SHA-256 단순 일치가 아니라 **서명 계보** 검사다: 내려받은 APK의 계보 마지막 항목이 그 APK의 현재 서명자와 같고, 현재 설치본의 인증서가 그 계보 안에 있어야 한다. v0.18.0의 키 회전에서 두 인증서가 일부러 달라졌으므로 단순 일치로는 앱이 자기 업데이트를 거부한다.
- Play Protect가 사이드로드 APK를 차단하거나 사용자가 취소하면 앱으로 결과가 돌아와 원인별 안내를 표시한다. 차단 시 시스템 세부정보에서 설치를 허용하거나 GitHub 릴리스에서 APK를 직접 설치할 수 있다.
- 릴리스는 전용 키스토어(`~/keystores/securemsg-release.jks`)로 서명한다. 자격증명은 `~/.gradle/gradle.properties`에만 두고 레포에는 절대 넣지 않는다. 이 키스토어와 `~/keystores/securemsg-lineage.bin`을 잃어버리면 기존 설치를 덮어쓸 수 없다 — 백업해 둘 것.
- v0.18.0 이전 설치본은 공개된 디버그 키로 서명돼 있었다. 그래서 새 키는 v3 회전 계보를 함께 실어, 디버그 키로 설치된 기기도 그대로 업데이트를 받는다. 회전이 끝난 기기는 반대로 디버그 키로 서명된 APK를 거부한다(`SigningRotationTest`가 이 두 방향을 모두 고정한다).
- 릴리스 빌드: `./tools/release-apk.sh` — assembleRelease 후 apksigner로 v2(옛 키)+v3(새 키+계보) 서명까지 한 번에 한다. Gradle의 signingConfig로는 계보를 표현할 수 없어 서명은 이 스크립트가 맡는다.
- 릴리스 방법: `gh release create v<version> --notes-file <노트파일> android/app/build/outputs/apk/release/securemsg-release.apk` — 이 자산이 곧 업데이트 공급 소스다. `--notes`를 빠뜨리지 않는다: v0.19.0부터 폰이 릴리스 본문을 평문으로 정리해 업데이트 완료 알림의 패치 노트로 띄우므로, 본문이 비면 사용자는 제목만 있는 알림을 받는다. 디버그 APK는 더 이상 배포하지 않는다(`debuggable=true`인 기본 문자 앱을 배포하던 셈이라, 개발자 도구도 함께 노출됐다).

## Oracle Cloud 배포

운영 스택은 `deploy/oracle/docker-compose.yml` 하나다. 여기서 뜨는 Caddy는 TLS를 종료하지 않는다 — 호스트에 이미 떠 있는 엣지 프록시(cloudflared)가 도메인의 TLS를 받아 **루프백 `127.0.0.1:8080`** 으로 넘기고, 컨테이너는 그 포트에만 바인딩한다. 따라서 VCN 보안 목록에 컨테이너용 포트를 새로 열 필요가 없고, 인증서도 이 스택이 아니라 엣지가 관리한다. 레포 루트의 `Caddyfile`은 80/443에서 직접 TLS를 종료하는 일반 배포용 참고 설정이며 운영 스택은 쓰지 않는다.

```bash
cd ~/secure-msg
cd frontend && npm ci && npm run build && cd ..
cat > .env <<'EOF'
SECUREMSG_DOMAIN=msg.example.com
SECUREMSG_JWT_SECRET=여기에_랜덤_32바이트_이상_시크릿
SECUREMSG_RESEND_API_KEY=re_...
SECUREMSG_RESEND_FROM=SecureMsg <no-reply@msg.example.com>
EOF
docker compose --env-file .env -f deploy/oracle/docker-compose.yml up -d --build
```

`--env-file .env`를 빠뜨리면 안 된다. Compose는 기본 `.env`를 현재 디렉터리가 아니라 **첫 `-f` 파일이 있는 디렉터리**(`deploy/oracle/`)에서 찾는데, `.env`는 레포 루트에 있다. 지정하지 않으면 모든 변수가 경고 한 줄과 함께 빈 문자열로 치환된다 — `SECUREMSG_JWT_SECRET`이 비면 백엔드가 기동을 거부해 크래시 루프에 빠지고(Caddy는 죽은 API 위에 PWA를 계속 서빙한다), `SECUREMSG_DOMAIN`이 비면 CORS origin이 `https://` 하나가 되어 서버는 health가 정상인데 웹만 막힌다.

`.env`에 넣는 값:

| 변수 | 기본값 | 설명 |
|---|---|---|
| `SECUREMSG_DOMAIN` | 없음 | 공개 호스트명. 컴포즈가 이 값으로 `SECUREMSG_CORS=https://<도메인>`을 만든다 — `SECUREMSG_CORS`를 `.env`에 직접 써도 무시된다. |
| `SECUREMSG_JWT_SECRET` | 없음 | 32바이트 이상. 짧으면 production에서 기동을 거부한다. |
| `SECUREMSG_RESEND_API_KEY` | 빈 값 | 가입·비밀번호 재설정 메일 발송. 비어 있으면 **가입이 503으로 실패한다**. |
| `SECUREMSG_RESEND_FROM` | 빈 값 | 위와 한 쌍. 둘 중 하나라도 비면 메일 발송이 꺼진다. |
| `SECUREMSG_EMAIL_PROVIDER` | `resend` | `smtp`로 바꾸면 `SECUREMSG_SMTP_*`를 쓴다. `console`은 인증 코드를 로그로 뱉으므로 production에서 거부된다. |
| `SECUREMSG_JWT_TTL` | `7776000`(90일) | 기기 토큰 수명. 토큰은 슬라이딩 갱신되므로 실질적으로는 "얼마나 오래 안 써도 세션이 살아 있는가"에 가깝다. |
| `SECUREMSG_SESSION_MAX_AGE` | `15552000`(180일) | 한 세션의 절대 상한. `SECUREMSG_JWT_TTL` 이상이어야 하고 2년을 넘을 수 없다 — 어기면 기동을 거부한다. |

이 표의 기본값은 컴포즈 파일에 박혀 있는 값이고, 운영 호스트의 `.env`가 쓰는 값과 **같아야 한다**. 빠진 변수는 경고 없이 기본값으로 대체되므로, 신규 배포·호스트 이전·재해 복구에서 `.env` 한 줄이 빠지면 다른 건 다 정상으로 보이는 채 인증 창만 조용히 달라진다 — 예전에 컴포즈 기본값이 7일이던 시절 `.env`를 옮기지 않은 재배포가 정확히 이렇게 폰 동기화를 끊었다. `SECUREMSG_SESSION_MAX_AGE`의 180일은 토큰 수명 두 번(90일 × 2)으로 잡은 값이라 활성 기기가 토큰 중간에 잘리지 않는다. 둘 중 하나만 바꾸면 그 관계가 깨지므로 함께 조정한다. `server/config.py`도 같은 근거로 같은 기본값을 들고 있다.

`server/config.py`는 이 밖에도 `SECUREMSG_MAX_ENVELOPE`, `SECUREMSG_MAX_DEVICES`, `SECUREMSG_CHALLENGE_RETENTION` 같은 한도를 읽지만 컴포즈가 전달하지 않으므로 컨테이너 배포에서는 조정할 수 없다. 조정이 필요하면 컴포즈의 `environment:` 블록에 먼저 추가한다.

### 백엔드 볼륨 소유권 (비루트 전환 시 1회)

백엔드 컨테이너는 root가 아니라 uid/gid `10001`(`securemsg`)로 실행된다. 기존
`secure-msg_backend-data` 볼륨은 root가 만들고 채웠기 때문에, **업그레이드 배포 전에 한 번**
소유권을 옮겨야 한다. 옮기지 않으면 SQLite가 `/data`에 WAL/SHM 파일을 만들지 못해
릴레이가 기동하지 않는다.

```bash
cd ~/secure-msg
docker compose --env-file .env -f deploy/oracle/docker-compose.yml stop backend
docker run --rm -v secure-msg_backend-data:/data alpine chown -R 10001:10001 /data
docker compose --env-file .env -f deploy/oracle/docker-compose.yml up -d --build
```

새로 만드는 볼륨은 이미지의 `/data` 소유권을 그대로 물려받으므로 이 작업이 필요 없다.

도메인은 엣지 프록시가 `SECUREMSG_DOMAIN`을 받아 `127.0.0.1:8080`으로 전달하도록 설정한다. 인증서 발급·갱신도 그쪽 책임이다. 실제 운영에서는 `.env`를 백업·공유하지 말고, SQLite 볼륨과 JWT secret을 별도로 보호한다.

## 테스트

```bash
cd ~/secure-msg
server/.venv/bin/python -m unittest discover -s server/tests -v

cd android
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
  ./gradlew testDebugUnitTest

cd ../frontend
npm test -- --run
npm run build
```

웹 테스트에는 **인증 E2E**(`src/e2e/auth.e2e.test.ts`)가 포함된다. 실제 로컬 Flask relay 서버를 띄워 회원가입·기기 등록·재로그인·새 브라우저 로그인·잘못된 비밀번호·중복 가입을 실 HTTP + 실 Argon2id 해시 + IndexedDB 저장까지 전 구간 검증한다. `server/.venv`가 설치되어 있어야 한다.

## 현재 범위와 제한

- 통신사 연동은 Android 31 이상을 대상으로 한다. iOS는 임의 SMS 앱이 통신사 SMS 수신을 가로채는 동일한 경로를 제공하지 않으므로 별도 구현이 필요하다.
- 계정당 통신사 SMS 게이트웨이는 Android 1대로 제한한다. 여러 브라우저/PWA 기기는 추가할 수 있지만, 서로 다른 SIM의 Android 여러 대가 같은 문자를 중복 발신하지 않도록 두 번째 Android 게이트웨이 등록은 거부한다.
- 통신사 발신으로 해석되는 대화는 전화번호 이름을 가진 **본인 단독 대화**로 제한한다. 다른 사용자가 포함된 그룹 대화는 이름이 전화번호처럼 보여도 Android가 통신사 발신 명령으로 처리하지 않는다. 동일 번호의 단독 대화 생성은 서버에서 원자적으로 재사용한다.
- 한국 전화번호 대화 키는 `+82` 형식으로 정규화한다. `010 + 7/8자리`, `02 + 7/8자리`, 그 밖의 `0xx + 7/8자리`, `050x + 8자리` 로컬 번호와 유효 길이의 `82`/`0082` 표기는 같은 대화로 본다. 짧은 대표번호·`*`/`#` 서비스 코드와 다른 국가의 국제번호는 한국 번호로 바꾸지 않는다. 기존 DB 라벨은 마이그레이션하지 않고 읽을 때 비교 키만 정규화한다.
- MMS 텍스트·제목·첨부파일(최대 8개, 전체 512KB)은 구현되어 있다. Android 기본 SMS 앱 역할과
  통신사 APN/MMSC 설정이 필요하며, raw PDU 처리 차이 때문에 실제 발신·수신은 기기/SIM별로 검증해야 한다.
  웹의 MMS 첨부파일은 암호화 envelope에 포함되고, 서버는 파일 평문을 보지 못한다.
- 자동 스팸 판정은 서버 ML이 아니라 오프라인 규칙 기반 점수기다. 오탐/미탐이 있을 수 있으며, 사용자가 키워드로 보정할 수 있다.
- 차단 키워드·발신번호는 계정 단위로 **모든 기기에 동기화**된다(v0.6). 서버에는 사용자가 입력한 필터 문자열(키워드·번호)만 저장되고 메시지 평문은 여전히 서버에 닿지 않는다. 각 기기는 복호화 후 로컬에서 규칙을 적용한다.
- foreground `remoteMessaging` 서비스가 백그라운드 relay 연결을 유지하므로 Android 상태 표시줄에 지속 알림이 보인다.
- JWT는 DB에 저장된 기기별 세션 버전과 REST 요청·Socket.IO 연결/이벤트마다 대조한다. 로그아웃과 동일 기기 재로그인은 해당 버전을 회전해 이전 토큰과 활성 소켓을 무효화하지만 기기 키는 삭제하지 않으며, 다른 기기 세션에는 영향을 주지 않는다. JWT가 만료되거나 기기가 폐기된 경우에도 활성 소켓을 종료하고 로컬 기기 키를 보존한 채 다시 로그인하도록 전환한다. 토큰 자체는 슬라이딩 갱신된다 — Android 브리지는 릴레이 접속에 성공할 때 6시간에 한 번, 웹은 로그인된 앱 로드마다 `/api/token-refresh`로 교체하므로 활성 기기의 세션은 TTL 경계에서 조용히 죽지 않는다. 다만 한 세션은 처음 성립한 시점부터 `SECUREMSG_SESSION_MAX_AGE`(기본 180일)를 넘길 수 없고, 그 뒤에는 갱신이 거부되고 다시 로그인해야 한다. **이 재로그인은 정상 동작이다** — 기기 키는 그대로 남아 있어 같은 기기·같은 `sid`로 재승인이나 이력 손실 없이 복구된다.
- 서비스가 완전히 오프라인일 때 수신 SMS/MMS는 Provider와 Room outbox에 먼저 보관하고 다음 브리지 시작·재연결 시 재동기화한다. 이미 해당 메시지의 envelope에 포함된 기기는 Android 재연결 시 서버 히스토리를 시퀀스 순서대로 회수한다. 새로 등록한 기기는 등록 이전 envelope를 복호화할 수 없고, 기존 승인 기기에서 과거 내역 공유를 실행해야 읽을 수 있다. relay outbox는 동일 `mid`로 서버 ACK를 재시도하며, 통신사 호출 경계에서 프로세스가 중단된 아주 좁은 구간은 중복 발신 가능성이 있는 at-least-once 복구 정책을 사용한다.
- envelope 암호화는 서버 평문 노출을 막지만 Signal Double Ratchet 수준의 전방 비밀성은 제공하지 않는다.

## 주요 업데이트 요약

- (v0.11) **QR 기기 페어링**: 새 기기가 QR을 띄우고 기존 기기가 스캔한 뒤, 양쪽 화면의 안전번호를 사람이 비교해 승인한다. 승인 서명이 페어링 세션을 바인딩하므로 재생이 불가능하다. 지문을 눈으로 대조하던 기존 v1 경로도 계속 동작한다. [설계·구현 위치](docs/QR_PAIRING_DESIGN.md)
- (v0.11) **온보딩 재설계·보안 정리**: 폼 중심 로그인 화면(테마 추종), 비밀번호 재설정 경로의 CPU 소모 취약점 수정, 대화 멤버 응답에서 타 계정 기기 이름 제거, 사용자 열거 차단, 키 디렉터리 다운그레이드 경로 제거
- (v0.10.8) **보안 경직화·신뢰 문서·CI**: 이메일 엔드포인트 rate limit, Android 로그아웃 시 로컬 평문 삭제, 구조화된 인증 거부 코드, [암호 명세](docs/CRYPTO_SPEC.md)·[QR 페어링 설계](docs/QR_PAIRING_DESIGN.md) 문서화, GitHub Actions CI(서버·웹·Android·Rust·시크릿 스캔)
- (v0.9) **GitHub 이슈 4건 해결 + Android 검색**: 차단 규칙 삭제 부활 방지, 안전한 인앱 설치 결과 처리, 로컬 연락처 이름 동기화, `010`↔`+82` 답장 스레드 통합·오프라인 즉시 표시, 대화·메시지 로컬 검색
- (v0.10) **인증·알림·연락처 동기화 강화**: Android Keystore 자격 증명 보호, 서버 로그아웃 즉시 세션 폐기, 최신 문자 하단 고정, 알림 탭 시 해당 Room 대화 직접 열기, 연락처 이름 웹·다른 Android 기기 동기화
- (v0.8) **Android UI 리디자인**: 웹 브랜드(딥 다크 + teal→sky 그라디언트)를 앱 전체에 이식 — 카드 레이아웃, 챗 버블, 그라디언트 버튼·아바타
- (v0.7) **Android 인앱 자동 업데이트**: 새 릴리스를 앱이 스스로 감지→다운로드→설치한다. v0.13.0부터 완전 무인. [사용법 참고](#android-인앱-자동-업데이트-v013)
- (v0.6) **차단 목록 기기 간 동기화**: 키워드·발신번호를 한 기기에서 추가/삭제하면 온라인 기기에는 `blocklist_updated` 이벤트로 즉시 반영되고, 오프라인 기기는 재연결할 때 동기화한다.
- (v0.6) **데스크톱 알림**: 웹에서 새 수신 메시지 도착 시 OS 알림(권한 요청, 설정 유지).
- (v0.6) **메시지·대화 검색**: 사이드바에서 대화 이름과 복호화된 메시지 본문 통합 검색.
- (v0.6) **대화 이름 변경**: SMS 대화에 연락처 이름 등의 라벨을 붙이고 전 기기에 반영.
- (v0.6) **대화 내보내기**: 대화 내용을 CSV(Excel UTF-8 BOM) 또는 JSON으로 다운로드.
- (v0.5) 테마: 라이트·다크·시스템 설정 따라가기.

상세한 자산·위협·보장 범위는 [`docs/THREAT_MODEL.md`](docs/THREAT_MODEL.md)를 참고한다.

## 라이선스

[MIT](LICENSE) © leetomo2528
