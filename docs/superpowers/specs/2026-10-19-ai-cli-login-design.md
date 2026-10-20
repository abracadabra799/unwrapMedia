# AI CLI login from Ask AI — Design

The Ask AI panel detects installed CLIs, but a failed authentication request only shows a notice. Add an account connection action for the selected installed CLI, both beside its installation status and beside an authentication failure. The action opens the real interactive CLI in a dedicated PTY terminal; the CLI owns authentication and may open its browser flow. The app never handles passwords or tokens. On return, the user retries the question explicitly. Keep the original failure detail because account eligibility cannot always be fixed by signing in.

Use the existing pty4j/JediTerm dependencies. On Windows, start PowerShell with a single quoted absolute executable path so `.cmd` shims work; on macOS/Linux, execute the resolved CLI path directly. Close the PTY and child processes with the window. Show startup failures in the window. No new dependencies or version bump for this focused usability fix.
