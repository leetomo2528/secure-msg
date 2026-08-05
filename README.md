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

SMS의 통신사 구간 자체는 SMS 표준 특성상 E2E 암호화가 아니다. 서버는 통과한 SMS의 평문을 볼 수 없지만, 대화 라벨(현재는 전화번호)·기기·시간·메시지 크기 같은 메타데이터는 볼 수 있다.

## 계정과 비밀번호 정책

개인정보 없이 임의의 아이디만 사용한다.

- **아이디**: `a-z`, `0-9`, `_` 만 허용, 3~20자. 실명·전화번호·이메일을 쓰지 않는다.
- **비밀번호**: **총 8자 이상**이면 된다. 영문·숫자·특수문자(한글 포함)를 자유롭게 섞을 수 있으며, 문자 종류별 개수나 조합 같은 추가 제한은 없다. 상한은 1,024자.
- 비밀번호는 클라이언트(웹/Android)에서 **Argon2id**로 해시된 값만 서버로 전송되므로 서버는 원문 비밀번호를 보지 못하고, 서버는 이를 다시 bcrypt로 감싸 저장한다.

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
│       ├── SpamClassifier.kt       # 오프라인 자동 스팸 점수 판정
│       ├── BlocklistManager.kt     # 키워드/발신번호/자동 스팸 정책
│       ├── SmsProvider.kt          # 시스템 SMS Provider 입출력
│       ├── MmsProvider.kt           # MMS Provider/part 읽기·수신 저장
│       ├── MmsPduComposer.kt        # WAP multipart/related MMS PDU
│       ├── MmsSender.kt             # framework MMSC MMS 발신
│       ├── RelayContent.kt          # text/MMS envelope 내부 포맷
│       ├── CarrierStatusReceiver.kt # SMS/MMS SENT·DELIVERED 결과
│       ├── Database.kt             # Room: 스레드·격리함·처리 이력
│       └── MainActivity.kt         # 로그인·권한·기본 SMS 역할·설정
├── frontend/                       # React + TypeScript + Vite PWA
│   └── src/
│       ├── store/useStore.ts       # 로그인·암복호화·동기화 orchestration
│       ├── store/blocklist.ts      # 웹 기기 로컬 키워드 필터
│       └── components/             # 채팅·기기·차단·새 SMS UI
├── server/                         # Flask + Socket.IO + SQLite
├── docs/THREAT_MODEL.md
├── docker-compose.yml
└── Caddyfile
```

## 로컬 실행

### 1. 서버

```bash
cd ~/secure-msg/server
source .venv/bin/activate
pip install -r requirements.txt
export SECUREMSG_JWT_SECRET="$(openssl rand -hex 32)"
export SECUREMSG_CORS="http://localhost:5173"
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
./gradlew testDebugUnitTest assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk

