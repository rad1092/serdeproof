# 한국어 빠른 시작

SerdeProof는 serializer를 올리는 PR에서 **기존 DTO·mapper·payload를 두 버전으로 실제 실행**하는 Java 라이브러리와 CLI입니다. 별도 서버나 계정이 필요하지 않습니다. 현재 예제는 Jackson 2.22.3과 3.2.3을 사용합니다.

JDK 17 이상을 설치한 뒤 소스 checkout 폴더 또는 릴리스 ZIP의 `source/` 폴더에서 실행하세요. 제품 자체는 JVM만 필요하고, 아래 설치 검증 스크립트는 Python 3.10 이상을 사용합니다.

```sh
./mvnw -B -ntp clean install
python3 scripts/smoke.py
java -jar serdeproof-cli/target/serdeproof-cli-0.1.0.jar run \
  --manifest examples/demo.json --json reports/demo.json \
  --junit reports/demo.xml --as-of 2026-10-08 --details
```

Windows PowerShell에서는 첫 줄을 `.\mvnw.cmd -B -ntp clean install`, 두 번째 줄을 `python scripts/smoke.py`로 바꾸세요. Java 명령은 한 줄로 쓰면 같습니다. demo에는 의도한 차이가 있어 **종료 코드 1이 정상적인 시연 결과**입니다. `examples/compatible.json`은 차이가 없는 예제입니다. 다시 실행할 때는 새 보고서 파일명을 쓰거나 `--force`를 추가해 이전 보고서를 교체하세요.

릴리스 ZIP을 받았다면 압축을 푼 폴더에서 실행합니다.

```sh
java -jar lib/serdeproof-cli-0.1.0.jar run --manifest examples/release-demo.json --json reports/demo.json --junit reports/demo.xml --as-of 2026-10-08 --details
```

릴리스 파일은 서명·notarization되지 않았습니다. 체크섬은 전송 무결성 확인용입니다. Maven Central에 게시했다고 주장하지 않으며, `mvn install`은 로컬 Maven 저장소에 설치합니다.

## 내 서비스에 붙이기

1. 기존 DTO와 mapper factory를 가져오는 작은 `SerializerAdapter`를 작성합니다. [완전한 예제](../examples/custom-adapter/)에는 DTO, custom module, adapter, 라이브러리 호출 코드가 있습니다.
2. 이전 dependency 구성과 새 구성으로 각각 빌드합니다. 두 버전의 dependency를 한 JVM에 섞지 않습니다.
3. 실제 사용 패턴을 대표하는 **정제된** payload를 corpus에 넣고 manifest의 두 `classpath`와 fixture 경로를 지정합니다.
4. CI에서 CLI를 실행하고 종료 코드와 JUnit XML을 확인합니다. 이미 두 버전용 snapshot/JsonUnit 테스트가 충분하다면 그것을 유지하는 편이 준비 비용이 적을 수 있습니다.

adapter가 반환하는 `output`은 실제 mapper의 직렬화 결과이고, `observation`은 업무적으로 확인하려는 DTO의 값입니다. 날짜의 동일한 instant, enum의 이름, 정확한 금액 등을 두 버전에서 같은 방식으로 관찰하세요. 관찰하지 않은 속성의 의미까지 자동으로 검증하지는 못합니다.

## 결과 읽기

- `ACCEPTANCE`: 한 버전만 입력을 받아들였습니다.
- `OUTPUT_*`: 직렬화한 JSON의 missing/null/타입/숫자/값이 달라졌습니다.
- `OBSERVATION_*`: DTO에서 관찰한 값이 달라졌습니다.
- `ROUNDTRIP_*`: producer의 출력물을 consumer가 다시 읽을 때 수용 여부나 관찰값이 달라졌습니다. 이전→이전, 이전→신규, 신규→이전, 신규→신규를 모두 확인합니다.

양쪽이 모두 거부한 입력은 의미를 검증하지 못한 `untested`입니다. 종료 코드 0도 corpus 바깥의 호환성을 보증하지 않습니다. 1은 예상하지 않은 차이, 2는 설정·실행·규칙 오류나 제한 초과이므로 모두 CI 실패로 다루세요.

검토한 차이는 fixture·종류·방향·JSON pointer가 정확히 일치하는 rule에 이유와 만료일을 기록할 수 있습니다. [실행 가능한 규칙 예제](../examples/jackson-reviewed.json)를 참고하세요. 만료되거나 더 이상 쓰이지 않는 rule은 실패 처리됩니다.

기본 JSON/JUnit 보고서는 fixture ID와 pointer를 hash 처리하며 payload 값·파일명·절대경로·adapter 오류 내용은 내보내지 않습니다. `--details`는 ID·필드명·규칙의 이유를 공개하므로 로컬 조사에만 신중하게 사용하세요. hash도 동일성이나 추측 가능한 값의 정보를 드러낼 수 있습니다.

adapter는 사용자 권한으로 실행하는 **신뢰된 로컬 코드**입니다. 별도 JVM은 sandbox가 아닙니다. 외부 코드·운영 비밀정보를 넣기 전에 [보안 및 한계](../SECURITY.md)를 확인하세요. 상세 manifest와 Maven/Gradle 연동은 [USAGE](USAGE.md), 실제 수행한 검증은 [VALIDATION](VALIDATION.md)에 기록합니다.
