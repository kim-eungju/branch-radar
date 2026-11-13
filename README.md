# Branch Radar

GitLab 프로젝트의 여러 브랜치를 한 화면에서 비교하는 사내 도구입니다. 조회가 중심이고, 쓰기는 **Flyway 관리의 커밋** 하나뿐입니다.

- 브랜치별 Flyway 마이그레이션 상태 (최신 / 뒤처짐 / 충돌 위험 / 파일 없음 / 조회 불가)
- 같은 Flyway 버전에서 서로 다른 SQL이 있는지 감지
- A/B 브랜치 비교 (Flyway + Git 커밋)
- SQL 미리보기, GitLab 바로가기
- 브랜치별 최근 GitLab Tag(패키지 버전)
- 오랫동안 커밋이 없는 브랜치 표시
- Flyway 관리: 기준 브랜치의 Flyway 목록, 다음 Flyway를 함께 작성(임시저장)해 여러 브랜치에 커밋

DB, 캐시 서버, 스케줄러를 쓰지 않습니다(로컬 폴더 `flyway-files`에 Flyway 파일과 임시저장만 둡니다). 화면을 열 때마다 GitLab REST API를 호출해 최신 정보를 가져옵니다.
**쓰기 작업은 "새 Flyway 파일 1개를 추가하는 커밋"뿐입니다.** Flyway 실행, 기존 파일 수정·삭제, merge, cherry-pick, 브랜치 삭제는 하지 않습니다.

## 빠른 시작

1. **Java 17**을 설치합니다. (Gradle은 따로 설치하지 않아도 됩니다. `gradlew` 포함)
2. jar를 만듭니다. 바탕화면에 `branch-radar.jar`가 생깁니다.

   ```bash
   ./gradlew bootJar        # Windows: gradlew.bat bootJar
   ```

3. jar를 둘 폴더를 정하고, 그 안에 `config/application.yml`을 만들어 내 환경 값만 적습니다.

   ```
   branch-radar/
   ├─ branch-radar.jar
   └─ config/
      └─ application.yml
   ```

   ```yaml
   branch-radar:
     gitlab:
       base-url: https://gitlab.mycompany.com   # 끝에 / 없이
       project-id: group/my-app                 # 숫자 ID 도 가능
       token: glpat-xxxxxxxxxxxxxxxx
     flyway:
       workers: [ejkim, ddd, aaa]               # Flyway 커밋 등록자
     branch:
       base: develop
   ```

4. 그 폴더에서 실행하고 브라우저로 **http://localhost:8443** 에 접속합니다.

   ```bash
   cd branch-radar
   java -jar branch-radar.jar
   ```

## 설정 넣는 방법

jar 안의 `src/main/resources/application.yml`에는 **예시 기본값만** 들어 있습니다. 실제 값은 jar 밖에서 넣으므로, 설정을 바꿔도 다시 빌드할 필요가 없습니다.
아래 방법 중 편한 것을 쓰면 되고, 적은 항목만 기본값을 덮어씁니다(Spring Boot 기본 기능). 아래쪽일수록 우선합니다.

| 방법 | 예 |
|---|---|
| 실행하는 폴더의 `config/application.yml` (또는 `application.yml`) | 위 "빠른 시작" 3번 |
| 환경 변수 (점·하이픈 → `_`, 대문자) | `BRANCH_RADAR_GITLAB_TOKEN=glpat-...` |
| 실행 인자 | `java -jar branch-radar.jar --branch-radar.gitlab.project-id=group/my-app` |

> 토큰은 소스의 `src/main/resources/application.yml`에 적지 마세요. 그 파일은 git에 올라갑니다.
> `config/` 폴더나 환경 변수에 넣으면 저장소와 jar 어디에도 남지 않습니다.

## GitLab 토큰 발급

1. GitLab → 우측 상단 프로필 → **Preferences → Access tokens** (또는 프로젝트 **Settings → Access tokens**)
2. 조회만 쓸 때는 **`read_api`** 권한에 Reporter 역할이면 충분합니다.
   **Flyway 관리의 커밋** 을 쓰려면 **`api`** 권한과 Developer 이상 역할이 필요하고,
   보호 브랜치에 커밋하려면 토큰 계정이 그 브랜치의 "Allowed to push" 대상이어야 합니다.
