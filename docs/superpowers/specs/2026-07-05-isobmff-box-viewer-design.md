# multiViewer — ISOBMFF Box Viewer (MVP) Design

## Background

멀티미디어 코덱 개발자가 개발 중 사용하는 exiftool, mediainfo, irfanview, jpegsnoop, 010 Editor, mp4parse, Elecard StreamEye의 기능을 하나로 통합한 크로스플랫폼 뷰어를 만든다. 비개발자도 쉽게 쓸 수 있는 UI를 목표로 하며, 상용화하지 않는다.

전체 요구사항이 크므로 (파일 메타데이터 태그, 컨테이너 구조, JPEG 세그먼트/손상 진단, 헥스+구조 연동, 프레임 단위 비트스트림 분석 등) 이번 스펙은 **1단계 MVP**만 다룬다.

## Goals (MVP)

- MP4, MOV, 3GP, HEIC 파일의 ISOBMFF 박스/아톰 구조를 트리로 시각화한다.
- 알려진 박스는 의미 있는 필드(width/height, timescale/duration, codec fourcc 등)까지 해석해서 보여준다.
- 트리에서 노드/필드를 선택하면 헥스덤프에서 해당 바이트 범위를 하이라이트한다 (010 Editor 스타일).
- 손상되거나 규격을 벗어난 박스를 만나면 경고를 표시하되 파싱은 계속 진행한다.
- 파일은 드래그앤드롭 또는 열기 다이얼로그로 열고, 탭으로 여러 파일을 동시에 볼 수 있다.
- Windows / Linux / macOS에서 동일하게 동작하는 네이티브 데스크톱 앱으로 배포한다.

## Non-Goals (MVP)

- JPEG 세그먼트 구조 및 EXIF 태그 뷰 (jpegsnoop/exiftool 스타일) — 별도 스펙으로 분리.
- 프레임 단위 비트스트림 분석 (Elecard StreamEye 스타일: GOP/프레임타입/모션벡터 등) — 별도 스펙으로 분리, 상당히 큰 작업.
- 파일 편집 기능 — 읽기 전용 뷰어.
- 상용 배포/라이선싱 대응.

## Tech Stack

- **Kotlin Multiplatform + Compose for Desktop**, Android Studio에서 그대로 개발.
- Compose Desktop의 `jpackage` 연동으로 Windows(msi)/macOS(dmg)/Linux(deb) 네이티브 설치파일 생성.

## Architecture

### Layer 1 — Box Walker (파서, UI 비의존)

파일을 순회하며 모든 박스의 `type` / `size` / `offset`을 재귀적으로 추출하는 자체 구현 워커.

- 컨테이너 박스(`moov`, `trak`, `mdia`, `minf`, `stbl`, `meta` 등)는 자식으로 재귀 진입.
- 등록되지 않은/모르는 박스 타입은 raw leaf 노드로 취급.
- `size`가 파일 범위를 벗어나거나 내부 길이가 불일치하는 등 규격 위반을 만나면 해당 노드에 경고 플래그를 붙이고 best-effort로 계속 진행 (예외로 전체 파싱을 중단하지 않음).

기존 `mp4parser`(isoparser) 같은 범용 라이브러리는 채택하지 않는다. 이유:
1. 필드 단위 offset/length 노출이 필요한데(하이라이트 기능의 전제), 범용 demux 라이브러리는 이 정밀도를 제공하지 않는다.
2. 손상 파일에 관대해야 하는데, 범용 라이브러리는 보통 예외를 던지고 중단하도록 설계되어 있다.

### Layer 2 — Semantic Decoders

알려진 박스 타입마다 디코더를 등록한다. 각 디코더는 `(offset, length, fieldName, value)` 목록을 반환한다.

- 우선 지원 대상: `ftyp`, `mvhd`, `tkhd`, `mdhd`, `hdlr`, `stsd`(하위 코덱 박스 포함), `stbl` 계열(`stts`/`stsc`/`stsz`/`stco`/`co64`/`stss`/`ctts`), HEIC용 `meta`/`iloc`/`ipco`/`ipma`/`iprp`.
- 등록되지 않은 박스 타입은 raw hex로 폴백 (필드 해석 없이 구조 정보만 표시).
- `stco`/`stsz`/`stts` 등 엔트리 수가 수만~수십만 개에 달할 수 있는 샘플 테이블 박스는 트리 노드에 "N entries" 요약만 표시하고, 클릭 시 별도의 페이징 테이블 뷰로 전체 항목을 확인한다 (트리에 개별 엔트리를 자식 노드로 두지 않음 — UI 성능/트리 가독성 보호).

### UI Layer

파서가 만든 트리 모델만 구독하며, 파서 레이어와 독립적으로 동작한다.

- **탭 바** (상단): 열려있는 파일 간 전환. 드래그앤드롭 또는 열기 다이얼로그로 파일 추가.
- **좌측: 박스 트리** — 계층 구조, 손상/규격위반 박스는 경고 아이콘 표시, 대용량 샘플 테이블 박스는 요약 노드로 표시.
- **우측: 헥스덤프** — 트리에서 노드/필드 선택 시 해당 offset~length 구간 하이라이트 + 자동 스크롤.
- **하단: 필드 패널** — 선택한 박스의 의미 해석된 필드 목록(이름/값/타입). 요약 노드 클릭 시 필드 패널 대신 페이징 테이블 뷰로 전환.

## Error Handling

- Box Walker는 규격 위반(offset 초과, size 불일치 등)을 만나도 예외로 중단하지 않고 해당 노드에 경고를 표시한 뒤 계속 진행한다.
- 파일 자체를 열 수 없는 경우(파일 접근 실패, 완전히 인식 불가능한 포맷)에만 사용자에게 에러를 표시한다.

## Testing Strategy

- **파서 레이어(Box Walker + Semantic Decoders)**: UI와 독립적이므로 순수 유닛 테스트로 검증. 정상 샘플 파일(mp4/mov/3gp/heic)과 의도적으로 손상시킨 파일(잘린 파일, size 불일치)로 테스트 케이스를 구성한다.
- 실제 검증에는 보유 중인 샘플 파일(다양한 카메라/인코더 결과물)을 활용한다.
- UI 레이어는 기본 동작(트리 렌더링, 하이라이트 동기화) 위주로 가볍게 확인하고, 테스트 비중은 파서 정확성에 둔다.

## Open Questions / Future Phases

- JPEG 세그먼트 구조 + EXIF 태그 뷰 (2단계 스펙)
- 프레임 단위 비트스트림 분석 (Elecard StreamEye 스타일, 별도 대형 스펙)
- 프로젝트 패키지명(`com.example.multiviewer`)은 현재 Android 기본 템플릿 값 — 구현 계획 단계에서 정리 필요.
