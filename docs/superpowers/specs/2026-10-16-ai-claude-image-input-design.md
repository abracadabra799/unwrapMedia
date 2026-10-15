# AI 대화 — Claude 이미지 첨부 (설계)

날짜: 2026-10-16 · 상태: 승인됨 (사용자, 대화 중) · 하위 프로젝트 1/4 ("AI가 화면을 더 보게 하기")

## 배경

AI 대화(`ui/ai/`)는 메인 이미지 미리보기의 표시/마킹 영역을 잘라 PNG로 첨부할 수 있지만, 실제로 이미지를 넘길 수
있는 연동은 Codex(`exec -i <png>`)뿐이다. `ClaudeCliAdapter.argumentsWithImages()`는 null을 돌려주므로 Claude
사용자는 좌표만 받는다. 후속 하위 프로젝트(2: 비디오 현재 프레임)의 효과가 이 경로에 달려 있다.

전체 순서: 1) Claude 이미지 첨부(본 문서) → 2) 비디오 현재 프레임 → 3) 분석 창 결과 텍스트 → 4) 그래프/차트 데이터.

## 실측 (2026-10-16, macOS)

| CLI | 방법 | 결과 |
|---|---|---|
| Claude Code 2.1.300 | `-p --input-format stream-json --output-format stream-json --verbose --tools "" --strict-mcp-config --no-session-persistence`, stdin 한 줄 = `{"type":"user","message":{"role":"user","content":[{"type":"image","source":{"type":"base64","media_type":"image/png","data":…}},{"type":"text","text":…}]}}` | 96×96 단색(초록) → `"green"` 정답. 파일명 없음(픽셀만). |
| Claude Code | `--input-format stream-json --output-format text` | 거부: `Error: --input-format=stream-json requires output-format=stream-json.` |
| Gemini CLI 0.46 | 어떤 호출이든 | 실행 불가: `IneligibleTierError` (개인용 Code Assist 종료, Antigravity 이전 안내). 검증 불가. |
| Antigravity(agy) | `@<file>` 프롬프트 | 첨부되지 않음. 색과 무관한 파일명으로 재시험하자 셸 명령으로 파일을 읽으려다 plan 모드에서 거부됨. (첫 시험 "green.png"의 정답은 파일명 누설.) |

stream-json 출력: 이벤트 한 줄씩(`system`(hook/init), `assistant`, `rate_limit_event`, `result`). 정답 실행의 stdout
전체 ≈ 21 KB — 기존 상한 512 KB로 충분. `result` 이벤트 필드: `type:"result"`, `subtype:"success"`, `is_error:false`,
`result:"green"`, `api_error_status:null`, 그 외 실수(`total_cost_usd`)·객체 필드. 사용자 hook 이벤트가 섞여 나올 수 있다.

## 범위

- 포함: Claude 연동의 이미지 첨부(채팅 전송), 첫 이미지 전송 때 이미지 입력 자동 확인(5절).
- 제외: Gemini(검증 불가), agy(첨부 경로 없음) — 지금처럼 "이 연동은 이미지를 보낼 수 없음"으로 표시.
- 이미지 없는 실행은 모든 연동에서 지금과 바이트 단위로 같다.

## 설계

### 1. 어댑터: 실행 단위(`AiCliInvocation`)

`AiCliAdapter`의 `argumentsWithImages(images: List<File>): List<String>?`를 다음으로 대체한다.

```kotlin
/** One CLI run: argument list, the exact stdin bytes, and how to read stdout. */
class AiCliInvocation(
    val arguments: List<String>,
    val stdin: String,
    val parse: (stdout: String) -> AiParsedOutput,
    /** True when the images travel inside [stdin], so no temp PNG files are needed. */
    val imagesInline: Boolean = false,
)

sealed interface AiParsedOutput {
    data class Answer(val text: String) : AiParsedOutput
    /** The CLI reported a failure inside its own output (exit code may still be 0). */
    data class Error(val message: String) : AiParsedOutput
}

interface AiCliAdapter {
    …
    /** Whether this adapter can attach images at all (no run needed). */
    val canAttachImages: Boolean get() = false
    /**
     * The run for [prompt] with [images] (app-owned PNGs: files for path-based CLIs, bytes for inline ones),
     * or null when this adapter cannot pass images. Empty [images] = today's text run.
     */
    fun invocation(prompt: String, images: List<AiImageAttachment>): AiCliInvocation?
}

class AiImageAttachment(val file: File, val png: ByteArray)
```

