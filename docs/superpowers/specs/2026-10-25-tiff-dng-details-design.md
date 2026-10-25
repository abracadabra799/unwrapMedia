# TIFF / DNG Detailed Properties and Boundary Handling Design

## 1. 개요 및 목적 (Background & Objectives)
DETAIL_PARSING_REMAINING.md의 1번 과제(TIFF/DNG 추가 태그 의미 및 경계 처리)를 수행한다.
Adobe DNG 1.6 규격, TIFF 6.0, TIFF/EP, EXIF 2.32 규격에 근거하여 검증 가능한 태그들의 의미를 해석(PARSED)하고, 기존 TIFF 파서의 무제한 배열 읽기, 알 수 없는 자료형 크기 추정, UInt32 count 축소 시의 음수 변환, BigTIFF 미지원 처리 등 안전성/경계 결함을 보완한다.

## 2. 세부 설계 (Detailed Specification)

### 2.1 DNG 및 TIFF/EP/EXIF 태그 의미 해석
1. **DNGVersion (0xC612) / DNGBackwardVersion (0xC613)**
   - 규격: Adobe DNG 1.6 (p.19), Type = BYTE (1), Count = 4.
   - 해석: 4개 바이트를 4-tier 버전 번호 `"b0.b1.b2.b3"` (예: `"1.4.0.0"`, `"1.1.0.0"`)로 표기.
   - 상태: Count == 4이면 `PARSED`, 그 외 바이트 수나 잘림 시 `UNPARSABLE`.
2. **CFARepeatPatternDim (0x828D)**
   - 규격: TIFF/EP 및 DNG (p.22), Type = SHORT (3), Count = 2.
   - 해석: `[rows, cols]` 값을 `"${rows} x ${cols}"` (예: `"2 x 2"`)로 표기.
   - 상태: `PARSED`.
3. **CFAPlaneColor (0xC616)**
   - 규격: DNG 1.6 (p.22), Type = BYTE (1), Count = planeCount.
   - 색상 매핑: 0=Red, 1=Green, 2=Blue, 3=Cyan, 4=Magenta, 5=Yellow, 6=White.
   - 해석: 각 플레인의 색상 라벨 매핑 (예: `"0, 1, 2 (Red, Green, Blue)"`).
   - 상태: `PARSED`.
4. **CFAPattern (0x828E)**
   - 규격: TIFF/EP, Type = BYTE (1), Count = rows * cols.
   - 해석: 각 셀의 색상 라벨 매핑 (예: `"0, 1, 1, 2 (Red, Green, Green, Blue)"`). 16개 초과 시 생략 표기.
   - 상태: `PARSED`.
5. **CFALayout (0xC617)**
   - 규격: DNG 1.6 (p.23), Type = SHORT (3), Count = 1.
   - 라벨: 1="Rectangular", 2="Staggered layout A", 3="Staggered layout B", 4="Staggered layout C", 5="Staggered layout D", 6="Staggered layout E", 7="Staggered layout F", 8="Staggered layout G", 9="Staggered layout H".
   - 상태: `PARSED`.
6. **CalibrationIlluminant1 (0xC65A) / CalibrationIlluminant2 (0xC65B)**
   - 규격: DNG 1.6 (p.26), Type = SHORT (3), Count = 1.
   - 라벨: EXIF LightSource (0x9208) 표준 정의 (1="Daylight", 17="Standard light A", 21="D65", 등).
   - 상태: `PARSED`.
7. **ColorimetricReference (0xC6BF)**
   - 규격: DNG 1.6 (p.37), Type = SHORT (3), Count = 1.
   - 라벨: 0="sRGB", 1="CRIS Color Appearance Model".
   - 상태: `PARSED`.
8. **ProfileEmbedPolicy (0xC6FD)**
   - 규격: DNG 1.6 (p.38), Type = LONG (4), Count = 1.
   - 라벨: 0="allowCopying", 1="embedIfUsed", 2="embedNever", 3="noRestrictions".
   - 상태: `PARSED`.
