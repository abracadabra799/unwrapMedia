# Web Search Across AI Providers

## Goal

Show the web-search choice in the AI panel for every selected CLI provider, including Codex. A checked option requests public web information without sending media-file context in search queries. A company account or policy may still prevent the provider from searching.

## Behavior

- Keep the search choice visible when Claude Code, Codex, Gemini CLI, or Antigravity is selected. Disable it if the selected CLI is not installed or a request is running.
- Claude Code uses its existing `WebSearch`-only tool selection.
- Codex uses the official `web_search=live` or `web_search=disabled` per-run setting and retains its read-only command sandbox.
- Gemini CLI gets an app-owned temporary project setting whose built-in tool allowlist contains only `google_web_search` when enabled, or no built-in tools when disabled. Delete it with the existing request temp directory.
- Antigravity has no documented per-run search switch. Show “Web Search Request” and ask the CLI through the prompt; do not report search as verified from an answer alone.
- The prompt may ask for public documentation through web search when enabled, requires URLs for results actually searched, and forbids putting file contents, paths, serial numbers, GPS, or conversation text into search terms. When disabled, it asks the CLI not to search.

## Verification

Test adapter arguments, temporary Gemini settings and cleanup, and controller forwarding of the search choice. Run the full test suite and Kotlin compilation. Confirm provider availability and actual search behavior on the company installation separately.

## Official references

- Codex configuration: https://developers.openai.com/codex/config-reference
- Gemini CLI web search: https://geminicli.com/docs/tools/web-search/
- Gemini CLI configuration: https://geminicli.com/docs/get-started/configuration/
- Antigravity CLI reference: https://antigravity.google/docs/cli/reference/
