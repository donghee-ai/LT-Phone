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
