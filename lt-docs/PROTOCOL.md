# 선택적 전송 프로토콜

LT Phone의 push 기능은 사용자가 운영하는 호환 서버로 ActivityWatch 버킷을 증분 전송합니다.
이 프로토콜은 앱의 로컬 사용에는 필요하지 않습니다.

## 인증 헤더

요청에는 다음 형식의 헤더가 들어갑니다.

```text
Authorization: LT1 <device>:<unix-seconds>:<nonce>:<signature>
```

서명 입력은 다음 문자열입니다.

```text
<device>\n<unix-seconds>\n<nonce>\n<lowercase-sha256-of-body>
```

`signature`는 이 문자열에 공유 비밀값으로 HMAC-SHA256을 적용한 뒤 URL-safe base64로 인코딩하고
패딩을 제거한 값입니다. 서버는 허용 가능한 시각 오차를 확인하고 nonce 재사용을 거부해야 합니다.
비밀값은 충분히 긴 무작위 값이어야 하며 URL이나 로그에 넣지 않아야 합니다.

## 이벤트 전송

```http
POST /ingest/aw
Content-Type: application/json
Authorization: LT1 ...
```

본문은 ActivityWatch export와 같은 버킷 맵을 `buckets` 아래에 담습니다.

```json
{
  "buckets": {
    "example-bucket": {
      "type": "currentwindow",
      "client": "example-client",
      "hostname": "example-device",
      "events": []
    }
  }
}
```

성공 응답은 HTTP 200과 다음 필드를 반환해야 합니다.

```json
{"buckets": 1, "events": 10, "skipped": []}
```

앱은 HTTP 200을 받은 버킷의 커서만 전진시킵니다. 같은 timestamp에서 duration이 늘어나는 이벤트를
놓치지 않도록 timestamp와 duration을 함께 커서로 관리합니다. 서버는 재전송에 대비해 멱등적으로
upsert해야 합니다.

## 프라이버시 상태

프라이버시 모드 연동은 같은 인증 방식으로 `GET /ingest/private`와
`POST /ingest/private`를 사용합니다. 시작 본문은 `{"minutes": 60}`, 종료 본문은
`{"off": true}`입니다. 이 endpoint를 구현하지 않은 서버에서도 로컬 프라이버시 모드는 동작합니다.

## 오류 처리

- `400`: 본문 또는 요청 형식 오류
- `401`: 인증/시각/nonce 오류. 앱은 반복 요청을 멈추고 설정 확인을 요구합니다.
- `404`: 서버의 ingest 기능이 없거나 꺼짐
- `413`: 요청 크기 초과
- 그 밖의 네트워크 및 서버 오류: 다음 주기에 재시도

서버 오류 본문에는 비밀값이나 전체 이벤트를 되돌려 보내지 않아야 합니다.
