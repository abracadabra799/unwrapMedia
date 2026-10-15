# All-AI Web Search Implementation Plan

**Spec:** `docs/superpowers/specs/2026-10-15-all-ai-web-search-design.md`

1. Add failing adapter tests for Codex live/disabled modes, Gemini temporary settings, and search-aware prompts. Verify that they fail against the prior Claude-only implementation.
2. Add per-provider CLI configuration in `AiCliAdapter.kt` and temporary settings setup in `AiCliRunner.kt`. Pass the selected choice through `AiChatController.kt`.
3. Show the choice for each selected provider in `AiChatPanel.kt`; label Antigravity as a request because its CLI has no confirmed search switch.
4. Bump the three version locations to 1.28.0; run focused and full tests, inspect the diff, merge to `main`, and push under the user's existing go-ahead.
