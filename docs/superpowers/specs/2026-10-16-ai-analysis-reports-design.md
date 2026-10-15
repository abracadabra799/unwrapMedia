# AI 대화 — 분석 창 결과를 리포트로 제공 (설계)

날짜: 2026-10-16 · 상태: 승인됨 (사용자, 대화 중) · 하위 프로젝트 3/4 ("AI가 화면을 더 보게 하기")

## 배경

AI 대화는 컨텐츠 검사 결과를 컨텍스트에 넣고, A/V sync·bitstream 결과는 창이 `TabState`에 보관한 것을 AI가
`@@unwrapmedia report <avsync|bitstream>`로 요청한다(`ui/ai/AiToolBridge.kt`). 나머지 분석 창 8개의 결과는 창 안에만 있어
AI가 볼 수 없다.

## 결정 (사용자)

- 대상: HDR/SEI 메타데이터, HRD 버퍼 시뮬레이션, 프레임 간격, 공간 비디오(MV-HEVC), 타임드 메타데이터, C2PA, SEF 무결성,
  게인맵. (Quality Compare는 두 파일 비교라 제외.)
- 전달: 혼합 — 가벼운 분석은 AI 요청 시 앱이 직접 실행, 무거운 분석은 사용자가 창에서 실행한 결과만.

## 리포트 종류

| kind | 방식 | 출처 | 적용 조건(컨텍스트 목록에 보일 때) |
|---|---|---|---|
| `c2pa` | 요청 시 실행(타임아웃 20 s) | `readC2paManifestStore` + `verifyC2paHardBinding` | 파일에 C2PA 저장소가 있을 때(메뉴 활성화와 같은 판정) |
| `sef` | 요청 시 실행 | `SefIntegrityAnalyzer.analyze` | 트리에 `sefd` |
| `gainmap` | 요청 시 실행 | `tab.gainmapInfo` + `GainmapValidator.validate` | `gainmapInfo.hasGainmap` |
| `spatial` | 요청 시 실행(타임아웃 20 s) | `analyzeSpatialVideo` | VIDEO + 트리에 `lhvC` |
| `interval` | 요청 시 계산(순수) | `computeFrameIntervals(tab.gopFrames)` | VIDEO; 프레임 분석 전이면 "프레임 분석 필요" |
| `hdr` | 창 결과만 | `scanHdrMetadata` 결과 + `detectHdrAnomalies`(기본 임계값) | VIDEO |
| `hrd` | 창 결과만 | 창의 현재 `HrdSimulationReport`(사용자 파라미터) | VIDEO |
| `timed` | 창 결과만 | `readTimedMetadataReport` | VIDEO + 트리에 `camm`/`gpmd`/`mebx` 트랙 |
| `avsync`, `bitstream` | 기존 그대로 | | |

- 창 결과 보관: `TabState`에 `StoredAnalysis<T>(value, identity: ContentFileIdentity?)`로 저장. 요청 시 identity가 현재 파일과
  다르면 "파일이 바뀌어 결과가 무효 — 다시 실행" 응답(거짓 결과 금지, 규칙 7).
- 요청 시 실행은 IO 스레드, 타임아웃 시 "시간 초과", 예외는 경로를 지운 사유로 응답. 취소(stop/탭 닫기)되면 중단.
- 응답 텍스트: 리포트마다 목록 상한(예: 이상 항목 30개)과 브리지의 `MAX_TOOL_RESULT_CHARS`(8000), `scrubPaths` 적용.

## 컨텍스트 목록

전송 시 컨텍스트에 한 섹션 추가(해당 파일에 적용되는 kind만):
```
### [분석 리포트 — 필요하면 `@@unwrapmedia report <kind>`로 요청]
- c2pa: 요청 가능 · sef: 요청 가능 · interval: 프레임 분석 필요(…메뉴…)
- hdr: 실행됨 · hrd: 미실행 — <메뉴 이름>에서 실행하면 요청 가능 · timed: …
```
`TOOL_PROTOCOL_HELP`의 report 목록도 전체 kind로 갱신.

## 개인정보 (기존 민감정보 정책과 동일)

- `timed`: GPS 센서 표본의 좌표는 보내지 않는다 — 센서 종류별 표본 수, 시간 범위, 샘플링 주기만. 자이로/가속도/방향은 축별
  min/max/mean.
- `c2pa`: 클레임 생성기·generatorInfo, 포맷, assertion **라벨**, action 이름·softwareAgent·digitalSourceType, ingredient 수,
  서명 알고리즘·체인 길이·오류, 하드바인딩 상태·알고리즘·해시/제외 바이트 수. **보내지 않음**: claim title(자유 텍스트),
  instanceId, 인증서 subject/issuer(사람 이름 가능 — "있음"만), assertion 내용, action when 시각.
- `sef`: 전체 판정, 구조 검사(라벨·판정·세부), 의미 검사는 라벨·판정만(세부에 MCC·시각 값이 들어갈 수 있음), 디렉터리
  항목(번호·마커·이름·선언 오프셋/길이·범위·마커 일치·판정). 항목 값은 보내지 않는다.
- `gainmap`: 원본 XMP는 보내지 않는다(길이만).

## 테스트

- 렌더러별 단위 테스트(생성한 객체로): 형식, 상한, 개인정보 항목 부재(GPS 좌표 숫자, 인증서 subject, claim title, SEF 의미
  검사 세부가 출력에 없음).
- 브리지: 창 결과 없음/무효(identity 불일치)/있음, 요청 시 실행 성공/예외/타임아웃, 알 수 없는 kind 거부.
- 컨텍스트 목록: 적용 조건별 노출(예: SEF 없는 파일엔 sef 없음).
- 실파일: 기존 fixture(C2PA, SEF, 게인맵 JPEG, HDR10 HEVC)가 있으면 그걸로 요청 시 실행 경로를 확인.