9. **EXIF 1바이트 UNDEFINED Enum 태그 해석 활성화**
   - 대상: `FileSource (0xA300)` (3="Digital Camera"), `SceneType (0xA301)` (1="Directly photographed").
   - `interpretedDisplay`에서 `fieldType == 1` (BYTE) 및 `fieldType == 7` (UNDEFINED) 단일 바이트 값에 대해서도 `TAG_VALUE_LABELS` 조회를 적용.
10. **일반 Type 1 (BYTE) 배열 포맷팅**
    - `formatTiffValue`에서 Type 1을 부호 없는 정수(0..255) 배열로 표기.
    - 256개 초과 시 생략 표기 및 `UNINTERPRETED` 상태 부여.
11. **DNG OpcodeList 태그 등록**
    - 태그 등록: `0xC740 to "OpcodeList1"`, `0xC741 to "OpcodeList2"`, `0xC74E to "OpcodeList3"`.
    - 상태: Type 7 (UNDEFINED) 바이너리 페이로드는 안전한 64바이트 헥스 프리뷰와 함께 `UNINTERPRETED` 유지.

### 2.2 안전성 및 경계 처리 (Safety & Boundary Guards)
1. **자료형 크기 안전성 (Unknown Type Size Guard)**:
   - `fieldType !in TIFF_TYPE_SIZES` (1..12 범위 밖의 임의 번호)인 경우, 크기를 1바이트로 임의 추정하지 않음.
   - 오프셋/인라인 판별이 불가능하므로 `ValueInterpretation.UNINTERPRETED` 및 `"알 수 없는 자료형 / Unknown type ($fieldType)"`으로 표시.
2. **배열 크기 256개 상한 (Array Capping & OOM Protection)**:
   - `formatTiffValue`의 Type 1, 3, 4, 5, 8, 9, 10 배열은 최대 256개까지만 문자열로 변환.
   - 256개 초과 시 `" … (256/$count 표시 / shown; 나머지 미해석 / remainder uninterpreted)"` 접미사를 추가하고 `ValueInterpretation.UNINTERPRETED` 설정.
   - Type 2 (ASCII) 문자열은 최대 4,096바이트까지만 디코딩.
   - `readUIntArray`는 최대 1,024개 항목만 읽어 Strip/Tile 계산 시 메모리 낭비 차단.
3. **UInt32 count 축소 및 count == 0L 처리**:
   - `count == 0L`인 경우 `"빈 값 / Empty value"` (`ValueInterpretation.UNPARSABLE`) 처리.
   - `count > Int.MAX_VALUE`일 때 `count.toInt()`가 음수가 되어 발생하는 `NegativeArraySizeException` 또는 잘못된 할당 방지.
4. **SubIFD 재귀 상한**:
   - `TAG_SUB_IFDS` 순회 시 최대 64개 SubIFD로 제한하여 손상된 메타데이터로 인한 과도한 재귀 방지.
5. **BigTIFF (Magic 43 / 0x002B) 감지 및 명시적 거부**:
   - `decodeTiff`에서 TIFF 매직 넘버가 43인 경우, 32비트 포인터로 잘못 파싱하지 않고 `"BigTIFF format is currently unsupported"` 경고 노드를 생성하여 안전하게 종료.

## 3. 검증 전략 (Validation Strategy)
- 실제 Python Pillow 11.3.0 도구로 DNG/CFA 태그를 포함한 LE/BE TIFF fixture 생성 (`docs/validation/tiff-dng-fixtures/`).
- Python `struct` 기반 독립 바이트 덤프 스크립트로 오프셋/길이/값 검증 및 기록.
- 합성 바이트 기반 경계 테스트 (BigTIFF, count=0, count>256, unknown fieldType, SubIFD limit).
