# AI 대화 — Claude 이미지 첨부 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Claude Code 연동이 AI 대화에 화면 크롭(PNG)을 실제로 첨부하게 하고, 첫 이미지 전송 때 이미지 입력을 자동 확인해 Claude·Codex 모두 이미지를 실제로 쓰게 한다.

**Architecture:** `AiCliAdapter`가 인자 목록만이 아니라 실행 단위(`AiCliInvocation`: 인자 + stdin + 출력 파서)를 돌려준다. Claude는 이미지가 있을 때 `--input-format stream-json --output-format stream-json --verbose`로 실행하고, stdin 한 줄 JSON에 base64 PNG 블록과 질문을 담고, 출력의 마지막 `result` 이벤트를 답으로 읽는다. 이미지 없는 실행은 모든 연동에서 지금과 같다.

**Tech Stack:** Kotlin, Compose Desktop (Material3), JUnit5 + kotlin.test, 손으로 쓴 JSON(`cli/JsonValue.kt`, 새 `ui/ai/StreamJson.kt`).

**Spec:** `docs/superpowers/specs/2026-10-16-ai-claude-image-input-design.md`

## Global Constraints

- 새 의존성 금지 — JSON은 손으로 쓴다 (AGENTS.md 규칙 10).
- Material3만: `androidx.compose.material3.Text` (규칙 3).
- 사용자 문구는 KO/EN 이중 언어 — 패널의 로컬 `label(korean, english)` (규칙 11).
- 외부 프로세스: 기존 `runAiCli`의 타임아웃·상한·프로세스 트리 종료·임시 폴더 삭제를 그대로 유지 (규칙 5).
- 이미지 없는 실행(모든 연동)의 인자·stdin·파싱은 바이트 단위로 지금과 같아야 한다.
- Gemini는 이미지 불가(`canAttachImages = false`) 그대로. agy는 Task 4에서 작업 폴더 PNG + `view_file`로 지원.
- 작업 위치: worktree `.worktrees/ai-claude-image`, 브랜치 `feat/ai-claude-image-input`. 각 커밋 전에 `git -C <worktree> branch --show-current`가 `feat/ai-claude-image-input`인지 확인한다 (서브에이전트가 main에 커밋한 전례 있음).
- 커밋 메시지 끝: `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`
- 테스트 명령: `./gradlew :app:test --tests '<FQCN>'` (worktree 루트에서). `ThumbnailDiskCacheTest`는 알려진 flaky.

## File Structure

| 파일 | 역할 |
|---|---|
| `app/src/main/kotlin/com/multiviewer/cli/JsonValue.kt` (수정) | 한 줄 렌더러 `renderCompact()` 추가 |
| `app/src/main/kotlin/com/multiviewer/ui/ai/StreamJson.kt` (신규) | `parseJsonLoose`, `AiParsedOutput`, `parseClaudeStreamJson` |
| `app/src/main/kotlin/com/multiviewer/ui/ai/AiCliAdapter.kt` (수정) | `AiCliInvocation`, `AiImageAttachment`, `invocation()`, `canAttachImages`, `claudeImageMessage`; Claude/Codex 구현 |
| `app/src/main/kotlin/com/multiviewer/ui/ai/AiCliRunner.kt` (수정) | invocation 사용(stdin·파서·PNG 파일 조건부 작성) |
| `app/src/main/kotlin/com/multiviewer/ui/ai/AiChatController.kt` (수정) | 탐침 대신 `canAttachImages` |
| `app/src/main/kotlin/com/multiviewer/ui/ai/AiChatPanel.kt` (수정) | 자동 확인 중 표시 |
| `app/src/main/kotlin/com/multiviewer/ui/AppState.kt` (수정) | `TabState.aiImageChecking` |
| `app/src/test/kotlin/com/multiviewer/cli/JsonValueTest.kt` (수정) | compact 렌더 테스트 |
| `app/src/test/kotlin/com/multiviewer/ui/ai/StreamJsonTest.kt` (신규) | 파서 테스트 |
| `app/src/test/kotlin/com/multiviewer/ui/ai/AiCliAdapterTest.kt` (신규) | invocation 테스트 |
| `app/src/test/kotlin/com/multiviewer/ui/ai/AiCliRunnerTest.kt` (수정) | 가짜 CLI stream-json 테스트, agy 단언 갱신 |
| `app/src/test/kotlin/com/multiviewer/ui/ai/AiChatControllerTest.kt` (수정) | Claude 이미지 가능으로 단언 갱신, Codex 인자 테스트 이전 |
| `app/src/test/kotlin/com/multiviewer/ui/ai/AiCliLiveAcceptanceTest.kt` (수정) | 실제 CLI 이미지 왕복(옵트인) |

---

### Task 0: Worktree

- [ ] **Step 1: 생성**

```bash
cd /Users/dong.kim/AndroidStudioProjects/multiViewer
git worktree add .worktrees/ai-claude-image -b feat/ai-claude-image-input main
git -C .worktrees/ai-claude-image branch --show-current
```
Expected: `feat/ai-claude-image-input`

- [ ] **Step 2: 기준선 테스트**

Run (worktree 루트): `./gradlew :app:test --tests 'com.multiviewer.ui.ai.*' --tests 'com.multiviewer.cli.JsonValueTest'`
Expected: BUILD SUCCESSFUL

---

### Task 1: JSON 한 줄 렌더러 + stream-json 파서

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/cli/JsonValue.kt`
- Create: `app/src/main/kotlin/com/multiviewer/ui/ai/StreamJson.kt`
- Test: `app/src/test/kotlin/com/multiviewer/cli/JsonValueTest.kt`, `app/src/test/kotlin/com/multiviewer/ui/ai/StreamJsonTest.kt`

**Interfaces:**
- Produces:
  - `fun JsonValue.renderCompact(): String` (package `com.multiviewer.cli`)
  - `fun parseJsonLoose(text: String): Any?` — `Map<String, Any?>` / `List<Any?>` / `String` / `Double` / `Boolean` / `null`; 형식 오류면 `null`
  - `sealed interface AiParsedOutput { data class Answer(val text: String); data class Error(val message: String) }`
  - `fun parseClaudeStreamJson(stdout: String): AiParsedOutput` (모두 package `com.multiviewer.ui.ai`)

- [ ] **Step 1: 실패하는 테스트 작성 — JsonValueTest에 추가**

`JsonValueTest` 클래스 안에 추가:

```kotlin
    @Test
    fun `compact rendering is one line and escapes control characters`() {
        val value = JsonValue.JObject(
            listOf(
                "t" to JsonValue.JString("a\"b\\c\nd\u0001"),
                "n" to JsonValue.JArray(listOf(JsonValue.JNumber(1), JsonValue.JBoolean(true), JsonValue.JObject(emptyList()))),
            ),
        )
        assertEquals("""{"t":"a\"b\\c\nd\u0001","n":[1,true,{}]}""", value.renderCompact())
    }
