# LT Phone

LT Phone은 Android 기기의 활동 기록을 사용자가 직접 관리할 수 있게 만든
[ActivityWatch Android](https://github.com/ActivityWatch/aw-android) 포크입니다.
기본 동작은 기기 내부 저장이며, 사용자가 직접 서버 주소와 인증 정보를 입력한 경우에만
호환되는 자가 호스팅 서버로 데이터를 전송합니다.

이 저장소는 포트폴리오 및 소스 공개용입니다. Google Play에는 배포하지 않으며,
설치 파일은 이 저장소의 GitHub Releases에서만 제공합니다.

## 주요 기능

- 앱 사용 시간과 AFK 상태를 로컬 ActivityWatch 버킷에 기록
- 접근성 권한을 켠 경우 지원 브라우저의 페이지 URL과 제목 기록
- 알림 접근 권한을 켠 경우 재생 중인 미디어 메타데이터 기록
- 일정 시간 동안 기록을 중단하고 해당 구간을 정리하는 프라이버시 모드
- HMAC-SHA256 인증을 사용하는 선택적 자가 호스팅 서버 전송
- watcher 상태 점검 및 Android Keystore를 이용한 전송 비밀값 보호

## 이 포크에서 추가·변경한 작업

비교 기준은 업스트림 `ActivityWatch/aw-android`의 `v0.14.0b2`입니다. 공개본을 경로와 Git
blob 해시로 대조하면 포크에만 있는 파일이 33개이고, 업스트림에도 존재하지만 내용이 바뀐 파일이
38개입니다. 이 가운데 앱의 Kotlin/Java 소스는 신규 15개, 내용 변경 25개(테스트 3개 포함)입니다.
그 밖에 업스트림 파일 12개를 공개본에서 제외했고, 3개는 내용 변경 없이 실행 권한만 달라졌습니다.

이 수치는 포크의 변경 범위를 설명하기 위한 것입니다. 기존 파일 전체를 새로 작성했다는 뜻이 아니며,
업스트림에서 이어받은 코드는 ActivityWatch 기여자에게, 아래 추가 파일과 변경분은 LT Phone 포크에
귀속됩니다.

| 작업 영역 | LT Phone에서 구현한 내용 | 주요 파일 |
|---|---|---|
| 브라우저 기록 정확도 | URL보다 먼저 도착하는 창 제목을 시간 기준으로 확정해 이전 페이지에 잘못 붙는 문제를 방지하고, 주소창 안내 문구·검색어를 URL에서 제외했습니다. 창 제목 조회와 URL 기록을 접근성 콜백의 메인 스레드에서 분리했습니다. | [`BrowserSessionTracker.kt`](mobile/src/main/java/net/activitywatch/android/watcher/BrowserSessionTracker.kt), [`UrlExtraction.kt`](mobile/src/main/java/net/activitywatch/android/watcher/UrlExtraction.kt), [`WebWatcher.kt`](mobile/src/main/java/net/activitywatch/android/watcher/WebWatcher.kt) |
| 앱·세션·미디어 수집 | 앱 사용 세션 파싱과 수집 주기를 보완하고, 화면·잠금 상태 기반 AFK 기록을 추가했습니다. 미디어 상태 처리와 YouTube Shorts 구간 기록도 보완했습니다. | [`SessionParser.kt`](mobile/src/main/java/net/activitywatch/android/parser/SessionParser.kt), [`UsageStatsWatcher.kt`](mobile/src/main/java/net/activitywatch/android/watcher/UsageStatsWatcher.kt), [`InteractionWatcher.kt`](mobile/src/main/java/net/activitywatch/android/watcher/InteractionWatcher.kt), [`MediaWatcher.kt`](mobile/src/main/java/net/activitywatch/android/watcher/MediaWatcher.kt) |
| 프라이버시 모드 | 지정 시간 동안 민감한 watcher의 조회·기록을 막고 해당 구간을 정리하며, 만료 예약과 빠른 설정 타일을 제공합니다. | [`privacy/`](mobile/src/main/java/net/activitywatch/android/privacy/)의 신규 Kotlin 파일 7개 |
| 선택적 서버 전송 | 사용자가 직접 설정한 서버에만 증분 전송하며, HMAC-SHA256 인증, 재시도·커서 관리, Android Keystore 기반 비밀값 보호를 구현했습니다. | [`push/`](mobile/src/main/java/net/activitywatch/android/push/)의 신규 Kotlin 파일 6개, [`PROTOCOL.md`](lt-docs/PROTOCOL.md) |
| 상태 점검과 수명 관리 | watcher가 실제 이벤트를 만들고 있는지 확인하는 상태 점검을 추가하고, 백그라운드 서비스·알람·작업 스케줄링을 연결했습니다. | [`WatcherHealth.kt`](mobile/src/main/java/net/activitywatch/android/watcher/WatcherHealth.kt), [`BackgroundService.kt`](mobile/src/main/java/net/activitywatch/android/BackgroundService.kt), [`AlarmReceiver.kt`](mobile/src/main/java/net/activitywatch/android/watcher/AlarmReceiver.kt) |
| 공개·배포 정리 | 앱 ID와 브랜딩을 분리하고, Android 백업을 비활성화했으며, 테스트·빌드·설치·개인정보·릴리즈 문서와 배포 정제 스크립트를 추가했습니다. | [`AndroidManifest.xml`](mobile/src/main/AndroidManifest.xml), [`BUILDING.md`](BUILDING.md), [`PRIVACY.md`](PRIVACY.md), [`RELEASE.md`](RELEASE.md), [`lt-docs/`](lt-docs/) |

회귀 테스트에는 브라우저 제목/URL 전이, 주소창 안내 문구 필터링, watcher 상태 판정을 재현한 사례가
들어 있습니다. 상세 구조와 설계 경계는 [구조 문서](lt-docs/ARCHITECTURE.md)에서 확인할 수 있습니다.

## 설치

공개 릴리즈가 만들어진 뒤 [Releases](../../releases)에서 APK와 SHA-256 파일을 내려받아
설치할 수 있습니다. Play Store 설치는 지원하지 않습니다. 자세한 절차와 권한 설명은
[INSTALL.md](INSTALL.md)를 확인하세요.

아직 릴리즈가 없다면 소스에서 직접 빌드해야 합니다. 전체 절차는
[BUILDING.md](BUILDING.md)에 있습니다.

## 개인정보 보호

저장소와 릴리즈 APK에는 개발자의 방문 기록, YouTube 기록, ActivityWatch 내보내기,
서버 주소, 인증 비밀값 또는 서명 키를 포함하지 않습니다. 앱이 수집하는 정보와 전송 조건은
[PRIVACY.md](PRIVACY.md)에 명시했습니다.

공개 전 개인정보 감사는 저장소 밖에 보관한 로컬 도구로 수행합니다. 감사 규칙과 검사 입력,
키 저장소, 실제 데이터 내보내기, 개인용 APK는 `aw-android` 디렉터리 안으로 복사하지 마세요.

## 빌드와 릴리즈

- 개발 빌드: [BUILDING.md](BUILDING.md)
- GitHub Releases 배포: [RELEASE.md](RELEASE.md)
- 구조와 전송 프로토콜: [lt-docs/README.md](lt-docs/README.md)
- 원본 프로젝트 문서: [README.upstream.md](README.upstream.md)

## 원본 프로젝트와 라이선스

이 프로젝트는 `v0.14.0b2`를 기준으로 분기한
[ActivityWatch/aw-android](https://github.com/ActivityWatch/aw-android) 포크입니다.
대부분의 기반 코드는 ActivityWatch 기여자들이 작성했습니다. 이 포크는 앱 식별자를
`net.lifetrainer.awphone`으로 분리하고 선택적 서버 전송, 프라이버시 모드, AFK watcher,
상태 점검 기능을 추가했습니다.

라이선스는 원본과 같은 [MPL-2.0](LICENSE)입니다. LT Phone은 ActivityWatch 공식 앱이 아니며
ActivityWatch 프로젝트의 보증을 받지 않습니다.
