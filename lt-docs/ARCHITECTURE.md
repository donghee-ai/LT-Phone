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
| `WebWatcher` | 접근성 이벤트에서 지원 브라우저 URL/제목 기록 |
| `MediaWatcher` | 알림 리스너에서 미디어 상태 기록 |
| `InteractionWatcher` | 화면과 잠금 상태에서 AFK 이벤트 기록 |
| `privacy/` | 프라이버시 모드 상태와 관련 이벤트 정리 |
| `push/` | 설정, HMAC 인증, 증분 전송 및 재시도 |
| `WatcherHealth` | watcher가 실제 이벤트를 만들고 있는지 점검 |

## 보안 경계

- 활동 이벤트는 앱 전용 저장소에 보관됩니다.
- Android 백업은 manifest와 extraction rules에서 비활성화되어 있습니다.
- 사용자가 입력한 전송 비밀값은 Android Keystore 키로 AES-GCM 암호화됩니다.
- 서버 전송은 기본값이 꺼짐이며 설정된 단일 서버를 대상으로 합니다.
- TLS 인증서는 Android의 기본 인증서 검증을 사용합니다. 공개 인터넷으로 전송할 때는 HTTPS를
  사용해야 합니다.

서버 구현과 서버에 저장된 데이터는 이 저장소의 보안 경계 밖입니다.
