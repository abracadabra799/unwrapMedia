package com.multiviewer.ui.ai

import java.io.File

/**
 * How to run one provider's CLI non-interactively and read-only. The question and context always travel on
 * stdin, never in the argument list, so no user text is ever parsed by a shell or by cmd.exe; the arguments
 * are constants owned by the adapter.
 */
interface AiCliAdapter {
    val provider: AiProviderId
    fun arguments(): List<String>
    fun parseAnswer(stdout: String): String = stdout.trim()
}

/** Claude Code print mode with every built-in tool and MCP server off and nothing saved to disk. */
class ClaudeCliAdapter : AiCliAdapter {
    override val provider = AiProviderId.CLAUDE
    override fun arguments() = listOf(
        "-p", "--output-format", "text", "--tools", "", "--strict-mcp-config", "--no-session-persistence",
    )
}

/**
 * Codex exec in its read-only sandbox (no writes, no network for commands), outside any git repo, ephemeral.
 * Note: the read-only sandbox can still read local files; the panel reports the tool bridge as unverified.
 */
class CodexCliAdapter : AiCliAdapter {
    override val provider = AiProviderId.CODEX
    override fun arguments() = listOf(
        "exec", "--sandbox", "read-only", "--skip-git-repo-check", "--ephemeral", "--color", "never", "-",
    )
}

/** Gemini CLI headless in plan (read-only) approval mode; the -p text is appended to the stdin prompt. */
class GeminiCliAdapter : AiCliAdapter {
    override val provider = AiProviderId.GEMINI
    override fun arguments() = listOf(
        "--approval-mode", "plan", "--output-format", "text", "-p", "Answer the request above.",
    )
}

fun adapterFor(provider: AiProviderId): AiCliAdapter = when (provider) {
    AiProviderId.CLAUDE -> ClaudeCliAdapter()
    AiProviderId.CODEX -> CodexCliAdapter()
    AiProviderId.GEMINI -> GeminiCliAdapter()
}

internal val isWindowsOs: Boolean get() = System.getProperty("os.name").lowercase().contains("win")

/**
 * Where CLIs usually live. A GUI app launched from Finder/Explorer often gets a minimal PATH, so the
 * common per-user install locations are searched after PATH.
 */
fun cliSearchDirectories(env: Map<String, String> = System.getenv(), home: String = System.getProperty("user.home")): List<File> {
    val path = (env["PATH"] ?: env["Path"]).orEmpty().split(File.pathSeparatorChar).filter { it.isNotBlank() }.map(::File)
    val extra = buildList {
        add(File(home, ".local/bin"))
        add(File(home, ".npm-global/bin"))
        add(File(home, ".bun/bin"))
        add(File("/opt/homebrew/bin"))
        add(File("/usr/local/bin"))
        env["APPDATA"]?.let { add(File(it, "npm")) }
        env["LOCALAPPDATA"]?.let { add(File(it, "Programs")) }
    }
    return (path + extra).distinct()
}

/** First match of [command] in [dirs]; on Windows each PATHEXT extension is tried in order. */
fun resolveExecutable(command: String, dirs: List<File>, windows: Boolean = isWindowsOs, pathExt: String? = System.getenv("PATHEXT")): File? {
    val names = if (windows) {
        (pathExt ?: ".COM;.EXE;.BAT;.CMD").split(';').filter { it.isNotBlank() }.map { command + it.lowercase() }
    } else {
        listOf(command)
    }
    for (dir in dirs) for (name in names) {
        val candidate = File(dir, name)
        if (candidate.isFile && (windows || candidate.canExecute())) return candidate.absoluteFile
    }
    return null
}

/**
 * The process command for [executable]. Windows .cmd/.bat shims (npm installs) need cmd.exe; the whole
 * command line is passed as one pre-quoted argument for `cmd /s /c`, which strips only the outer quotes.
 * Safe because every argument is an adapter constant (no user text, no cmd metacharacters).
 */
fun launchCommand(executable: File, args: List<String>, windows: Boolean = isWindowsOs, comSpec: String = System.getenv("ComSpec") ?: "cmd.exe"): List<String> {
    val shim = windows && executable.name.lowercase().let { it.endsWith(".cmd") || it.endsWith(".bat") }
    if (!shim) return listOf(executable.path) + args
    fun quote(arg: String) = if (arg.isEmpty() || arg.any { it == ' ' || it == '\t' }) "\"$arg\"" else arg
    val line = (listOf("\"${executable.path}\"") + args.map(::quote)).joinToString(" ")
    return listOf(comSpec, "/d", "/s", "/c", "\"$line\"")
}

private const val MAX_HISTORY_TURNS = 8
private const val MAX_HISTORY_CHARS_PER_TURN = 4_000

/**
 * The text sent on stdin. Session resume is not assumed for any provider, so the recent turns are resent
 * explicitly (questions and answers only; older contexts and app notices are dropped).
 */
fun buildAiCliPrompt(history: List<AiMessage>, context: String, question: String): String = buildString {
    appendLine("당신은 미디어 분석 앱 unwrapMedia 안에서 동작하는 분석 보조자입니다.")
    appendLine("- 파일을 수정하거나 명령을 실행하지 말고, 아래에 주어진 자료만으로 답하세요.")
    appendLine("- 근거가 되는 오프셋, 검사 ID, 프레임 번호를 인용하고, 확인된 사실과 가설을 구분하세요.")
    appendLine("- 앱의 PASS/FAIL 판정을 바꾸지 마세요. 판정과 다른 의견은 가설로 표시하세요.")
    appendLine("- 사용자가 쓴 언어로 답하세요.")
    appendLine()
    val turns = history.filter { it.role != AiRole.NOTICE }.takeLast(MAX_HISTORY_TURNS)
    if (turns.isNotEmpty()) {
        appendLine("### [이전 대화]")
        for (m in turns) {
            val who = if (m.role == AiRole.USER) "사용자" else "AI"
            val text = if (m.text.length <= MAX_HISTORY_CHARS_PER_TURN) m.text else m.text.take(MAX_HISTORY_CHARS_PER_TURN) + "…"
            appendLine("$who: $text")
        }
        appendLine()
    }
    append(context)
    appendLine()
    appendLine("### [질문]")
    appendLine(question)
}