```

- [ ] **Step 2: 실패하는 테스트 작성 — StreamJsonTest.kt 신규**

```kotlin
package com.multiviewer.ui.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class StreamJsonTest {
    @Test
    fun `parses objects arrays strings numbers booleans and null`() {
        val v = parseJsonLoose("""{"a":[1,2.5,-3e2],"s":"x\"\\\/\né","t":true,"f":false,"z":null,"o":{}}""") as Map<*, *>
        assertEquals(listOf(1.0, 2.5, -300.0), v["a"])
        assertEquals("x\"\\/\né", v["s"])
        assertEquals(true, v["t"])
        assertEquals(false, v["f"])
        assertTrue(v.containsKey("z"))
        assertEquals(null, v["z"])
        assertEquals(emptyMap<String, Any?>(), v["o"])
    }

    @Test
    fun `malformed input is null, not an exception`() {
        val bad = listOf("", "{", "{\"a\":}", "{\"a\":1,}", "[\"x\"", "tru", "{\"a\":1} trailing", "\"bad \\q escape\"", "\"raw\ncontrol\"")
        for (text in bad) assertEquals(null, parseJsonLoose(text), text)
    }

    @Test
    fun `nesting deeper than the limit is refused`() {
        assertEquals(null, parseJsonLoose("[".repeat(100) + "]".repeat(100)))
        assertTrue(parseJsonLoose("[".repeat(10) + "]".repeat(10)) is List<*>)
    }

    // Shape of a real `claude -p --output-format stream-json --verbose` run (2.1.300), trimmed.
    private val successRun = listOf(
        """{"type":"system","subtype":"hook_started","hook_name":"SessionStart"}""",
        """{"type":"system","subtype":"init","tools":[],"model":"x"}""",
        """{"type":"assistant","message":{"content":[{"type":"text","text":"green"}]}}""",
        """{"type":"rate_limit_event"}""",
        """{"type":"result","subtype":"success","is_error":false,"api_error_status":null,"total_cost_usd":0.0433178,"result":"green","num_turns":1}""",
    ).joinToString("\n")

    @Test
    fun `the answer is the result event of a successful run`() {
        assertEquals(AiParsedOutput.Answer("green"), parseClaudeStreamJson(successRun + "\n"))
    }

    @Test
    fun `an error result is an error even with exit code zero`() {
        assertEquals(
            AiParsedOutput.Error("Invalid API key · Please run /login"),
            parseClaudeStreamJson("""{"type":"result","subtype":"success","is_error":true,"result":"Invalid API key · Please run /login"}"""),
        )
        assertEquals(
            AiParsedOutput.Error("error_max_turns"),
            parseClaudeStreamJson("""{"type":"result","subtype":"error_max_turns","is_error":false}"""),
        )
    }

    @Test
    fun `no result event is an error, never the raw stream`() {
        val cut = successRun.substringBefore("""{"type":"result"""") + """{"type":"result","subtype":"succ"""
        assertIs<AiParsedOutput.Error>(parseClaudeStreamJson(cut))
        assertIs<AiParsedOutput.Error>(parseClaudeStreamJson("plain text warning\n"))
    }

    @Test
    fun `the last result event wins and stray lines are skipped`() {
        val out = listOf(
            """{"type":"result","subtype":"success","is_error":false,"result":"first"}""",
            "warning: something",
            """{"type":"result","subtype":"success","is_error":false,"result":"second"}""",
        ).joinToString("\n")
        assertEquals(AiParsedOutput.Answer("second"), parseClaudeStreamJson(out))
    }
}
```

- [ ] **Step 3: 실패 확인**

Run: `./gradlew :app:test --tests 'com.multiviewer.cli.JsonValueTest' --tests 'com.multiviewer.ui.ai.StreamJsonTest'`
Expected: 컴파일 실패 — `Unresolved reference: renderCompact`, `parseJsonLoose`, `parseClaudeStreamJson`, `AiParsedOutput`

- [ ] **Step 4: `renderCompact` 구현**

`JsonValue.kt`에서 `fun JsonValue.render(...)` 함수 바로 아래(그리고 `private fun jsonString` 위)에 추가:

```kotlin
/** One line, no whitespace: for newline-delimited JSON (one message per line). */
fun JsonValue.renderCompact(): String = when (this) {
    is JsonValue.JObject -> entries.joinToString(",", "{", "}") { (key, value) -> jsonString(key) + ":" + value.renderCompact() }
    is JsonValue.JArray -> items.joinToString(",", "[", "]") { it.renderCompact() }
    is JsonValue.JString -> jsonString(value)
    is JsonValue.JNumber -> value.toString()
    is JsonValue.JBoolean -> value.toString()
}
```

- [ ] **Step 5: `StreamJson.kt` 구현**

```kotlin
package com.multiviewer.ui.ai

private const val MAX_JSON_DEPTH = 64

/** What one CLI run said, read from its stdout. [Error] = the CLI reported a failure in its own output. */
sealed interface AiParsedOutput {
    data class Answer(val text: String) : AiParsedOutput
    data class Error(val message: String) : AiParsedOutput
}

/**
 * Minimal JSON reader for CLI output (no JSON dependency in this project; `JsonValue` has no null or
 * fractional numbers). Returns Map<String, Any?> / List<Any?> / String / Double / Boolean / null, and null
 * for any syntax error, so a top-level JSON `null` and bad input look the same -- callers expect objects.
 */
fun parseJsonLoose(text: String): Any? = try {
    val reader = JsonReader(text)
    val value = reader.value(0)
    reader.skipWs()
    if (reader.pos != text.length) null else value
} catch (_: JsonSyntax) {
    null
}

/**
 * The answer of a `claude -p --output-format stream-json` run: the last `type:"result"` event. Hook, init and
 * assistant events are skipped. No result event (cut-off or crashed run) is an error, so a partial stream is
 * never shown as an answer.
 */
fun parseClaudeStreamJson(stdout: String): AiParsedOutput {
    val result = stdout.lineSequence()
        .map { it.trim() }
        .filter { it.startsWith("{") }
        .mapNotNull { parseJsonLoose(it) as? Map<*, *> }
        .lastOrNull { it["type"] == "result" }
        ?: return AiParsedOutput.Error("Claude CLI ended without a result event")
    val text = result["result"] as? String
    val failed = result["is_error"] == true || result["subtype"] != "success"
    return if (failed) {
        AiParsedOutput.Error(text?.takeIf { it.isNotBlank() } ?: (result["subtype"] as? String) ?: "error")
    } else {
        AiParsedOutput.Answer(text?.trim().orEmpty())
    }
}

private class JsonSyntax : Exception()

private class JsonReader(private val s: String) {
    var pos = 0

    fun skipWs() {
        while (pos < s.length && s[pos] in " \t\r\n") pos++
    }

    private fun fail(): Nothing = throw JsonSyntax()

    fun value(depth: Int): Any? {
        if (depth > MAX_JSON_DEPTH) fail()
        skipWs()
        if (pos >= s.length) fail()
        return when (s[pos]) {
            '{' -> obj(depth)
            '[' -> arr(depth)
            '"' -> str()
            't' -> literal("true", true)
            'f' -> literal("false", false)
            'n' -> literal("null", null)
            else -> num()
        }
    }

    private fun literal(word: String, v: Any?): Any? {
        if (!s.startsWith(word, pos)) fail()
        pos += word.length
        return v
    }

    private fun obj(depth: Int): Map<String, Any?> {
        pos++ // {
        val out = LinkedHashMap<String, Any?>()
        skipWs()
        if (s.getOrNull(pos) == '}') { pos++; return out }
        while (true) {
            skipWs()
            if (s.getOrNull(pos) != '"') fail()
            val key = str()
            skipWs()
            if (s.getOrNull(pos) != ':') fail()
            pos++
            out[key] = value(depth + 1)
            skipWs()
            when (s.getOrNull(pos)) {
                ',' -> pos++
                '}' -> { pos++; return out }
                else -> fail()
            }
        }
    }

    private fun arr(depth: Int): List<Any?> {
        pos++ // [
        val out = ArrayList<Any?>()
        skipWs()
        if (s.getOrNull(pos) == ']') { pos++; return out }
        while (true) {
            out += value(depth + 1)
            skipWs()
            when (s.getOrNull(pos)) {
                ',' -> pos++
                ']' -> { pos++; return out }
                else -> fail()
            }
        }
    }

    private fun str(): String {
        pos++ // opening quote
        val sb = StringBuilder()
        while (true) {
            if (pos >= s.length) fail()
            val c = s[pos++]
            when {
                c == '"' -> return sb.toString()
                c == '\\' -> {
                    if (pos >= s.length) fail()
                    when (val e = s[pos++]) {
                        '"', '\\', '/' -> sb.append(e)
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            if (pos + 4 > s.length) fail()
                            val hex = s.substring(pos, pos + 4)
                            if (!hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) fail()
                            sb.append(Char(hex.toInt(16)))
                            pos += 4
                        }
                        else -> fail()
                    }
                }
                c < ' ' -> fail()
                else -> sb.append(c)
            }
        }
    }

    private fun num(): Double {
        val start = pos
        while (pos < s.length && (s[pos].isDigit() || s[pos] in "+-.eE")) pos++
        if (pos == start) fail()
        return s.substring(start, pos).toDoubleOrNull() ?: fail()
    }
}
```

- [ ] **Step 6: 통과 확인**

Run: `./gradlew :app:test --tests 'com.multiviewer.cli.JsonValueTest' --tests 'com.multiviewer.ui.ai.StreamJsonTest'`
Expected: PASS (전체)

- [ ] **Step 7: 커밋**

```bash
git -C .worktrees/ai-claude-image branch --show-current   # feat/ai-claude-image-input 이어야 함
cd .worktrees/ai-claude-image
git add app/src/main/kotlin/com/multiviewer/cli/JsonValue.kt app/src/main/kotlin/com/multiviewer/ui/ai/StreamJson.kt \
  app/src/test/kotlin/com/multiviewer/cli/JsonValueTest.kt app/src/test/kotlin/com/multiviewer/ui/ai/StreamJsonTest.kt
git commit -m "feat: add compact JSON rendering and Claude stream-json parser

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: 어댑터 실행 단위 + Claude 이미지 + 러너/컨트롤러 연결

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/ui/ai/AiCliAdapter.kt:1-51`
- Modify: `app/src/main/kotlin/com/multiviewer/ui/ai/AiCliRunner.kt:80-140`
- Modify: `app/src/main/kotlin/com/multiviewer/ui/ai/AiChatController.kt:120,282`
- Create: `app/src/test/kotlin/com/multiviewer/ui/ai/AiCliAdapterTest.kt`
- Modify: `app/src/test/kotlin/com/multiviewer/ui/ai/AiCliRunnerTest.kt`, `app/src/test/kotlin/com/multiviewer/ui/ai/AiChatControllerTest.kt`

**Interfaces:**
- Consumes (Task 1): `JsonValue.renderCompact()`, `AiParsedOutput`, `parseClaudeStreamJson`, `parseJsonLoose`(테스트)
- Produces:
  - `class AiImageAttachment(val file: File, val png: ByteArray)`
  - `class AiCliInvocation(val arguments: List<String>, val stdin: String, val parse: (String) -> AiParsedOutput, val imagesInline: Boolean = false)`
  - `AiCliAdapter.canAttachImages: Boolean` (기본 false; Claude·Codex true)
  - `AiCliAdapter.invocation(prompt: String, images: List<AiImageAttachment> = emptyList()): AiCliInvocation?`
  - `fun AiCliAdapter.textInvocation(prompt: String): AiCliInvocation`
  - `internal fun claudeImageMessage(prompt: String, pngs: List<ByteArray>): String`
  - 제거: `AiCliAdapter.argumentsWithImages(...)`

- [ ] **Step 1: 실패하는 테스트 — AiCliAdapterTest.kt 신규**

```kotlin
package com.multiviewer.ui.ai

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AiCliAdapterTest {
    @Test
    fun `text runs are unchanged for every provider`() {
        for (p in AiProviderId.entries) {
            val adapter = adapterFor(p)
            val inv = adapter.invocation("hello")!!
            assertEquals(adapter.arguments(), inv.arguments, p.name)
            assertEquals("hello", inv.stdin, p.name)
            assertFalse(inv.imagesInline, p.name)
            assertEquals(AiParsedOutput.Answer("hi"), inv.parse("  hi \n"), p.name)
        }
    }

    @Test
    fun `claude image runs use stream-json with the image blocks before the question`() {
        val inv = ClaudeCliAdapter().invocation(
            "what \"is\" this?\nline2",
            listOf(AiImageAttachment(File("/unused.png"), byteArrayOf(1, 2, 3))),
        )!!
        assertEquals(
            listOf("-p", "--input-format", "stream-json", "--output-format", "stream-json", "--verbose",
                "--tools", "", "--strict-mcp-config", "--no-session-persistence"),
            inv.arguments,
        )
        assertTrue(inv.imagesInline)
        assertTrue(inv.stdin.endsWith("\n"))
        assertEquals(1, inv.stdin.trimEnd('\n').lines().size)
        assertFalse(inv.stdin.contains("/unused.png"))
        val msg = parseJsonLoose(inv.stdin.trim()) as Map<*, *>
        assertEquals("user", msg["type"])
        val message = msg["message"] as Map<*, *>
        assertEquals("user", message["role"])
        val content = message["content"] as List<*>
        assertEquals(2, content.size)
        val image = content[0] as Map<*, *>
        assertEquals("image", image["type"])
        assertEquals(mapOf("type" to "base64", "media_type" to "image/png", "data" to "AQID"), image["source"])
        assertEquals(mapOf("type" to "text", "text" to "what \"is\" this?\nline2"), content[1])
        assertEquals(AiParsedOutput.Answer("red"), inv.parse("""{"type":"result","subtype":"success","is_error":false,"result":"red"}"""))
    }

    @Test
    fun `claude web search keeps its only tool in image runs`() {
        val args = ClaudeCliAdapter(webSearch = true)
            .invocation("q", listOf(AiImageAttachment(File("x.png"), ByteArray(1))))!!.arguments
        val tools = args.indexOf("--tools")
        assertEquals("WebSearch", args[tools + 1])
        assertEquals(listOf("--allowedTools", "WebSearch"), args.takeLast(2))
    }

    @Test
    fun `codex image flags end before the stdin marker and the prompt stays plain`() {
        val inv = CodexCliAdapter().invocation("q", listOf(AiImageAttachment(File("/t/view-1.png"), ByteArray(0))))!!
        assertEquals(listOf("exec", "-i", "/t/view-1.png", "--sandbox"), inv.arguments.take(4))
        assertEquals("-", inv.arguments.last())
        assertEquals("q", inv.stdin)
        assertFalse(inv.imagesInline)
    }

    @Test
    fun `only claude and codex can attach images`() {
        assertEquals(
            setOf(AiProviderId.CLAUDE, AiProviderId.CODEX),
            AiProviderId.entries.filter { adapterFor(it).canAttachImages }.toSet(),
        )
        assertEquals(null, adapterFor(AiProviderId.GEMINI).invocation("q", listOf(AiImageAttachment(File("x.png"), ByteArray(1)))))
    }
}
```

- [ ] **Step 2: 실패하는 테스트 — AiCliRunnerTest에 러너 테스트 추가**

`AiCliRunnerTest` 클래스 안(마지막 테스트 뒤)에 추가:

```kotlin
    private fun square() = java.awt.image.BufferedImage(8, 8, java.awt.image.BufferedImage.TYPE_INT_RGB)

    @Test
    fun `claude image runs send one stream-json line with the image inline and read the result event`() {
        val stdin = File(dir, "stdin.txt")
        val listing = File(dir, "ls.txt")
        val exe = script(
            "claude",
            """cat > "${stdin.absolutePath}"; ls -A > "${listing.absolutePath}"
echo '{"type":"system","subtype":"init"}'
echo '{"type":"result","subtype":"success","is_error":false,"result":"red"}'""",
        )
        val result = runBlocking { runAiCli(ClaudeCliAdapter(), exe, "q", images = listOf(square())) }
        assertEquals(AiCliResult.Answer("red", false), result)
        val sent = stdin.readText()
        assertEquals(1, sent.trimEnd('\n').lines().size)
        assertTrue(sent.contains("\"media_type\":\"image/png\""), sent.take(200))
        assertEquals("", listing.readText().trim()) // inline CLI: no PNG written to the work dir
    }

    @Test
    fun `an error result from claude is a failure and a login error is reported as such`() {
        val exe = script("claude", """cat > /dev/null; echo '{"type":"result","subtype":"success","is_error":true,"result":"Invalid API key · Please run /login"}'""")
        val result = runBlocking { runAiCli(ClaudeCliAdapter(), exe, "q", images = listOf(square())) }
        assertIs<AiCliResult.Failed>(result)
        assertEquals(AiFailure.AUTH_REQUIRED, result.reason)
    }

    @Test
    fun `a claude image run without a result event is a failure, not an answer`() {
        val exe = script("claude", """cat > /dev/null; echo '{"type":"system","subtype":"init"}'""")
        val result = runBlocking { runAiCli(ClaudeCliAdapter(), exe, "q", images = listOf(square())) }
        assertIs<AiCliResult.Failed>(result)
        assertEquals(AiFailure.EXIT_ERROR, result.reason)
    }

    @Test
    fun `codex image runs still get png files in the work dir`() {
        val exe = script("codex", """cat > /dev/null; echo "ARGS:$*"; ls""")
        val result = runBlocking { runAiCli(CodexCliAdapter(), exe, "q", images = listOf(square())) }
        assertIs<AiCliResult.Answer>(result)
        assertTrue(result.text.contains("ARGS:exec -i "), result.text)
        assertTrue(result.text.contains("view-1.png"), result.text)
    }
```

같은 파일의 기존 테스트 `antigravity adapter uses plan mode and text output format`의 마지막 줄

```kotlin
        assertEquals(null, adapter.argumentsWithImages(listOf(File("crop.png"))))
```
를 다음으로 교체:
```kotlin
        assertFalse(adapter.canAttachImages)
```

- [ ] **Step 3: 기존 컨트롤러 테스트 갱신 — AiChatControllerTest**

`visual marks are taken at send time and a crop goes only to a verified image provider`에서

```kotlin
        assertEquals(0, images) // Claude adapter cannot pass images
```
를
```kotlin
        assertEquals(1, images) // Claude passes images inline (stream-json)
```
로 바꾼다.

테스트 `codex image flags end before the stdin marker` 전체(그 `@Test` 포함, 아래 블록)를 삭제한다 — AiCliAdapterTest로 이전됨:

```kotlin
    @Test
    fun `codex image flags end before the stdin marker`() {
        val args = CodexCliAdapter().argumentsWithImages(listOf(File("/t/view-1.png")))
        assertEquals(listOf("exec", "-i", "/t/view-1.png", "--sandbox"), args.take(4))
        assertEquals("-", args.last())
        assertEquals(null, ClaudeCliAdapter().argumentsWithImages(listOf(File("x.png"))))
    }
```

- [ ] **Step 4: 실패 확인**

Run: `./gradlew :app:test --tests 'com.multiviewer.ui.ai.*'`
Expected: 컴파일 실패 — `Unresolved reference: invocation`, `AiImageAttachment`, `canAttachImages`

- [ ] **Step 5: AiCliAdapter.kt 구현**

파일 맨 위 import에 추가:
```kotlin
import com.multiviewer.cli.JsonValue
import com.multiviewer.cli.renderCompact
```

`interface AiCliAdapter { … }` 블록 전체를 다음으로 교체:

```kotlin
interface AiCliAdapter {
    val provider: AiProviderId
    fun arguments(): List<String>
    /** Optional per-run configuration inside the app-owned temporary working directory. */
    fun prepareWorkDirectory(directory: File) {}

    /** Whether this adapter has an explicit web-search tool or per-run setting. */
    val supportsWebSearch: Boolean get() = false
    fun parseAnswer(stdout: String): String = stdout.trim()

    /** Whether this integration can attach images at all (answered without running anything). */
    val canAttachImages: Boolean get() = false

    /**
     * The run for [prompt] with [images] (app-owned PNG crops), or null when this adapter cannot pass images.
     * No images = the plain text run: [arguments], the prompt on stdin, the trimmed stdout as the answer.
     */
    fun invocation(prompt: String, images: List<AiImageAttachment> = emptyList()): AiCliInvocation? =
        if (images.isEmpty()) textInvocation(prompt) else null
}

/** One crop: its PNG bytes, and where it is written when the CLI takes file paths. */
class AiImageAttachment(val file: File, val png: ByteArray)

/** One CLI run: the argument list, the exact stdin text, and how to read stdout. */
class AiCliInvocation(
    val arguments: List<String>,
    val stdin: String,
    val parse: (stdout: String) -> AiParsedOutput,
    /** True when the images travel inside [stdin], so no PNG files are written. */
    val imagesInline: Boolean = false,
)

/** Today's text run: [AiCliAdapter.arguments], the prompt as stdin, the plain-text answer. */
fun AiCliAdapter.textInvocation(prompt: String) =
    AiCliInvocation(arguments(), prompt, { AiParsedOutput.Answer(parseAnswer(it)) })

/** One stream-json user message line: the images first, then the whole prompt as the text block. */
internal fun claudeImageMessage(prompt: String, pngs: List<ByteArray>): String {
    val blocks = pngs.map { png ->
        JsonValue.JObject(
            listOf(
                "type" to JsonValue.JString("image"),
                "source" to JsonValue.JObject(
                    listOf(
                        "type" to JsonValue.JString("base64"),
                        "media_type" to JsonValue.JString("image/png"),
                        "data" to JsonValue.JString(java.util.Base64.getEncoder().encodeToString(png)),
                    ),
                ),
            ),
        )
    } + JsonValue.JObject(listOf("type" to JsonValue.JString("text"), "text" to JsonValue.JString(prompt)))
    return JsonValue.JObject(
        listOf(
            "type" to JsonValue.JString("user"),
            "message" to JsonValue.JObject(listOf("role" to JsonValue.JString("user"), "content" to JsonValue.JArray(blocks))),
        ),
    ).renderCompact() + "\n"
}
```

`class ClaudeCliAdapter … { … }` 블록 전체를 다음으로 교체(위의 KDoc 주석은 유지):

```kotlin
class ClaudeCliAdapter(private val webSearch: Boolean = false) : AiCliAdapter {
    override val provider = AiProviderId.CLAUDE
    override val supportsWebSearch = true
    override val canAttachImages = true

    private fun toolArguments() =
        listOf("--tools", if (webSearch) "WebSearch" else "", "--strict-mcp-config", "--no-session-persistence") +
            if (webSearch) listOf("--allowedTools", "WebSearch") else emptyList()

    override fun arguments() = listOf("-p", "--output-format", "text") + toolArguments()

    // Print mode takes images only as content blocks of a stream-json user message. Verified with 2.1.300:
    // `--input-format stream-json` requires `--output-format stream-json`, which requires `--verbose`.
    override fun invocation(prompt: String, images: List<AiImageAttachment>): AiCliInvocation =
        if (images.isEmpty()) {
            textInvocation(prompt)
        } else {
            AiCliInvocation(
                listOf("-p", "--input-format", "stream-json", "--output-format", "stream-json", "--verbose") + toolArguments(),
                claudeImageMessage(prompt, images.map { it.png }),
                ::parseClaudeStreamJson,
                imagesInline = true,
            )
        }
}
```

`CodexCliAdapter`에서 다음 두 줄(주석 + `argumentsWithImages` 오버라이드)을

```kotlin
    // `-i` takes several values, so it goes right after `exec`, ended by the next flag (never next to `-`).
    override fun argumentsWithImages(images: List<File>): List<String> =
        if (images.isEmpty()) arguments() else listOf("exec", "-i") + images.map { it.path } + arguments().drop(1)
```
다음으로 교체:
```kotlin
    override val canAttachImages = true

    // `-i` takes several values, so it goes right after `exec`, ended by the next flag (never next to `-`).
    override fun invocation(prompt: String, images: List<AiImageAttachment>): AiCliInvocation =
        if (images.isEmpty()) {
            textInvocation(prompt)
        } else {
            AiCliInvocation(
                listOf("exec", "-i") + images.map { it.file.path } + arguments().drop(1),
                prompt,
                { AiParsedOutput.Answer(parseAnswer(it)) },
            )
        }
```

- [ ] **Step 6: AiCliRunner.kt 구현**

다음 블록을

```kotlin
        // Images are re-encoded crops written only inside the app-owned temp dir (deleted below).
        val imageFiles = images.mapIndexed { i, image ->
            File(workDir, "view-${i + 1}.png").also { javax.imageio.ImageIO.write(image, "png", it) }
        }
        val args = adapter.argumentsWithImages(imageFiles)
            ?: return@runInterruptible AiCliResult.Failed(AiFailure.LAUNCH_FAILED, null, "${adapter.provider.displayName} cannot take images from this app")
        val command = try {
            launchCommand(executable, args)
```
다음으로 교체:
```kotlin
        // Images are re-encoded crops (no source metadata). CLIs that take paths get them as files inside the
        // app-owned temp dir (deleted below); inline CLIs get the bytes in stdin and no file is written.
        val attachments = images.mapIndexed { i, image ->
            val png = ByteArrayOutputStream().also { javax.imageio.ImageIO.write(image, "png", it) }.toByteArray()
            AiImageAttachment(File(workDir, "view-${i + 1}.png"), png)
        }
        val invocation = adapter.invocation(prompt, attachments)
            ?: return@runInterruptible AiCliResult.Failed(AiFailure.LAUNCH_FAILED, null, "${adapter.provider.displayName} cannot take images from this app")
        if (!invocation.imagesInline) attachments.forEach { it.file.writeBytes(it.png) }
        val command = try {
            launchCommand(executable, invocation.arguments)
```

다음 줄을
```kotlin
                started.outputStream.use { it.write(prompt.toByteArray(Charsets.UTF_8)) }
```
다음으로 교체:
```kotlin
                started.outputStream.use { it.write(invocation.stdin.toByteArray(Charsets.UTF_8)) }
```

다음 블록을
```kotlin
            else -> {
                val answer = adapter.parseAnswer(stdout)
                if (answer.isBlank()) AiCliResult.Failed(AiFailure.EMPTY_OUTPUT, exit, excerpt(stderr))
                else AiCliResult.Answer(answer, out.truncated)
            }
```
다음으로 교체:
```kotlin
            else -> when (val parsed = invocation.parse(stdout)) {
                is AiParsedOutput.Answer ->
                    if (parsed.text.isBlank()) AiCliResult.Failed(AiFailure.EMPTY_OUTPUT, exit, excerpt(stderr))
                    else AiCliResult.Answer(parsed.text, out.truncated)
                // The CLI exited 0 but said in its own output that the run failed (stream-json `is_error`).
                is AiParsedOutput.Error -> AiCliResult.Failed(
                    if (AUTH_HINT.containsMatchIn(parsed.message)) AiFailure.AUTH_REQUIRED else AiFailure.EXIT_ERROR,
                    exit,
                    excerpt(parsed.message),
                )
            }
```

- [ ] **Step 7: AiChatController.kt 구현**

다음 줄을
```kotlin
        val canPassImages = adapter.argumentsWithImages(listOf(File("probe.png"))) != null
```
다음으로 교체:
```kotlin
        val canPassImages = adapter.canAttachImages
```

다음 줄을
```kotlin
        if (adapter.argumentsWithImages(listOf(File("probe.png"))) == null) {
```
다음으로 교체:
```kotlin
        if (!adapter.canAttachImages) {
```

`java.io.File` import가 다른 곳에서 더 이상 쓰이지 않으면(`grep -n "File" AiChatController.kt`) 미사용 import 경고만 나므로 그대로 둔다 — `send`/`verifyImageInput`의 `executable: File` 파라미터가 계속 쓴다.

- [ ] **Step 8: 남은 참조 확인**

Run: `grep -rn "argumentsWithImages" app/src`
Expected: 출력 없음

- [ ] **Step 9: 통과 확인**

Run: `./gradlew :app:test --tests 'com.multiviewer.ui.ai.*' --tests 'com.multiviewer.cli.JsonValueTest'`
Expected: PASS (전체). `AiCliLiveAcceptanceTest`는 옵트인이라 SKIPPED.

- [ ] **Step 10: 커밋**

```bash
git -C .worktrees/ai-claude-image branch --show-current   # feat/ai-claude-image-input 이어야 함
cd .worktrees/ai-claude-image
git add app/src/main/kotlin/com/multiviewer/ui/ai/AiCliAdapter.kt app/src/main/kotlin/com/multiviewer/ui/ai/AiCliRunner.kt \
  app/src/main/kotlin/com/multiviewer/ui/ai/AiChatController.kt app/src/test/kotlin/com/multiviewer/ui/ai/AiCliAdapterTest.kt \
  app/src/test/kotlin/com/multiviewer/ui/ai/AiCliRunnerTest.kt app/src/test/kotlin/com/multiviewer/ui/ai/AiChatControllerTest.kt
git commit -m "feat: attach screen crops to Claude via stream-json input

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: 첫 이미지 전송 때 이미지 입력 자동 확인 + 실사용 수용 테스트

(사용자 결정 2026-10-16: 버튼 대신 첫 이미지 전송 때 자동 확인. `verifyImageInput()`은 호출하는 UI가 없으므로 제거.)

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/ui/ai/AiChatController.kt` (`IMAGE_PROBE_COLORS`, `send`, `verifyImageInput` → `probeImageInput`)
- Modify: `app/src/main/kotlin/com/multiviewer/ui/AppState.kt` (TabState: `aiImageChecking`)
- Modify: `app/src/main/kotlin/com/multiviewer/ui/ai/AiChatPanel.kt` (3행 옵션: 확인 중 표시)
- Modify: `app/src/test/kotlin/com/multiviewer/ui/ai/AiChatControllerTest.kt`
- Modify: `app/src/test/kotlin/com/multiviewer/ui/ai/AiCliLiveAcceptanceTest.kt`

**Interfaces:**
- Consumes: `AiCliAdapter.canAttachImages`(Task 2), `AiProviderCapabilities.image: CapabilityState`, 생성자 파라미터 `capabilities`, `onCapabilityUpdate`, `runner`
- Produces:
  - `internal const val IMAGE_PROBE_PROMPT: String`, `internal val IMAGE_PROBE_COLORS: List<Pair<String, java.awt.Color>>`
  - `TabState.aiImageChecking: Boolean` (Compose state)
  - 제거: `AiChatController.verifyImageInput(...)`

**동작:**
- `send` 시점에 이미지가 첨부될 상황(미리보기 있음, `canAttachImages`, 실행 파일 있음, 파일 변경 없음)이고 이미지 상태가 UNKNOWN이면, 질문을 보내기 전에 확인 1회: 무작위 단색 96×96 PNG + `IMAGE_PROBE_PROMPT`.
  - 정답 한 단어 → VERIFIED → 이번 질문부터 크롭 첨부.
  - 답했지만 틀림 → UNSUPPORTED → 좌표만, 컨텍스트에 "확인용 이미지를 읽지 못함 (X)". 이후 같은 실행 동안 재확인 안 함.
  - 실행 실패/타임아웃 → UNKNOWN 유지 → 좌표만("아직 확인되지 않음"), 다음 전송 때 다시 확인.
- 확인 중에는 `tab.aiImageChecking = true` → 패널 3행에 "🖼️ 이미지 입력 확인 중…". 취소(`stop`)되면 finally에서 false.

- [ ] **Step 1: 실패하는 테스트 — AiChatControllerTest**

(1) `visual marks are taken at send time and a crop goes only to a verified image provider`에서 첫 전송이 확인을 유발하지 않도록 바꾼다. 다음 두 줄을

```kotlin
        controller(CapabilityState.UNKNOWN).send(a, "q", AiProviderId.CODEX, File("/bin/true"))
```
```kotlin
        assertTrue(prompt.contains("not verified") || prompt.contains("확인되지 않음"), prompt)
```
각각 다음으로 교체:
```kotlin
        controller(CapabilityState.UNSUPPORTED).send(a, "q", AiProviderId.CODEX, File("/bin/true"))
```
```kotlin
        assertTrue(prompt.contains("확인용 이미지를 읽지 못함"), prompt)
```

(2) 테스트 `image input is verified only by an exact one-word color` 전체를 삭제한다(`verifyImageInput`이 사라짐; 같은 판정은 아래 새 테스트가 `send` 경로로 검증).

(3) 클래스 안(마지막 테스트 뒤)에 추가:

```kotlin
    private fun probeAnswer(imgs: List<java.awt.image.BufferedImage>) =
        IMAGE_PROBE_COLORS.first { it.second.rgb == imgs.single().getRGB(0, 0) }.first

    private fun imageTab(name: String = "a.jpg"): TabState = tab(name).apply {
        aiImageSource = androidx.compose.ui.graphics.ImageBitmap(400, 200)
        aiViewport = ImageRegion(0, 0, 400, 200)
    }

    @Test
    fun `the first image send checks image input once, then attaches the crop`() = runBlocking {
        val a = imageTab()
        var state = CapabilityState.UNKNOWN
        val prompts = mutableListOf<String>()
        var mainImages = -1
        var checkingSeen = false
        val controller = AiChatController(this, now = { 1L }, ioDispatcher = Dispatchers.Default,
            capabilities = { AiProviderCapabilities(it, image = state) },
            onCapabilityUpdate = { u -> u.image?.let { state = it } },
            runner = { _, _, p, _, imgs ->
                prompts += p
                if (p == IMAGE_PROBE_PROMPT) {
                    checkingSeen = a.aiImageChecking
                    AiCliResult.Answer(probeAnswer(imgs), false)
                } else {
                    mainImages = imgs.size
                    AiCliResult.Answer("ok", false)
                }
            })
        controller.send(a, "q", AiProviderId.CLAUDE, File("/bin/true"))
        waitFor { a.aiConversation.status == AiRunStatus.FINISHED }
        assertEquals(listOf(IMAGE_PROBE_PROMPT), prompts.take(1))
        assertEquals(2, prompts.size)
        assertEquals(1, mainImages)
        assertEquals(CapabilityState.VERIFIED, state)
        assertTrue(checkingSeen)
        assertFalse(a.aiImageChecking)

        controller.send(a, "q2", AiProviderId.CLAUDE, File("/bin/true"))
        waitFor { a.aiConversation.messages.count { it.role == AiRole.ASSISTANT } == 2 }
        assertEquals(3, prompts.size) // verified: no second check
        assertEquals(1, mainImages)
    }

    @Test
    fun `a wrong check answer sends coordinates only and is not retried`() = runBlocking {
        val a = imageTab()
        var state = CapabilityState.UNKNOWN
        var probes = 0
        var prompt = ""
        var mainImages = -1
        val controller = AiChatController(this, now = { 1L }, ioDispatcher = Dispatchers.Default,
            capabilities = { AiProviderCapabilities(it, image = state) },
            onCapabilityUpdate = { u -> u.image?.let { state = it } },
            runner = { _, _, p, _, imgs ->
                if (p == IMAGE_PROBE_PROMPT) {
                    probes++
                    AiCliResult.Answer("I cannot see any image, so I can't say if it is red, green or blue.", false)
                } else {
                    prompt = p
                    mainImages = imgs.size
                    AiCliResult.Answer("ok", false)
                }
            })
        controller.send(a, "q", AiProviderId.CODEX, File("/bin/true"))
        waitFor { a.aiConversation.status == AiRunStatus.FINISHED }
        assertEquals(CapabilityState.UNSUPPORTED, state)
        assertEquals(0, mainImages)
        assertTrue(prompt.contains("확인용 이미지를 읽지 못함"), prompt)

        controller.send(a, "q2", AiProviderId.CODEX, File("/bin/true"))
        waitFor { a.aiConversation.messages.count { it.role == AiRole.ASSISTANT } == 2 }
        assertEquals(1, probes)
    }

    @Test
    fun `a check run that fails leaves image input unknown and the next send checks again`() = runBlocking {
        val a = imageTab()
        var state = CapabilityState.UNKNOWN
        var probes = 0
        var prompt = ""
        val controller = AiChatController(this, now = { 1L }, ioDispatcher = Dispatchers.Default,
            capabilities = { AiProviderCapabilities(it, image = state) },
            onCapabilityUpdate = { u -> u.image?.let { state = it } },
            runner = { _, _, p, _, _ ->
                if (p == IMAGE_PROBE_PROMPT) {
                    probes++
                    AiCliResult.Failed(AiFailure.EXIT_ERROR, 1, "boom")
                } else {
                    prompt = p
                    AiCliResult.Answer("ok", false)
                }
            })
        controller.send(a, "q", AiProviderId.CLAUDE, File("/bin/true"))
        waitFor { a.aiConversation.status == AiRunStatus.FINISHED }
        assertEquals(CapabilityState.UNKNOWN, state)
        assertTrue(prompt.contains("확인되지 않음"), prompt)

        controller.send(a, "q2", AiProviderId.CLAUDE, File("/bin/true"))
        waitFor { a.aiConversation.messages.count { it.role == AiRole.ASSISTANT } == 2 }
        assertEquals(2, probes)
    }

    @Test
    fun `no check runs without a preview image or for a provider that cannot take images`() = runBlocking {
        var probes = 0
        val controller = AiChatController(this, now = { 1L }, ioDispatcher = Dispatchers.Default,
            capabilities = { AiProviderCapabilities(it, image = CapabilityState.UNKNOWN) },
            runner = { _, _, p, _, _ ->
                if (p == IMAGE_PROBE_PROMPT) probes++
                AiCliResult.Answer("ok", false)
            })
        val plain = tab("b.jpg")
        controller.send(plain, "q", AiProviderId.CLAUDE, File("/bin/true"))
        waitFor { plain.aiConversation.status == AiRunStatus.FINISHED }
        val a = imageTab()
        controller.send(a, "q", AiProviderId.GEMINI, File("/bin/true"))
        waitFor { a.aiConversation.status == AiRunStatus.FINISHED }
        assertEquals(0, probes)
    }
```

(`AiFailure`, `AiRole`, `AiRunStatus`, `ImageRegion`은 같은 패키지 `com.multiviewer.ui.ai`; `assertFalse`는 이미 import 되어 있다.)

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :app:test --tests 'com.multiviewer.ui.ai.AiChatControllerTest'`
Expected: 컴파일 실패 — `Unresolved reference: IMAGE_PROBE_PROMPT`, `aiImageChecking` (그리고 `IMAGE_PROBE_COLORS`가 private)

- [ ] **Step 3: TabState 상태 추가 — AppState.kt**

다음 줄을 찾아
```kotlin
    var aiMarkMode: Boolean by mutableStateOf(false)
```
바로 아래에 추가:
```kotlin
    // True while the first image send of a provider checks that its CLI really reads images (AiChatController).
    var aiImageChecking: Boolean by mutableStateOf(false)
```

- [ ] **Step 4: AiChatController.kt 구현**

(a) 다음 블록을
```kotlin
private val IMAGE_PROBE_COLORS = listOf(
```
다음으로 교체(목록 내용은 그대로, 선언만 바꾸고 프롬프트 상수를 위에 추가):
```kotlin
internal const val IMAGE_PROBE_PROMPT =
    "The attached image is one solid color. Reply with only that color as a single lowercase English word."

internal val IMAGE_PROBE_COLORS = listOf(
```

(b) `AiCapabilityUpdate`의 KDoc 한 줄
```kotlin
    /** Result of an explicit image-input check. */
```
를
```kotlin
    /** Result of the image-input check run on a provider's first image send. */
```
로 바꾼다.

(c) `send` 안의 다음 줄을
```kotlin
        val imageVerified = capabilities(provider)?.canAttachImage == true
```
다음으로 교체:
```kotlin
        val imageState = capabilities(provider)?.image ?: CapabilityState.UNKNOWN
```

(d) `send` 안의 다음 블록을
```kotlin
            val shown = snapshot.visual
            val region = shown?.let { it.marked ?: it.visible }
            var cropFailed: String? = null
            val crop = if (shown != null && region != null && imageSource != null && canPassImages && imageVerified) {
```
다음으로 교체:
```kotlin
            val shown = snapshot.visual
            val region = shown?.let { it.marked ?: it.visible }
            // A provider's first image send checks its image input once, before the question goes out. A run
            // that fails (timeout, login) stays UNKNOWN, so a later send tries again; a wrong answer does not.
            val checkedImage = if (shown != null && region != null && imageSource != null && canPassImages &&
                imageState == CapabilityState.UNKNOWN && executable != null && !snapshot.fileChangedSinceOpen
            ) {
                tab.aiImageChecking = true
                try {
                    probeImageInput(provider, executable)
                } finally {
                    tab.aiImageChecking = false
                }
            } else {
                imageState
            }
            var cropFailed: String? = null
            val crop = if (shown != null && region != null && imageSource != null && canPassImages && checkedImage == CapabilityState.VERIFIED) {
```

(e) `omitted`의 `when`에서 다음 줄을
```kotlin
                else -> label("${provider.displayName} 이미지 입력이 아직 확인되지 않음", "${provider.displayName} image input is not verified yet")
```
다음 두 줄로 교체:
```kotlin
                checkedImage == CapabilityState.UNSUPPORTED ->
                    label("확인용 이미지를 읽지 못함 (${provider.displayName})", "${provider.displayName} failed the image input check")
                else -> label("${provider.displayName} 이미지 입력이 아직 확인되지 않음", "${provider.displayName} image input is not verified yet")
```

(f) `fun verifyImageInput(...)` 함수 전체(위의 KDoc 포함, `/** Explicit, user-started check …` 부터 함수 끝 `}` 까지)를 다음으로 교체:

```kotlin
    /**
     * One round trip proving [provider] reads an attached image: a random solid color, asked for as one exact
     * word ("I can't see the image, maybe red?" must not pass). VERIFIED / UNSUPPORTED (it answered, wrongly) /
     * UNKNOWN (the run itself failed or timed out, so a later send tries again).
     */
    private suspend fun probeImageInput(provider: AiProviderId, executable: File): CapabilityState {
        val (word, color) = IMAGE_PROBE_COLORS.random()
        val probe = java.awt.image.BufferedImage(96, 96, java.awt.image.BufferedImage.TYPE_INT_RGB).apply {
            val g = createGraphics(); g.color = color; g.fillRect(0, 0, 96, 96); g.dispose()
        }
        val result = runner(adapterFor(provider), executable, IMAGE_PROBE_PROMPT, { true }, listOf(probe))
        val state = when {
            result is AiCliResult.Answer && result.text.trim().trimEnd('.').lowercase() == word -> CapabilityState.VERIFIED
            result is AiCliResult.Answer -> CapabilityState.UNSUPPORTED
            else -> CapabilityState.UNKNOWN
        }
        report(AiCapabilityUpdate(
            provider,
            image = state,
            detail = when (result) {
                is AiCliResult.Answer -> result.text.take(80)
                is AiCliResult.Failed -> result.reason.name
                else -> result.toString()
            },
        ))
        return state
    }
```

Run: `grep -rn "verifyImageInput\|canAttachImage\b" app/src` → `verifyImageInput`는 없어야 한다. (`canAttachImage`(단수, `AiProviderCapabilities`)가 더 이상 쓰이지 않으면 그대로 둔다 — 이 태스크 범위 밖.)

- [ ] **Step 5: 패널 확인 중 표시 — AiChatPanel.kt**

다음 블록을 찾는다(영역 지정 블록의 끝):

```kotlin
                            } else if (tab.aiMarkMode) {
                                Text(label("이미지 위를 드래그하세요", "Drag on image"), color = AppColors.TextMuted, fontSize = 10.sp, softWrap = false)
                            }
```
바로 뒤(여전히 `if (hasImageSource) {` 안)에 추가:

```kotlin
                            if (tab.aiImageChecking) {
                                Text(label("🖼️ 이미지 입력 확인 중…", "🖼️ Checking image input…"), color = AppColors.TextMuted, fontSize = 10.sp, softWrap = false)
                            }
```

(`Text`가 `androidx.compose.material3.Text`인지 `grep -n "import androidx.compose.material" AiChatPanel.kt`로 확인.)

- [ ] **Step 6: 통과 확인**

Run: `./gradlew :app:test --tests 'com.multiviewer.ui.ai.*'` 그리고 `./gradlew :app:compileKotlin`
Expected: PASS / BUILD SUCCESSFUL

- [ ] **Step 7: 실사용 수용 테스트 확장**

`AiCliLiveAcceptanceTest.kt`에서

```kotlin
            if (provider == AiProviderId.CODEX) {
```
를
```kotlin
            if (adapterFor(provider).canAttachImages) {
```
로 바꾸고, 같은 블록의 마지막 줄

```kotlin
                println("LIVE IMAGE ${provider.name}: $img")
```
뒤에 추가:
```kotlin
                kotlin.test.assertTrue(
                    img is AiCliResult.Answer && img.text.trim().trimEnd('.').lowercase() == "red",
                    "${provider.name} did not read the attached image: $img",
                )
```

- [ ] **Step 8: 실제 CLI로 수용 테스트**

Run: `UNWRAPMEDIA_LIVE_AI=1 UNWRAPMEDIA_LIVE_AI_PROVIDERS=CLAUDE,CODEX ./gradlew :app:test --tests 'com.multiviewer.ui.ai.AiCliLiveAcceptanceTest' -i 2>&1 | grep "LIVE"`
Expected: `LIVE IMAGE CLAUDE: Answer(text=red, …)`, `LIVE IMAGE CODEX: Answer(text=red, …)`, 테스트 PASS. (CLI 미설치/미로그인이면 실패 사유를 그대로 보고한다 — 통과로 바꾸지 않는다.)

- [ ] **Step 9: 커밋**

```bash
git -C .worktrees/ai-claude-image branch --show-current   # feat/ai-claude-image-input 이어야 함
cd .worktrees/ai-claude-image
git add app/src/main/kotlin/com/multiviewer/ui/ai/AiChatController.kt app/src/main/kotlin/com/multiviewer/ui/AppState.kt \
  app/src/main/kotlin/com/multiviewer/ui/ai/AiChatPanel.kt app/src/test/kotlin/com/multiviewer/ui/ai/AiChatControllerTest.kt \
  app/src/test/kotlin/com/multiviewer/ui/ai/AiCliLiveAcceptanceTest.kt
git commit -m "feat: check AI image input automatically on the first image send

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: Antigravity(agy) 이미지 첨부 — 작업 폴더 PNG + `view_file`

(사용자 결정 2026-10-16. 실측: agy 1.3.5, `--mode plan --output-format stream-json`, 작업 폴더의 `view-1.png`(파란 단색, 색과 무관한 이름) + 프롬프트 "Use the view_file tool to open the image file view-1.png in the current directory … Do not run any commands … Reply with only that color" → `view_file` 2회, 거부 동작 없음, 답 `blue`. `@파일`은 첨부되지 않고 셸 명령 시도 후 거부됨 — 쓰지 않는다.)

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/ui/ai/AiCliAdapter.kt` (`AgyCliAdapter`)
- Modify: `app/src/test/kotlin/com/multiviewer/ui/ai/AiCliAdapterTest.kt`, `app/src/test/kotlin/com/multiviewer/ui/ai/AiCliRunnerTest.kt`

**Interfaces:**
- Consumes (Task 2): `AiCliAdapter.invocation`, `canAttachImages`, `AiCliInvocation`, `AiImageAttachment`, `textInvocation`, `AiParsedOutput`; 러너는 `imagesInline == false`면 `attachments`를 작업 폴더에 `view-N.png`로 쓰고 그 폴더에서 CLI를 실행한다.
- Produces: `AgyCliAdapter.canAttachImages == true`; 이미지 실행의 stdin = 안내 문단 + 원래 prompt.

- [ ] **Step 1: 실패하는 테스트 — AiCliAdapterTest**

테스트 `only claude and codex can attach images`를 다음으로 교체(이름과 기대 집합 변경):

```kotlin
    @Test
    fun `claude, codex and antigravity can attach images, gemini cannot`() {
        assertEquals(
            setOf(AiProviderId.CLAUDE, AiProviderId.CODEX, AiProviderId.AGY),
            AiProviderId.entries.filter { adapterFor(it).canAttachImages }.toSet(),
        )
        assertEquals(null, adapterFor(AiProviderId.GEMINI).invocation("q", listOf(AiImageAttachment(File("x.png"), ByteArray(1)))))
    }
```

클래스 안에 추가:

```kotlin
    @Test
    fun `antigravity image runs name the work-dir files for view_file and keep the prompt`() {
        val adapter = AgyCliAdapter()
        val inv = adapter.invocation(
            "PROMPT",
            listOf(
                AiImageAttachment(File("/tmp/unwrapmedia-ai-1/view-1.png"), ByteArray(1)),
                AiImageAttachment(File("/tmp/unwrapmedia-ai-1/view-2.png"), ByteArray(1)),
            ),
        )!!
        assertEquals(adapter.arguments(), inv.arguments)
        assertFalse(inv.imagesInline)
        assertTrue(inv.stdin.contains("view_file"), inv.stdin)
        assertTrue(inv.stdin.contains("view-1.png, view-2.png"), inv.stdin)
        assertFalse(inv.stdin.contains("/tmp/"), inv.stdin) // file names only: the CLI runs in that directory
        assertTrue(inv.stdin.endsWith("\n\nPROMPT"), inv.stdin)
        assertEquals(AiParsedOutput.Answer("blue"), inv.parse("blue\n"))
    }
```

- [ ] **Step 2: 실패하는 테스트 — AiCliRunnerTest**

`antigravity adapter uses plan mode and text output format`의 마지막 단언
```kotlin
        assertFalse(adapter.canAttachImages)
```
를
```kotlin
        assertTrue(adapter.canAttachImages)
```
로 바꾼다.

클래스 안(마지막 테스트 뒤)에 추가(`square()` 헬퍼는 Task 2에서 이 파일에 추가됨):

```kotlin
    @Test
    fun `antigravity image runs get png files in the work dir and the view_file instruction on stdin`() {
        val stdin = File(dir, "agy-stdin.txt")
        val exe = script("agy", """cat > "${stdin.absolutePath}"; ls""")
        val result = runBlocking { runAiCli(AgyCliAdapter(), exe, "q", images = listOf(square())) }
        assertIs<AiCliResult.Answer>(result)
        assertTrue(result.text.contains("view-1.png"), result.text)
        val sent = stdin.readText()
        assertTrue(sent.contains("view_file") && sent.contains("view-1.png"), sent)
        assertTrue(sent.endsWith("\n\nq"), sent)
    }
```

- [ ] **Step 3: 실패 확인**

Run: `./gradlew :app:test --tests 'com.multiviewer.ui.ai.AiCliAdapterTest' --tests 'com.multiviewer.ui.ai.AiCliRunnerTest'`
Expected: FAIL — 기대 집합에 AGY 없음, `antigravity image runs …`에서 `invocation`이 null(`!!` NPE) / LAUNCH_FAILED.

- [ ] **Step 4: AgyCliAdapter 구현**

`class AgyCliAdapter : AiCliAdapter { … }` 블록 전체를 다음으로 교체(위의 KDoc 주석은 유지):

```kotlin
class AgyCliAdapter : AiCliAdapter {
    override val provider = AiProviderId.AGY
    // No documented per-run web toggle: the prompt can request search, but cannot guarantee it.
    override fun arguments() = listOf("--mode", "plan", "--output-format", "text")

    // No attach flag, and `@file` is not attached (observed: it tries shell commands instead, refused in plan
    // mode). Its own view_file tool does read a PNG in the working directory (verified with agy 1.3.5), so the
    // crops stay files in the app-owned temp dir and the prompt names them. Whether it really looked is still
    // decided by the first-send color check.
    override val canAttachImages = true

    override fun invocation(prompt: String, images: List<AiImageAttachment>): AiCliInvocation =
        if (images.isEmpty()) {
            textInvocation(prompt)
        } else {
            val names = images.joinToString(", ") { it.file.name }
            AiCliInvocation(
                arguments(),
                "Attached images in the current directory: $names. Open each one with the view_file tool before " +
                    "answering. Do not run any commands.\n\n" + prompt,
                { AiParsedOutput.Answer(parseAnswer(it)) },
            )
        }
}
```

- [ ] **Step 5: 통과 확인**

Run: `./gradlew :app:test --tests 'com.multiviewer.ui.ai.*'` 그리고 `./gradlew :app:compileKotlin`
Expected: PASS / BUILD SUCCESSFUL

- [ ] **Step 6: 실제 CLI로 수용 테스트 (agy)**

Run: `UNWRAPMEDIA_LIVE_AI=1 UNWRAPMEDIA_LIVE_AI_PROVIDERS=AGY ./gradlew :app:test --tests 'com.multiviewer.ui.ai.AiCliLiveAcceptanceTest' -i 2>&1 | grep "LIVE"`
Expected: `LIVE IMAGE AGY: Answer(text=red, …)`, 테스트 PASS. (실패하면 LIVE 줄을 그대로 보고한다 — 단언을 약하게 바꾸지 않는다.)

- [ ] **Step 7: 커밋**

```bash
git -C .worktrees/ai-claude-image branch --show-current   # feat/ai-claude-image-input 이어야 함
cd .worktrees/ai-claude-image
git add app/src/main/kotlin/com/multiviewer/ui/ai/AiCliAdapter.kt \
  app/src/test/kotlin/com/multiviewer/ui/ai/AiCliAdapterTest.kt app/src/test/kotlin/com/multiviewer/ui/ai/AiCliRunnerTest.kt
git commit -m "feat: let Antigravity read AI image crops via view_file

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: 전체 검증

- [ ] **Step 1: 전체 테스트**

Run: `./gradlew :app:test`
Expected: BUILD SUCCESSFUL (`ThumbnailDiskCacheTest`만 단독 재실행으로 통과하면 허용)

- [ ] **Step 2: GUI 확인 (macOS)**

`./gradlew :app:run` → JPEG 이미지 열기 → AI 패널 → 도구 Claude Code:
1. "🎯 영역 지정"으로 영역 드래그 → 질문 "표시한 영역에 무엇이 보이나요?" 전송 → 3행에 "🖼️ 이미지 입력 확인 중…"이 잠시 보였다가 사라진다.
2. 답이 실제 영역 내용을 묘사한다. 두 번째 질문에서는 확인 표시가 다시 나오지 않는다.
3. 보낸 컨텍스트 펼치기에 "첨부 이미지: … 영역을 잘라 축소한 PNG"가 있다.
4. 도구를 Antigravity로 바꾸고 이미지 질문 → 첫 전송에 확인 표시 후 답이 영역 내용을 묘사한다(view_file).

- [ ] **Step 3: Windows 확인 항목 기록**

Windows 실기 확인은 사용자 몫으로 남긴다: `claude.cmd` 래퍼를 거친 큰 stdin(≈1 MB) 전달, 첫 이미지 전송 자동 확인 → 크롭 질문.