3. 발급한 토큰을 `branch-radar.gitlab.token`에 넣습니다 (위 "설정 넣는 방법" 참고).

토큰이 없어도 실행은 되지만, 공개 프로젝트만 조회할 수 있고 화면에 `토큰 없음` 배지가 표시됩니다.

## 설정 항목

| 항목 | 설명 | 기본값 |
|---|---|---|
| `branch-radar.gitlab.base-url` | GitLab 주소 | `https://gitlab.example.com` |
| `branch-radar.gitlab.project-id` | 숫자 ID 또는 `group/project` 경로 | `123` |
| `branch-radar.gitlab.token` | GitLab 토큰 (직접 입력) | 비어 있음 |
| `branch-radar.gitlab.connect-timeout` / `read-timeout` | API 호출 타임아웃 | 5s / 30s |
| `branch-radar.gitlab.max-pages` | 목록 API 최대 페이지 수 (페이지당 100건). 넘으면 "일부 결과"로 표시 | 50 |
| `branch-radar.flyway.path` | 저장소 기준 마이그레이션 디렉터리 (하위 폴더 포함) | `src/main/resources/db/migration` |
| `branch-radar.flyway.versioned-prefix` / `repeatable-prefix` / `separator` / `suffixes` | Flyway 파일명 규칙 | `V` / `R` / `__` / `.sql` |
| `branch-radar.flyway.date-aware-ordering` | 날짜 형식 버전을 날짜 의미로 정렬 | `true` |
| `branch-radar.flyway.local-dir` | Flyway 파일과 임시저장을 두는 로컬 폴더 (실행 위치 기준) | `flyway-files` |
| `branch-radar.flyway.sync-on-startup` | 시작할 때 기준 브랜치의 Flyway 파일을 로컬 폴더로 내려받기 | `true` |
| `branch-radar.flyway.new-file-description` | Flyway 관리에서 만드는 파일명의 설명 부분 | `VADA` |
| `branch-radar.flyway.workers` | Flyway 관리에서 고를 수 있는 커밋 등록자 목록. 예) `[ejkim, ddd, aaa]`. 비어 있으면 커밋 불가 | 비어 있음 |
| `branch-radar.branch.base` | 대시보드 기본 기준 브랜치이자 Flyway 관리의 기준 브랜치 | `develop` |
| `branch-radar.branch.fetch-concurrency` | 브랜치별 파일 목록을 동시에 조회하는 수 | 6 |
| `branch-radar.stale-branch.warning-days` | 이 일수 이상 커밋이 없으면 "미갱신" (1 이상) | 30 |
| `branch-radar.stale-branch.danger-days` | 이 일수 이상이면 "장기 미갱신" (`warning-days`보다 커야 함) | 60 |
| `branch-radar.stale-branch.timezone` | 경과 일수 계산 기준 타임존 | `Asia/Seoul` |
| `branch-radar.tag.enabled` | Tag 조회 사용 여부 | `true` |
| `branch-radar.tag.sort` | 최신 Tag 정렬 기준: `semver` / `created` / `name` | `semver` |
| `branch-radar.tag.version-pattern` | Tag 이름에서 버전을 뽑는 정규식 (첫 번째 그룹) | 자동 (`1.2.3`, `1.2.3-rc.1`) |
| `branch-radar.tag.max-lookups` | 브랜치별 Tag 판정에 쓰는 API 호출 상한 | 50 |

이 밖에 `server.port`(기본 `8443`)로 접속 포트를 바꿀 수 있습니다.

`stale-branch` 값이 규칙에 맞지 않거나 타임존 이름이 틀리면 **애플리케이션이 시작되지 않습니다.**

## 개발

```bash
./gradlew bootRun     # 소스에서 바로 실행 → http://localhost:8443 (프로젝트 폴더의 config/application.yml 도 읽음)
./gradlew test        # 테스트 (GitLab 서버 없이 실행)
./gradlew bootJar     # 바탕화면에 branch-radar.jar 생성 (경로·이름은 build.gradle 의 bootJar 설정)
```