- 기본 구현: 이미지가 비어 있으면 `AiCliInvocation(arguments(), prompt, { Answer(parseAnswer(it)) })`, 아니면 null.
- **Codex**: 이미지 있음 → 기존 `exec -i <path…>` 인자, stdin은 prompt, 텍스트 파싱(현재와 동일).
- **Claude**: 이미지 있음 → 인자 `-p --input-format stream-json --output-format stream-json --verbose --tools <…>
  --strict-mcp-config --no-session-persistence (+ webSearch 시 --allowedTools WebSearch)`. stdin = 아래 사용자
  메시지 한 줄 + `\n`. 이미지 블록들을 먼저, 텍스트 블록(prompt 전체)을 마지막에. `imagesInline = true`.
  파서 = `parseClaudeStreamJson`.
- 호출부(`AiChatController.send`의 `canPassImages`, `verifyImageInput`)는 `File("probe.png")` 탐침 대신
  `adapter.canAttachImages`를 쓴다.

### 2. stdin JSON 생성

`cli/JsonValue.kt`에 한 줄 렌더러 `JsonValue.renderCompact(): String`을 추가한다(기존 `render`는 들여쓰기·줄바꿈
때문에 NDJSON에 못 쓴다). 문자열 이스케이프는 기존 `jsonString`을 공유한다. base64는 `java.util.Base64`.

### 3. stream-json 출력 파서

`ui/ai/StreamJson.kt`:
- `parseJsonLoose(text: String): Any?` — 작은 재귀 하향 파서. 결과는 `Map<String, Any?>` / `List<Any?>` / `String` /
  `Double` / `Boolean` / `null`. 형식 오류면 예외 대신 null. 깊이 상한 64.
  (기존 `JsonValue`에는 null·실수가 없고, 그것을 넓히면 그 sealed class를 쓰는 기존 `when`들이 깨지므로 별도로 둔다.)
- `parseClaudeStreamJson(stdout: String): AiParsedOutput` — 줄 단위로 파싱해 `type == "result"`인 **마지막** 객체를 쓴다.
  - `is_error == true` 또는 `subtype != "success"` → `Error(result 문자열 ?: subtype ?: "error")`.
  - 성공 → `Answer(result)` (빈 문자열이면 러너가 EMPTY_OUTPUT 처리).
  - `result` 이벤트가 없으면(출력 잘림·비정상 종료) → `Error("no result event")`. 깨진 JSON을 답으로 보여주지 않는다.
  - 파싱 안 되는 줄(경고 등)은 건너뛴다.

### 4. 러너(`runAiCli`)

- 이미지를 PNG 바이트로 한 번 인코딩한다. `invocation.imagesInline`가 아니면 지금처럼 작업 폴더에 `view-N.png`로 쓴다
  (인코딩 전에 invocation을 얻어야 하므로: 바이트 인코딩 → 파일 경로 결정 → `invocation()` → 필요할 때만 파일 쓰기).
- stdin에는 `invocation.stdin`을 쓴다.
- 종료 코드 0이면 `invocation.parse(stdout)`:
  - `Answer` → 지금과 같음(빈 답은 EMPTY_OUTPUT).
  - `Error(msg)` → `AUTH_HINT`가 맞으면 AUTH_REQUIRED, 아니면 EXIT_ERROR로, 발췌는 msg.
- 0이 아니면 지금과 같다(stderr/stdout 발췌, AUTH_HINT).
- 타임아웃·상한·작업 폴더 삭제·프로세스 정리는 그대로.

### 5. 이미지 입력 자동 확인 (첫 이미지 전송 때)

