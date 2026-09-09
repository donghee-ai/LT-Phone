# GitHub Releases 배포

LT Phone은 Google Play에 배포하지 않습니다. 현재 포트폴리오 공개본은 검증한 APK와 SHA-256
체크섬을 GitHub Release에 수동으로 첨부합니다. GitHub Actions 자동 배포는 나중에 별도로 설정합니다.

## CI 준비 전 수동 배포

GitHub Actions용 서명 secret을 아직 설정하지 않았어도 로컬에서 검증한 APK를 포트폴리오용
GitHub Release에 직접 첨부할 수 있습니다.

1. 저장소 밖의 로컬 개인정보 감사, 단위 테스트, Lint, APK 감사를 모두 통과시킵니다.
2. GitHub 저장소의 **Releases → Draft a new release**에서 현재 공개 커밋을 가리키는 새 태그를 만듭니다.
3. `dist/lt-phone-<version>.apk`와 같은 이름의 `.sha256` 파일만 첨부합니다.
4. Release를 게시한 뒤 APK를 다시 내려받아 SHA-256이 첨부 파일과 일치하는지 확인합니다.

서명 키와 비밀번호 파일, 실제 데이터 내보내기, 저장소 상위 디렉터리는 첨부하거나 업로드하지
않습니다. 이후 CI를 설정할 때도 같은 공개용 키를 유지해야 기존 설치에 업데이트할 수 있습니다.

## CI 설정 후: 공개용 서명 키 등록

기존 개인용 키를 재사용하지 말고 공개 릴리즈 전용 키를 오프라인에서 생성하세요. 인증서 DN에는
실명, 이메일, 도시, 국가를 넣지 않는 것을 권장합니다. 아래 절차는 CI를 활성화할 때 사용합니다.

```bash
keytool -genkeypair \
  -keystore lt-phone-release.jks \
  -alias lt-phone \
  -keyalg RSA -keysize 4096 -validity 10000 \
  -dname "CN=LT Phone, O=LT Phone"
```

키 저장소는 저장소에 커밋하지 말고 안전한 오프라인 백업을 만드세요. 이 키를 잃으면 동일 앱을
업데이트할 수 없습니다.

키 저장소를 base64 한 줄로 변환해 GitHub 저장소의 Actions secrets에 등록합니다.

```bash
base64 -w 0 lt-phone-release.jks
```

필요한 secret 이름은 다음과 같습니다.

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

## 릴리즈 만들기

1. `mobile/build.gradle`의 `versionName`과 `versionCode`를 올립니다.
2. 로컬 개인정보 감사와 `./gradlew test`를 통과시킵니다.
3. 버전과 같은 태그를 push합니다.

   ```bash
   git tag -a v0.14.1 -m "Release v0.14.1"
   git push origin main
   git push origin v0.14.1
   ```

4. Actions의 `Build and release`가 성공했는지 확인합니다.
5. Release에 APK와 `.sha256`이 함께 있고, 인증서 정보가 공개용 DN인지 확인한 뒤 공개합니다.

태그의 버전과 `versionName`이 다르거나 서명 secret이 없으면 릴리즈 job은 실패하도록 구성되어
있습니다. CI는 인증서 DN에 위치·이메일 필드가 있으면 배포를 중단합니다.

## 절대 올리지 않는 파일

- `.jks`, `.keystore`, `.p12`, `.pfx` 서명 파일과 비밀번호
- 실제 ActivityWatch/브라우저/미디어 내보내기
- 개인 서버 주소, ingest secret, SSH 설정
- 개발 중 개인 키로 서명한 APK
- 저장소 상위 디렉터리의 개인 자료

정리된 브랜치만 `git push origin main`으로 전송하세요. 로컬의 다른 브랜치와 reflog까지 보낼 수 있는
`git push --all`과 `git push --mirror`는 사용하지 마세요.