`bootRun`으로 개발할 때도 프로젝트 폴더에 `config/application.yml`을 만들어 쓰면 됩니다. `config/` 폴더는 `.gitignore`에 들어 있어서 올라가지 않습니다.

Bootstrap(WebJar)과 폰트(Pretendard, JetBrains Mono — 둘 다 SIL Open Font License)는 jar 안에 들어 있어서,
인터넷이 막힌 사내망에서도 화면이 똑같이 보입니다.

## 화면

| 경로 | 내용 |
|---|---|
| `/` | 대시보드. `?base=브랜치`로 기준 브랜치를 바꿉니다. 이 값은 저장되지 않습니다. |
| `/compare?a=A&b=B` | A/B 비교 — "A 브랜치에 비해 B 브랜치에 없는 항목" |
| `/api/sql?ref=&path=` | SQL 미리보기용 JSON. 마이그레이션 디렉터리 안의 SQL 파일만 허용합니다. |
| `/flyway` | Flyway 관리. 기준 브랜치의 Flyway 목록과 작성 중인 Flyway |
| `/api/flyway/draft`, `/api/flyway/validate`, `/api/flyway/commit` | 관리 화면이 쓰는 JSON API (임시저장 / 검증 / 커밋) |

## Flyway 관리

`/flyway` 화면 하나에서 기준 브랜치(`branch.base`, 기본 `develop`)의 Flyway 목록을 보고, 다음에 추가할 Flyway를 작성해 커밋합니다.

### 목록

- 기준 브랜치의 모든 버전 Flyway를 최신 순으로 보여 줍니다. 파일명을 누르면 SQL을 미리 봅니다.
- 시작할 때 기준 브랜치의 Flyway 파일을 모두 로컬 폴더(`flyway.local-dir`, 기본 `flyway-files`)에 내려받습니다.
  **이미 있는 파일은 건너뜁니다**(덮어쓰지 않음). 백그라운드로 실행하므로 GitLab이 느려도 시작은 막지 않고, 결과는 화면 맨 아래와 로그에 나옵니다.
  끄려면 `flyway.sync-on-startup: false`.

### 작성 중인 Flyway (임시저장)

- **모든 작업자가 함께 이어 쓰는 한 개**입니다. `임시저장`을 누르면 서버의 `flyway-files/.draft.json`에 저장되고, 대시보드 맨 위에 "작성 중인 Flyway"로 표시됩니다.
- 다른 사람이 먼저 저장한 뒤에 예전 화면에서 저장하거나 커밋하려 하면 거부합니다(409). 남의 내용을 모르고 덮어쓰거나 빼고 커밋하는 일을 막기 위해서입니다. 새로고침해서 최신 내용에 이어 쓰면 됩니다.
- SQL과 설명을 모두 비우고 임시저장하면 임시저장이 지워집니다.

### 커밋

- **등록자**를 반드시 골라야 합니다. 고를 수 있는 사람은 `flyway.workers` 설정의 목록이고, 목록이 비어 있으면 커밋할 수 없습니다. 설명은 선택입니다.
- **파일명은 자동**입니다: `V{오늘 yyMMdd}.{순번 3자리}__VADA.sql`. 순번은 전체 브랜치에서 오늘 날짜로 쓰인 최대 순번 + 1이고, 날짜는 `stale-branch.timezone` 기준입니다. `VADA` 부분은 `flyway.new-file-description`으로 바꿀 수 있습니다.
  저장 위치는 기준 브랜치의 최신 Flyway가 있는 폴더입니다.
- **커밋 메시지 양식**(고정):

  ```
  🐦 V261102.001__VADA.sql

  등록자 : {등록자}
  설명 : {설명}
  ```

