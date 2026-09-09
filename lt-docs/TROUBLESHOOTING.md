# 문제 해결

## 빌드가 하위 모듈 오류로 중단됨

```bash
git submodule update --init --recursive
git submodule status --recursive
```

최상위 저장소만 복제하고 하위 모듈을 빠뜨리면 Web UI와 Rust 라이브러리를 만들 수 없습니다.

## Android NDK 또는 Rust target을 찾지 못함

`ANDROID_HOME`과 `ANDROID_NDK_HOME`이 현재 환경의 실제 SDK 경로를 가리키는지 확인한 뒤 다음을
실행합니다.

```bash
./aw-server-rust/install-ndk.sh
RELEASE=true make aw-server-rust
python3 scripts/check-jnilibs.py
```

## 앱 사용 기록이 없음

Android 설정의 **사용 정보 접근**에서 LT Phone을 허용했는지 확인합니다. 배터리 최적화가 앱의
백그라운드 서비스를 제한하는지도 확인하세요.

## 브라우저 제목/URL이 없음

- LT Phone 접근성 서비스를 켰는지 확인합니다.
- 현재 브라우저가 watcher에서 지원되는지 확인합니다.
- 브라우저 UI 변경으로 주소 표시줄 노드가 달라졌다면 `UrlExtractionTest`에 익명 샘플을 추가해
  추출 규칙을 검증합니다.

실제 방문 URL이나 페이지 제목을 이슈와 테스트에 붙이지 마세요. 동일한 구조의 `example.com` 샘플로
바꿔서 재현하세요.

## 미디어 기록이 없음

알림 접근 권한을 켜고 미디어 앱의 알림이 허용되어 있는지 확인합니다. 일부 앱은 표준 미디어 알림을
제공하지 않아 기록되지 않을 수 있습니다.

## 서버 전송이 실행되지 않음

1. 전송 스위치, 서버 주소, 기기 이름, 비밀값을 확인합니다.
2. HTTPS 인증서가 Android에서 신뢰되는지 확인합니다.
3. 서버가 `/ingest/aw`와 `LT1` 인증을 구현했는지 확인합니다.
4. 기기 시각이 크게 틀어져 있지 않은지 확인합니다.
5. 401 이후에는 설정을 저장해 인증 오류 정지 상태를 해제합니다.

로그를 공유할 때 서버 URL, 기기 이름, Authorization 헤더, 이벤트 본문을 먼저 지우세요.

## APK가 기존 앱 위에 설치되지 않음

서명 키가 다른 APK는 업데이트로 설치할 수 없습니다. 로컬 데이터가 필요하면 먼저 내보낸 뒤 기존
앱을 제거하고 공개 릴리즈 APK를 새로 설치하세요.
