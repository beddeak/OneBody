# OneBody

참가자들이 캐릭터 하나를 함께 조종하는 일심동체 플러그인입니다.

[다운로드](https://github.com/beddeak/OneBody/releases/latest)

## 설치 및 설정

Paper 26.2 · Java 25. 새 서버는 서버팩 ZIP을 빈 폴더에 풀고 Windows에서 `start.bat`을 실행하세요. 첫 실행 후 생성된 `eula.txt`를 읽고 동의하면 값을 변경한 뒤 다시 실행하세요. 기존 서버는 정지 후 `OneBody-1.0.0.jar`을 `plugins/`에 넣고 다시 시작하세요.

첫 실행 후 `plugins/OneBody/config.yml`을 편집하세요. 서버를 정상 종료하고 편집한 뒤 다시 시작하면 새 설정을 읽습니다. [기본 설정과 항목별 설명](src/main/resources/config.yml)

## 명령어

**접속한 플레이어 전원이 자동 참가**합니다. 별도 join/leave 명령어는 없습니다. 살아 있고 관전 모드가 아닌 접속자가 2명 이상이면 OP가 `/ob start`로 시작합니다. 도중 새로 접속한 사람도 자동 참가합니다. 참가자에게 **일심동체 / mod by beddeak(침댁)** Title이 표시됩니다. 서바이벌 또는 어드벤처 모드로 플레이하세요.

| 명령어 | 권한 | 동작 |
| --- | --- | --- |
| `/onebody start` | `onebody.admin` | 기존 판정/작업 정리 후 시작 또는 재시작 |
| `/onebody stop` | `onebody.admin` | 모든 판정·피해·ActionBar 작업 즉시 종료, 등록 유지 |
| `/onebody status` | `onebody.admin` | 참가자, 행동, 완료/미완료 명단, punishment 확인 |
| `/onebody reload` | `onebody.admin` | 유효한 설정만 적용, 잘못된 설정이면 기존 값 유지 |

`/ob`는 `/onebody`의 별칭입니다. 관리 명령어만 남아 있으며 `onebody.admin`은 OP 기본 허용입니다. `onebody.play` 권한은 제거했습니다.