- **브랜치를 여러 개** 고를 수 있습니다. 같은 파일을 브랜치마다 커밋합니다(GitLab Commits API `action=create`, MR 없음). 임시저장에 고른 브랜치가 없으면 기준 브랜치만 선택되어 있습니다.
- 커밋 전에 검증 결과를 확인하는 창이 뜹니다. **하나라도 오류가 있으면 어느 브랜치에도 커밋하지 않습니다.**
  - 오류: 등록자 미선택, SQL 없음 또는 1MB 초과, 브랜치 미선택, 브랜치에 같은 버전·같은 경로의 파일이 이미 있음, 브랜치 조회 실패.
  - 경고: 보호 브랜치, 브랜치 최신보다 낮은 버전, 다른 브랜치에 같은 버전의 다른 파일이 있음.
- 커밋한 파일은 로컬 폴더에도 같은 경로로 저장하고, 모든 브랜치에 성공하면 임시저장을 지웁니다.
- 일부 브랜치만 GitLab이 거부하면(권한 등), 성공한 브랜치는 그대로 두고 **실패한 브랜치와 그때의 버전을 임시저장에 남깁니다.** 원인을 해결하고 다시 커밋하면 같은 파일명으로 올라갑니다.
- SQL은 UTF-8, 줄바꿈 LF로 저장하고 파일 끝에 줄바꿈을 붙입니다. 기존 파일은 덮어쓰지 않습니다(같은 경로면 GitLab이 거부).

### 주의

- **이 도구에는 로그인이 없습니다.** 화면에 접속할 수 있는 사람은 누구나 토큰 계정의 권한으로 커밋할 수 있고, 등록자는 본인 확인 없이 고르는 값입니다. 접근 가능한 네트워크 범위를 제한해서 운영하세요.
- 커밋의 작성자(author)와 커미터는 토큰 계정이고, 등록자는 커밋 메시지에만 남습니다.
- 쓰기 API는 `application/json` 본문만 받으므로, 다른 웹사이트가 사용자 브라우저를 이용해 몰래 커밋을 일으킬 수 없습니다.
- 이 도구 밖에서(로컬 push 등) 같은 날 같은 순번을 올리는 경우까지는 막지 못합니다. 커밋 직전에 브랜치를 다시 조회해 줄일 뿐입니다.
- 커밋하면 저장소 설정에 따라 CI가 바로 실행될 수 있습니다. 한 번 DB에 적용된 Flyway 파일은 고칠 수 없으니 확인 창의 내용을 꼭 읽어 보세요.

## 판정 규칙

### Flyway 버전

- 파일명 `V250212.001__VADA.sql`에서 버전 `250212.001`을 뽑습니다. `_`는 `.`과 같게 취급합니다.
- 버전은 **Flyway와 같은 방식으로 비교합니다.** 각 부분을 정수로 비교하고(`V2` < `V10`), 끝의 0은 무시합니다(`1.0` = `1`). 문자열 사전순으로 비교하지 않습니다.
- `date-aware-ordering: true`이면, 버전의 첫 부분이 `yyMMdd`, `yyyyMMdd`, `yyyyMMddHHmm`, `yyyyMMddHHmmss` 중 하나일 때 날짜로 바꿔서 비교합니다.
  모든 파일이 같은 날짜 형식을 쓰면 결과는 Flyway 순서와 똑같습니다. `V251231`과 `V20250101`처럼 형식이 섞여 있을 때만 결과가 달라집니다.
- 최신 여부는 **버전으로만** 판단합니다. Git 커밋 날짜나 파일 수정 시각은 보지 않습니다.
- `R__`(Repeatable)는 버전 마이그레이션과 따로 다루며, 버전 충돌로 판정하지 않습니다.

### SQL 내용 비교

- 파일 내용은 **Git blob SHA**로 비교합니다. GitLab 트리 API 응답에 이미 들어 있는 값이라 파일을 따로 내려받지 않아도 됩니다. 바이트가 하나라도 다르면 값이 달라집니다.
- blob SHA는 Flyway checksum(CRC32)과 **다른 값**입니다. 비교 화면에서는 충돌 후보에 한해 원문을 받아 Flyway 방식 CRC32도 계산해 보여 줍니다(UTF-8 가정, 참고용).
  두 값이 같으면 줄바꿈이나 BOM만 다른 경우로, Flyway는 같은 내용으로 봅니다.

### 대시보드 상태 (전체 브랜치 최신 Flyway 기준)

