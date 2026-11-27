# 구조

## 데이터 흐름

```text
Android 권한/시스템 이벤트
          │
          ▼
 app · web · media · AFK watcher
          │
          ▼
 기기 내부 aw-server-rust / SQLite
          │
          ├── 로컬 Web UI와 내보내기
          │
          └── 사용자가 켠 경우에만 PushSender
                         │
                         ▼
              호환 자가 호스팅 /ingest/aw
```

앱은 ActivityWatch의 Android 서비스와 내장 Rust 서버를 기반으로 합니다. Android 쪽 watcher가
이벤트를 만들고 JNI를 통해 기기 내부 데이터베이스에 저장합니다. 저장된 데이터는 앱의 로컬 Web UI에
표시되며, 외부 전송 설정을 완료하고 활성화했을 때만 별도의 push 경로를 사용합니다.

## 주요 구성 요소

| 구성 요소 | 역할 |
|---|---|
| `BackgroundService` | watcher와 로컬 서버의 수명 관리 |
| `UsageStatsWatcher` | 앱 패키지와 사용 구간 기록 |
| `WebWatcher` | 접근성 이벤트에서 지원 브라우저 URL/제목 기록 (이벤트는 브라우저·YouTube 패키지로 제한) |
| `MediaWatcher` | 알림 리스너의 미디어 세션에서 재생 상태 기록 (알림 본문은 읽지 않음) |
| `InteractionWatcher` | 화면과 잠금 상태에서 AFK 이벤트 기록 |
| `privacy/` | 프라이버시 모드 상태와 관련 이벤트 정리 |
| `push/` | 설정, HMAC 인증, 증분 전송 및 재시도 |
| `WatcherHealth` | watcher가 실제 이벤트를 만들고 있는지 점검 |

## 최소 권한 수집 스위치

LT 설정 화면의 **수집 항목**에서 두 스위치를 켜고 끕니다. 스위치는 누르는 즉시 적용됩니다.

| 스위치 | 기본값 | 끄면 |
|---|---|---|
| 웹 주소·제목 기록 (접근성) | 켜짐 | `WebWatcher`가 열린 세션을 닫고 `disableSelf()`로 접근성 서비스를 스스로 끕니다 |
| 영상·음악 제목 기록 (알림 접근) | 새 설치는 꺼짐, 알림 접근을 쓰던 기존 설치는 켜짐 | `MediaWatcher`가 기록을 멈추고 `requestUnbind()`로 연결을 끊습니다. 권한 회수는 알림 접근 설정에서 합니다 |

접근성 서비스는 `accessibility_service_config.xml`의 `packageNames`에 적힌 브라우저와 YouTube의
이벤트만 받습니다. 이 목록은 `WebWatcher.KNOWN_BROWSER_PACKAGES` + YouTube와 같아야 하며
`AccessibilityConfigTest`가 확인합니다. 다른 앱으로 넘어가는 이벤트가 오지 않으므로 브라우저 세션은
YouTube 화면 전환, 화면 꺼짐, 서비스 중단/언바인드, 10분 무활동 중 하나로 닫힙니다. 서버 rollup은
사용 통계의 전경 앱이 브라우저인 구간에만 웹 URL을 붙입니다.

## 전송 주기와 배터리

`PushScheduler`는 foreground 서비스가 사는 동안 Handler로 주기 전송하고, 화면이 꺼질 때 최대 1분에
한 번 추가로 전송합니다. AlarmManager 폴백은 non-wakeup(`ELAPSED_REALTIME`)이라 잠든 폰을 깨우지
않습니다. 데이터는 버킷과 커서로 보존되므로 늦게 도착할 뿐 빠지지 않습니다.

## 보안 경계

- 활동 이벤트는 앱 전용 저장소에 보관됩니다.
- Android 백업은 manifest와 extraction rules에서 비활성화되어 있습니다.
- 사용자가 입력한 전송 비밀값은 Android Keystore 키로 AES-GCM 암호화됩니다.
- 서버 전송은 기본값이 꺼짐이며 설정된 단일 서버를 대상으로 합니다.
- TLS 인증서는 Android의 기본 인증서 검증을 사용합니다. 공개 인터넷으로 전송할 때는 HTTPS를
  사용해야 합니다.

서버 구현과 서버에 저장된 데이터는 이 저장소의 보안 경계 밖입니다.
