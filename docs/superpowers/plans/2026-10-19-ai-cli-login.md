# AI CLI Login Implementation Plan

**Goal:** Open the selected AI CLI's interactive login flow from Ask AI.

**Architecture:** A dedicated PTY session runs the installed CLI. The panel exposes an action at selection and authentication failure; a small window owns the terminal lifecycle.

**Tech Stack:** Kotlin, Compose Desktop, pty4j, JediTerm.

**Spec:** `docs/superpowers/specs/2026-10-19-ai-cli-login-design.md`

## Tasks

- [x] Test platform command construction and auth failure action eligibility; confirm tests fail.
- [x] Implement session, terminal window, and panel wiring with bilingual labels.
- [x] Run focused and full tests, review diff, commit on feature branch, fast-forward main, push origin/main.