| 색 | 상태 | 의미 |
|---|---|---|
| 초록 | 최신 | 전체 최신 Flyway 파일이 같은 파일명·같은 내용으로 있음 |
| 노랑 | 뒤처짐 | 전체 최신 Flyway 파일이 없음. 이전 버전을 일부러 유지하는 release 브랜치일 수 있으므로, 배포 오류가 확정되었다는 뜻은 아닙니다. |
| 빨강 | 충돌 위험 | 같은 버전인데 다른 브랜치와 파일명 또는 SQL이 다름. 또는 한 브랜치 안에 같은 버전이 둘 이상 있음 (이 경우 Flyway 실행 시 실제로 오류가 납니다). |
| 회색 | 파일 없음 / 조회 불가 | 마이그레이션 파일이나 디렉터리가 없음 / API 조회 실패 |

- **전체 최신 Flyway**와 **기준 브랜치 대비 차이**는 서로 다른 기능입니다. 상태 색은 전체 최신 기준이고, "기준 브랜치 대비 누락/추가" 열은 선택한 기준 브랜치 기준입니다.
- 충돌을 판단할 때 기준 브랜치에 있는 버전은 기준 브랜치의 파일을 정답으로 보고, 그와 다른 브랜치만 빨강으로 표시합니다. 오래된 브랜치 하나 때문에 모든 브랜치가 빨강이 되는 것을 막기 위해서입니다.
  기준 브랜치에 없는 버전이면, 그 버전을 가진 브랜치를 모두 빨강으로 표시합니다.

### Git 커밋 비교

- "A에 있고 B에 없는 커밋"은 GitLab Compare API를 **`from=B&to=A`** 로 호출해서 얻습니다. 이는 `git log B..A`와 같습니다.
  커밋 목록은 `straight` 값과 관계없이 같습니다. `straight`는 diff 계산 방식에만 영향을 주므로 기본값(`false`, merge-base 기준)을 씁니다.
- 결과는 모두 **"이력상 누락 후보"** 로 표시합니다. cherry-pick, squash, rebase로 같은 변경이 이미 들어가 있으면 SHA가 달라도 반영된 상태일 수 있기 때문입니다.
  커밋 메시지에 `(cherry picked from commit …)` 기록이 있거나 제목·작성자가 같으면 힌트를 붙입니다.
  화면에서는 **cherry-pick 흔적 없음**(실제 누락 가능성 높음, 기본 표시)과 **cherry-pick 가능성 있음**(접힘)으로 나눠 보여 주고, 요약 타일 숫자는 흔적 없는 커밋 수입니다.
  커밋 탭 상단의 **작성자** 선택으로 목록을 거를 수 있습니다(화면 안에서만 거르며 API를 다시 호출하지 않습니다). 패치 내용이 같은지(patch-id)는 검증하지 않으며, 이 부분은 추후 확장 기능으로 남겨 두었습니다.
- Compare API는 페이지네이션이 없습니다. GitLab이 `compare_timeout`을 알려 오면 "결과 불완전"으로 표시합니다.

### Tag (패키지 버전)

- 최신 Tag는 `tag.sort` 정책으로 정합니다. 기본값 `semver`는 Tag 이름 속 버전을 비교하며, 정식 버전이 사전 릴리스보다 높습니다(`1.0.0` > `1.0.0-rc.1`).
- **Tag 생성일**(annotated Tag에만 있음), **대상 커밋 날짜**, **패키지 버전**은 서로 다른 값으로 나눠서 표시합니다.
- annotated Tag와 lightweight Tag 모두, Tag가 가리키는 실제 커밋을 기준으로 판단합니다.
- 브랜치별 최근 Tag: Tag를 최신 순으로 보면서 `GET /repository/commits/:sha/refs?type=branch`(그 커밋을 조상으로 포함하는 브랜치 목록)를 호출합니다.
  같은 커밋은 한 번만 조회하고, 모든 브랜치의 Tag가 정해지면 바로 멈춥니다.
  호출 상한에 걸리거나 오류가 나거나 목록이 일부만 조회되면, 추측하지 않고 **"확인 불가"** 로 표시합니다.

### 브랜치 활동 상태