- 계획 작성 중 발견: `22e66a7e`(2026-10-15, 패널 레이아웃 정리)에서 "이미지 입력 확인" 버튼이 삭제되어
  `AiChatController.verifyImageInput()`을 부르는 곳이 없다. 즉 현재 앱에서는 어떤 연동도 이미지 입력이 VERIFIED가 될 수
  없고, Codex도 실제로는 크롭을 첨부하지 못한다.
- 결정(사용자, 2026-10-16): 버튼을 복원하지 않고 **첫 이미지 전송 때 자동 확인**한다. `verifyImageInput()`은 제거하고
  컨트롤러 내부 `probeImageInput()`으로 바꾼다.
- `send`에서 이미지가 첨부될 상황(미리보기 있음, `canAttachImages`, 실행 파일 있음, 파일 변경 없음)이고 이미지 상태가
  UNKNOWN이면, 질문을 보내기 전에 확인 1회(무작위 단색 96×96 + 한 단어 색 이름 요구):
  - 정답 → VERIFIED → 이번 질문부터 크롭 첨부.
  - 답했지만 틀림 → UNSUPPORTED → 좌표만, 컨텍스트에 "확인용 이미지를 읽지 못함 (X)". 같은 실행 동안 재확인 안 함.
  - 실행 실패·타임아웃 → UNKNOWN 유지 → 좌표만("아직 확인되지 않음"), 다음 전송 때 다시 확인.
- 확인 중에는 `TabState.aiImageChecking` → 패널 3행에 "🖼️ 이미지 입력 확인 중…" / "🖼️ Checking image input…".
  버튼은 없다. 확인 상태는 앱 실행 동안 유지된다(기존 `aiCapabilities` 동작).
- Gemini/agy: `canAttachImages = false` → 확인 없음, 컨텍스트에 "이 앱의 X 연동은 이미지를 보낼 수 없음".

## 오류 처리

| 상황 | 결과 |
|---|---|
| Claude가 stream-json에서 오류 result(`is_error`) | Failed(EXIT_ERROR 또는 AUTH_REQUIRED), 메시지 발췌 |
| result 이벤트 없음 / 출력 상한에서 잘림 | Failed — 부분 JSON을 답으로 보이지 않음 |
| 종료 코드 ≠ 0 | 기존과 동일 |
| 타임아웃·취소 | 기존과 동일(프로세스 트리 종료) |

## 테스트

- `JsonValue.renderCompact`: 줄바꿈 없음, 이스케이프(따옴표·역슬래시·개행·제어문자), 중첩.
- `parseJsonLoose`: 객체/배열/문자열 이스케이프(`\uXXXX` 포함)/실수/null/불리언, 형식 오류 → null.
- `parseClaudeStreamJson`: 실측 출력 그대로의 성공 샘플(hook·init 이벤트 섞임), `is_error:true`, result 없음,
  마지막 줄이 잘린 출력, 여러 result 중 마지막 사용.
- `ClaudeCliAdapter.invocation`: 이미지 없음 → 기존 인자·stdin=prompt 그대로; 이미지 있음 → 인자 목록, stdin 한 줄,
  이미지 블록 순서·base64·텍스트 마지막, webSearch 인자.
- `CodexCliAdapter.invocation`: 기존 `-i` 인자 테스트 이전.
- `runAiCli`: 가짜 CLI 스크립트로 stream-json 성공/오류 result/결과 없음 → Answer/Failed, inline 모드에서 PNG 파일 미생성.
- `AiCliLiveAcceptanceTest`: 실제 `claude`가 있을 때만 — 단색 PNG를 `runAiCli`로 보내 색 이름 정답.
- 기존 `AiChatControllerTest`의 "Claude는 이미지 불가" 단언은 반대로 바꾼다.

## 남은 위험

- Windows: `claude.cmd` 래퍼를 거친 큰 stdin(크롭 최대 1024px PNG의 base64 ≈ 1 MB 내외). 텍스트도 이미 stdin으로 가므로
  경로는 같지만 실기 확인이 필요하다.
- Claude CLI의 stream-json 형식은 CLI 버전에 따라 바뀔 수 있다 — 파서는 `type/subtype/is_error/result`만 의존하고,
  형식이 바뀌어 result를 못 찾으면 실패로 보인다(거짓 답 없음).
