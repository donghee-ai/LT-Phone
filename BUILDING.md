# 빌드 안내

LT Phone은 Android 코드 외에 Rust 서버 라이브러리와 Web UI를 함께 빌드합니다. 저장소의 하위
모듈까지 있어야 완전한 APK를 만들 수 있으므로 반드시 `--recurse-submodules`로 복제하세요.

## 요구 사항

- Git과 GNU Make
- JDK 17
- Node.js 20
- Rust stable
- Android SDK Platform 36 및 Build Tools 36
- Android NDK `28.2.13676358`
- Python 3

Windows에서는 WSL2에서 Make/Rust 빌드를 실행하는 방식을 권장합니다. 경로는 특정 사용자 폴더에
고정하지 말고 원하는 작업 디렉터리를 사용하세요.

## 처음부터 빌드

```bash
git clone --recurse-submodules <repository-url> lt-phone
cd lt-phone
export ANDROID_HOME=/path/to/Android/Sdk
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/28.2.13676358"
./aw-server-rust/install-ndk.sh
make aw-server-rust
./gradlew test assembleDebug
```

디버그 APK는 `mobile/build/outputs/apk/debug/mobile-debug.apk`에 생성됩니다. 디버그 빌드는
`arm64-v8a`와 `x86_64`를 대상으로 합니다.

네 ABI를 포함한 릴리즈용 네이티브 라이브러리는 다음처럼 만듭니다.

```bash
RELEASE=true make aw-server-rust
python3 scripts/check-jnilibs.py
./gradlew assembleRelease
```

서명 속성이 없으면 릴리즈 APK는 서명되지 않습니다. 공개 배포용 서명과 GitHub Actions 설정은
[RELEASE.md](RELEASE.md)를 따르세요.

## 공개 전 검사

```bash
python3 scripts/check-jnilibs.py
./gradlew test
```

개인정보 감사 규칙과 검사 입력은 저장소 밖의 로컬 도구로 관리합니다. 공개 저장소에는 정리된
브랜치만 일반 push하고 `--mirror` 또는 `--all` push는 사용하지 마세요.