- 브랜치 목록 API에 들어 있는 마지막 커밋의 `committed_date`를 그대로 씁니다. **추가 API 호출이 없습니다.**
  이 값은 브랜치를 만든 날짜가 아닙니다.
- 경과 일수는 시각 차이가 아니라, 설정한 타임존 기준 **날짜 차이**입니다. 예: 서울 기준 어제 23:50에 커밋했다면 오늘 00:10에는 1일 경과입니다.
- 0~(warning-1)일은 활성, warning~(danger-1)일은 미갱신(회색), danger일 이상은 장기 미갱신(빨강)입니다.
  날짜가 없거나 미래 날짜이면 "확인 불가"로 표시합니다.
- Protected 브랜치도 경고에서 빼지 않습니다. 오래된 브랜치라도 표시만 하고, 자동으로 삭제하지 않습니다.

## API 호출량

- 대시보드 한 번에: 프로젝트 1회, 브랜치 목록(100개당 1회), 브랜치마다 트리 조회(파일 100개당 1회), Tag 목록, Tag 판정 몇 회
- 같은 커밋을 가리키는 브랜치는 트리를 한 번만 조회합니다.
- 트리 조회 결과는 **커밋 SHA 기준으로 메모리에 캐시**합니다(최대 2000개). 커밋 내용은 바뀌지 않으므로 캐시 때문에 결과가 틀려지지 않습니다. 브랜치에 새 커밋이 생기면 SHA가 바뀌어 다시 조회합니다.
- API가 실패한 브랜치만 "조회 불가"로 표시하고, 페이지 전체가 500 오류로 끝나지 않습니다. 결과가 일부만 조회되었으면 화면 상단에 알려 줍니다.

## 구조

```
config/GitLabProperties      설정 (branch-radar.*), 시작 시 검증
client/GitLabClient          GitLab REST API (페이지네이션, URL 인코딩, 오류 → GitLabException)
client/GitLabLinks           GitLab 웹 링크 생성
service/FlywayService        파일명 해석, 대시보드 분석, A/B Flyway 비교, checksum
service/FlywayCommitService  Flyway 관리: 임시저장, 파일명 자동 지정, 검증, 여러 브랜치 커밋
service/FlywayLocalStore     로컬 폴더 입출력 (Flyway 파일, 임시저장)
service/FlywayLocalSync      시작 시 기준 브랜치 Flyway 를 로컬 폴더로 내려받기
service/BranchService        대시보드 조립 (브랜치 → 스냅샷 → 분석)
service/CompareService       A/B 비교 (Flyway + 커밋)
service/TagService           Tag 정렬, 브랜치별 최근 Tag
service/BranchActivityService 마지막 커밋 경과 일수 (Clock 주입)
controller/*                 Dashboard, Compare, SQL API, Flyway 관리
```

## 테스트

`./gradlew test` (65개). 실제 GitLab 서버 없이 실행됩니다.

- `FlywayVersionTest`, `FlywayServiceTest`: 버전 추출·정렬, 전체 최신 판정, 뒤처짐·충돌·파일 없음 판정, A/B 누락 비교, checksum
- `GitLabClientTest`: 페이지네이션, 특수문자 인코딩, 401/403/404/429/5xx 처리 (`MockRestServiceServer`)
- `CompareServiceTest`: Compare API 방향(`from=B&to=A`), API 실패 처리, cherry-pick 힌트
- `TagServiceTest`: 버전 정렬, annotated/lightweight 구분, 조상 커밋 판정, "확인 불가" 처리
- `BranchActivityServiceTest`: 0/29/30/59/60/150일 경계, null, 미래 날짜, 타임존 날짜 경계, 잘못된 설정
- `FlywayCommitServiceTest`: 파일명 자동 지정, 커밋 메시지 양식, 임시저장 충돌(revision), 여러 브랜치 커밋과 로컬 저장, 일부 실패 시 버전 고정, 시작 시 로컬 동기화
- `WebIntegrationTest`: 가짜 GitLab 서버에 연결해 대시보드·비교·SQL API·Flyway 관리 화면과 임시저장/검증/커밋 API를 확인