# 운영/배포 APK(R8 난독화·리소스 축소)
./gradlew testDebugUnitTest lintDebug assembleRelease
```

앱을 처음 실행한 뒤 다음 순서로 설정한다.

1. SecureMsg 계정 로그인/가입
2. `기본 SMS 앱으로 설정`을 눌러 시스템 역할 승인
3. 이어서 표시되는 SMS·MMS·알림 권한 허용
4. Android 앱의 `차단 키워드`와 `발신번호 차단` 설정
5. 브리지 foreground 알림이 유지되는지 확인

기본 SMS 앱 역할을 승인하지 않으면 Android는 SMS를 읽을 수 있어도 다른 SMS 앱의 수신·알림을 차단할 수 없다. 따라서 “문자가 들어오면 실제로 자동 차단”하는 기능은 역할 승인 후에만 유효하다.

### Android 인앱 자동 업데이트 (v0.7)

업데이트마다 APK를 직접 받아 설치할 필요가 없다. 게임 업데이트와 같은 방식이다.

1. 앱이 12시간마다 GitHub 릴리스 API에서 최신 버전을 확인한다(차단·설정 → 앱 업데이트에서 토글).
2. 새 버전이 있으면 메인 화면 상단에 `새 버전 vX.Y.Z 출시` 배너가 뜬다.
3. `업데이트`를 누르면 진행률 표시줄과 함께 APK를 내려받고, 완료 시 시스템 설치 화면이 자동으로 열린다.
4. 최초 1회 시스템 설정에서 `이 앱의 설치 허용`(알 수 없는 앱 설치)을 켜면, 이후부터는 권한 확인 후 설치가 자동으로 이어진다.

- APK는 GitHub 릴리스의 공식 자산을 HTTPS로 내려받으며, 설치 전 시스템 패키지 설치기가 서명 일치를 검증한다.
- 업데이트가 성립하려면 서명 키가 같아야 하므로, 릴리스는 계속 이 Mac의 디버그 키스토어(`~/.android/debug.keystore`)로 서명된 APK를 사용한다. 키스토어를 잃어버리면 기존 설치를 덮어쓸 수 없으니 주의.
- 릴리스 방법: `gh release create v<version> android/app/build/outputs/apk/debug/app-debug.apk` — 이 자산이 곧 업데이트 공급 소스다.

## Oracle Cloud 배포

```bash
cd ~/secure-msg
cd frontend && npm ci && npm run build && cd ..
cat > .env <<'EOF'
SECUREMSG_DOMAIN=msg.example.com
SECUREMSG_JWT_SECRET=여기에_랜덤_32바이트_이상_시크릿
SECUREMSG_CORS=https://msg.example.com
EOF
docker compose up -d --build
```

DNS A 레코드를 서버 IP로 연결하고 Oracle VCN 보안 목록에서 80/443을 허용한다. Caddy가 `SECUREMSG_DOMAIN`을 기준으로 자동 TLS 인증서를 발급한다. 실제 운영에서는 `.env`를 백업·공유하지 말고, SQLite 볼륨과 JWT secret을 별도로 보호한다.

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
- MMS 텍스트·제목·첨부파일(최대 8개, 전체 512KB)은 구현되어 있다. Android 기본 SMS 앱 역할과
  통신사 APN/MMSC 설정이 필요하며, raw PDU 처리 차이 때문에 실제 발신·수신은 기기/SIM별로 검증해야 한다.
  웹의 MMS 첨부파일은 암호화 envelope에 포함되고, 서버는 파일 평문을 보지 못한다.
- 자동 스팸 판정은 서버 ML이 아니라 오프라인 규칙 기반 점수기다. 오탐/미탐이 있을 수 있으며, 사용자가 키워드로 보정할 수 있다.
- 차단 키워드·발신번호는 계정 단위로 **모든 기기에 동기화**된다(v0.6). 서버에는 사용자가 입력한 필터 문자열(키워드·번호)만 저장되고 메시지 평문은 여전히 서버에 닿지 않는다. 각 기기는 복호화 후 로컬에서 규칙을 적용한다.
- foreground `remoteMessaging` 서비스가 백그라운드 relay 연결을 유지하므로 Android 상태 표시줄에 지속 알림이 보인다.
- JWT가 만료되거나 서버에서 기기가 폐기되면 활성 소켓도 종료되며, 클라이언트는 로컬 기기 키를 보존한 채 다시 로그인하도록 전환한다.
- 서비스가 완전히 오프라인일 때 수신 SMS/MMS는 Provider와 Room outbox에 먼저 보관하고 다음 브리지 시작·재연결 시 재동기화한다. 웹에서 보낸 메시지도 Android 재연결 시 서버 히스토리를 시퀀스 순서대로 회수한다. relay outbox는 동일 `mid`로 서버 ACK를 재시도하며, 통신사 호출 경계에서 프로세스가 중단된 아주 좁은 구간은 중복 발신 가능성이 있는 at-least-once 복구 정책을 사용한다.
- envelope 암호화는 서버 평문 노출을 막지만 Signal Double Ratchet 수준의 전방 비밀성은 제공하지 않는다.

## v0.6 신규 기능

- (v0.7) **Android 인앱 자동 업데이트**: 새 릴리스를 앱이 스스로 감지→다운로드→설치한다. [사용법 참고](#android-인앱-자동-업데이트-v07)
- **차단 목록 기기 간 동기화**: 키워드·발신번호를 한 기기에서 추가/삭제하면 `blocklist_updated` 실시간 이벤트로 다른 기기에 즉시 반영된다.
- **데스크톱 알림**: 웹에서 새 수신 메시지 도착 시 OS 알림(권한 요청, 설정 유지).
- **메시지·대화 검색**: 사이드바에서 대화 이름과 복호화된 메시지 본문 통합 검색.
- **대화 이름 변경**: SMS 대화에 연락처 이름 등의 라벨을 붙이고 전 기기에 반영.
- **대화 내보내기**: 대화 내용을 CSV(Excel UTF-8 BOM) 또는 JSON으로 다운로드.
- (v0.5) 테마: 라이트·다크·시스템 설정 따라가기.

상세한 자산·위협·보장 범위는 [`docs/THREAT_MODEL.md`](docs/THREAT_MODEL.md)를 참고한다.

## 라이선스

[MIT](LICENSE) © leetomo2528
