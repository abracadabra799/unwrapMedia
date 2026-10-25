# Detailed Properties 잔여 작업 인수인계 — 2026-10-25

## 현재 요청과 시작 지점

사용자가 잔여 작업을 다른 에이전트에게 넘기기로 했다. 이 문서 작성 후 구현은 중단한다. 다음 에이전트는 이 문서 → `HANDOFF.md` 상단 → `plans/2026-10-23-detail-parsing.md` Task18–23 순서로 읽는다. HANDOFF 아래의 오래된 로드맵을 현재 우선 작업으로 오해하지 말 것.

목표: Detailed Properties에서 근거를 확인할 수 있는 값을 파싱해 표시하고, 해석하지 못한 값은 **미해석**, 잘못되거나 잘린 값은 **파싱 불가**로 표시한다. 모든 포맷을 완전히 지원했다는 의미가 아니다. 기존 사용자 우선순위는 MP4, HEIC/HEIF, JPG(+SEF), HEVC/AVC, AVIF, MOV, APV, AAC/FLAC/MP3, AV1/AV2, PNG/TIFF/GIF/DNG다. 상당 부분을 순차 보완했고, 아래는 남은 조사·구현 후보다.

## Git 및 검증 상태

- 문서 작성 전 로컬 main: `bcd890a6` (작업 트리 깨끗함).
- 로컬 origin/main 추적값: `ee9c9210`. 이번 인수인계에서 fetch나 push는 하지 않았다.
- 아직 push하지 않은 구현: `161e0ca7` PNG text/ICC, `bcd890a6` TIFF numeric. 이 인수인계 문서 커밋도 로컬에만 남긴다.
- **새 사용자 요청 없이 push하지 말 것.** 이전 push 요청을 이후 변경에 재사용하지 않는다.
- 마지막 전체 검증: `bcd890a6`의 `./gradlew :app:test`, 2,870 tests / 0 failures / 0 errors / 1 skipped. 독립 리뷰 결함 없음.
- 소유했던 `tiff-numeric-details` worktree와 feature branch는 정리 완료. 다른 worktree는 건드리지 않았다. 앱은 이번 작업에서 실행하지 않았다.

## 다음 작업 목록

아래 항목은 모두 미완료다. 조사 후보를 확정된 버그로 간주하거나, 전체를 한 번에 구현하지 않는다. 실제 코드와 fixture를 확인해 한 단위씩 진행한다.

1. **GIF Detailed Properties 조사** — 권장 다음 단위.
   - `GifWalker.kt`, `GifFrameDecoder.kt`, `integrity/GifIntegrity.kt`와 기존 테스트부터 읽는다.
   - extension/GCE/application/comment/plain text, 색상표, data sub-block 등 현재 값 표시·누락·미해석 상태를 대조한다. 누락이 있다고 단정하지 않는다.
   - 기본 설정 GIF와 실제 애니메이션/extension fixture를 생성해 원본 바이트를 검증한다. 픽셀 디코더 재작성이나 전체 conformance로 범위를 넓히지 않는다.
2. **AV2 추가 상세 값 조사**.
   - `Av2CBoxDecoder.kt`, `Av2SequenceHeader.kt`, `Av2Obu.kt`, `Av2SampleIndexer.kt`, `Av2ParameterSetExtraction.kt` 및 `plans/2026-10-02-av2-isobmff-analysis.md`.
   - 이미 지원하는 config/sequence-header 값과 미해석 payload를 먼저 목록화한다. 규격·encoder 버전을 fixture와 함께 명시한다. AV1 규칙을 AV2에 추정 적용하지 않는다.
3. **SEF 남은 값 및 크기 제한**.
   - `SefdBoxDecoder.kt`, `SefIntegrityAnalyzer.kt`, `plans/2026-10-24-sef-details.md`와 관련 fixture부터 확인한다.
   - 정의가 확인된 payload만 의미를 추가한다. 제조사 전용 binary는 근거가 없으면 원본 범위와 미해석 표시를 유지한다.
   - 큰 이름/payload, UInt32 길이, allocation/preview 한도를 조사한다. 최근 다른 작업의 JPEG conformance/SEF 검사 수정과 충돌하거나 중복 구현하지 않는다.
4. **후순위: ICC 내부 태그와 legacy metadata**.
   - PNG iCCP는 안전한 압축 해제, ICC header/tag-count 길이 검증, 실제 압축 바이트 source span까지 완료. ICC 태그 내용은 미해석으로 남아 있다.
   - 지원하지 않는 ID3v2.2, v2.3 tag-wide unsync, 압축/암호화 프레임, 일부 MOV nested/binary/legacy encoding 등은 미해석 상태를 유지한다. 추가 구현은 실제 근거와 우선순위 확인 후 진행한다.

## 최근 완료 작업 — 중복 방지

- Task18 MP3/ID3, Task19 MOV, Task20 native FLAC CUESHEET/PICTURE/Vorbis.
- Task21 PNG APNG/HDR/color-context 및 palette, Task22 PNG text/ICC.
- Task23 TIFF SBYTE/FLOAT/DOUBLE.
- Task24 TIFF/DNG 추가 태그 의미 및 경계 처리: DNGVersion/BackwardVersion 4-tier 버전 표기, CFARepeatPatternDim/CFAPattern/CFAPlaneColor 색상 매핑, CFALayout, CalibrationIlluminant1/2, ColorimetricReference, ProfileEmbedPolicy, EXIF 1바이트 FileSource/SceneType 해석, OpcodeList 식별, 256개 배열 상한 및 생략 표시, count==0L 빈 값 처리, 알 수 없는 자료형 안전 처리, BigTIFF 매직(43) 미지원 경고, SubIFD 64개 제한.
- Task21 PNG APNG/HDR/color-context 및 palette, Task22 PNG text/ICC.
- Task23 TIFF SBYTE/FLOAT/DOUBLE: 양쪽 endian, inline/reference, signed zero/nonfinite 표현, 256개 초과 시 생략+미해석, 미등록 태그 의미 미해석 유지, 원본 offset/length 보존.
- 구체적인 테스트 결과·한계는 각 `plans/2026-10-24-*.md`, `plans/2026-10-25-*.md` 및 공통 진행 기록에 있다.

## 실행 규칙과 재현 자료

- 한국어로 소통. 승인된 순차 보완 작업은 불필요한 재승인 없이 진행하되, 새 push는 별도 요청 필요.
- 실제 도구 생성 fixture → 독립 바이트 dump → spec/plan → feature worktree → 실패 테스트 → 구현 → focused/full tests → 독립 리뷰 → 로컬 main 통합.
- 검증 자료는 `docs/validation/*-fixtures`, 파일은 `app/src/test/resources/`에 보관. default-settings fixture를 유지한다.
- TIFF 최신 자료: `docs/validation/tiff-numeric-fixtures/`, `app/src/test/resources/tiff-numeric/`. Pillow 생성 TIFF metadata-layout 샘플이며 카메라가 만든 DNG라고 주장하지 않는다.
- 생성용 임시 Python 환경 `/tmp/unwrap-flac-fixture-env/bin/python`에 Pillow11.3.0/Mutagen1.47.0이 있었음. 임시 환경은 사라질 수 있으니 README 기준 재구성. 앱 의존성이 아니다.
- JDK21; `./gradlew :app:test`. 동일 worktree에서 Gradle 테스트를 중복 실행하지 않는다. 기존 ThumbnailDiskCacheTest는 간헐적 flaky 이력이 있으나 다른 실패를 무시하지 않는다.
- `BoxField.interpretation` / `BoxNode.interpretation`: PARSED, UNINTERPRETED, UNPARSABLE. warning만으로 모든 노드에 상태가 자동 적용되는 것은 아니므로 실제 UI/CLI 경로 확인.
- 파싱된 값의 source span은 실제 파일 바이트를 가리켜야 한다. 압축 해제된 가상 offset을 파일 offset처럼 표시하지 않는다. KO/EN 문자열, Material3, 새 무거운 의존성 금지 규칙 유지.
