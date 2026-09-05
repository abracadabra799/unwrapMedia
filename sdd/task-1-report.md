# Task 1 Report: Add dependencies and repository

## Summary

Successfully added JediTerm terminal emulator (v3.74) and pty4j pseudo-terminal (v0.13.12) dependencies to the Kotlin + Compose Multiplatform project. All build verification steps completed successfully with no failures.

## Changes Made

### File 1: gradle/libs.versions.toml
- Added version entries for `jediterm = "3.74"` and `pty4j = "0.13.12"`
- Added library aliases:
  - `jediterm-core` → `org.jetbrains.jediterm:jediterm-core:3.74`
  - `jediterm-ui` → `org.jetbrains.jediterm:jediterm-ui:3.74`
  - `pty4j` → `org.jetbrains.pty4j:pty4j:0.13.12`
- **Note**: Upgraded Kotlin from 2.0.21 to 2.4.0 (see Concerns section)

### File 2: settings.gradle.kts
- Added JetBrains cache-redirector repository: `https://cache-redirector.jetbrains.com/intellij-dependencies`
- Maintains existing repositories for mavenCentral, google, and compose-dev

### File 3: app/build.gradle.kts
- Added three new implementation dependencies:
  - `implementation(libs.jediterm.core)`
  - `implementation(libs.jediterm.ui)`
  - `implementation(libs.pty4j)`
- Added Kotlin stdlib resolution force configuration:
  ```kotlin
  configurations.all {
      resolutionStrategy {
          force("org.jetbrains.kotlin:kotlin-stdlib:2.4.0")
      }
  }
  ```

## Verification Results

### Step 4: Dependency Resolution ✓
Ran: `./gradlew :app:dependencies --configuration runtimeClasspath`

**Result**: BUILD SUCCESSFUL in 7s

**Resolved dependencies verified**:
- `org.jetbrains.jediterm:jediterm-core:3.74` ✓
- `org.jetbrains.jediterm:jediterm-ui:3.74` ✓
- `org.jetbrains.pty4j:pty4j:0.13.12` ✓
- `net.java.dev.jna:jna:5.14.0` (transitive) ✓
- `net.java.dev.jna:jna-platform:5.14.0` (transitive) ✓

**Note**: kotlin-stdlib resolved to 2.4.0 (highest version available), which is forward-compatible with the compiler.

### Step 5: Kotlin Compilation ✓
Ran: `./gradlew :app:compileKotlin`

**Result**: BUILD SUCCESSFUL in 36s

Compilation output showed expected warnings about unnecessary safe calls and redundant assertions in existing code, but no errors. All compilation errors resolved after Kotlin version upgrade.

### Step 6: Test Suite ✓
Ran: `./gradlew :app:test`

**Result**: BUILD SUCCESSFUL with 826 tests, 0 failures

Test verification:
- Total tests: 826 (matches baseline)
- Failed tests: 0 (matches baseline)
- All test XML files show zero failures

## Kotlin Version Upgrade Rationale

**Issue Encountered**: Initial compilation with Kotlin 2.0.21 failed with metadata incompatibility:
```
Module was compiled with an incompatible version of Kotlin. 
The binary version of its metadata is 2.4.0, expected version is 2.0.0.
```

**Root Cause**: The JediTerm libraries (jediterm-core:3.74, jediterm-ui:3.74) and pty4j:0.13.12 were compiled with Kotlin 2.4.0. The Kotlin 2.0.21 compiler cannot read metadata from libraries compiled with a newer Kotlin version.

**Solution Applied**: Upgraded `gradle/libs.versions.toml` to use `kotlin = "2.4.0"` to match the dependency metadata format. This is a necessary change not explicitly specified in the brief but required for successful compilation with the specified dependencies.

**Fallback Note**: The stdlib force configuration was added as documented in the brief's fallback instructions. While the root cause was ultimately the compiler version mismatch (not stdlib mismatch), this configuration prevents future stdlib version conflicts.

## Self-Review Findings

### Positive
- All specified dependencies resolve correctly from the JetBrains repositories
- No FAILED markers in dependency resolution output
- Compilation successful with only non-critical warnings
- Test suite baseline maintained at 826 tests with 0 failures
- Commit message follows project conventions with proper attribution
- Branch is correct: `feature/embedded-ai-cli-terminal`

### Concerns
1. **Kotlin Version Divergence**: Brief specified `kotlin = "2.0.21"` but implementation required `2.4.0`. This is necessary due to library metadata incompatibility but represents a deviation from the exact requirements.

2. **Fallback Configuration**: The stdlib force configuration in `app/build.gradle.kts` was added per brief fallback instructions, though the root issue was Kotlin compiler version, not stdlib version.

## Files Modified

- `/Users/dong.kim/AndroidStudioProjects/multiViewer/.worktrees/embedded-ai-cli-terminal/gradle/libs.versions.toml`
- `/Users/dong.kim/AndroidStudioProjects/multiViewer/.worktrees/embedded-ai-cli-terminal/settings.gradle.kts`
- `/Users/dong.kim/AndroidStudioProjects/multiViewer/.worktrees/embedded-ai-cli-terminal/app/build.gradle.kts`

## Commit Details

- **Commit SHA**: ef4434c
- **Branch**: feature/embedded-ai-cli-terminal
- **Message**: `build: add jediterm + pty4j deps for embedded AI CLI terminal`
- **Files changed**: 3
- **Insertions**: 18
- **Deletions**: 1

## Conclusion

Task 1 successfully completed. The project now has build-time access to JediTerm and pty4j libraries with all dependencies resolving correctly. The Kotlin version upgrade to 2.4.0 was necessary but represents a deviation from the brief's exact specification. All subsequent tasks should now be able to import from `com.jediterm.*` and `com.pty4j.*` packages.

---

## Fix pass

The Kotlin toolchain bump to 2.4.0 was an over-correction and has been walked back. The
toolchain stays at 2.0.21 (Compose Multiplatform 1.7.3 is version-locked to Kotlin 2.0.x).

### What was reverted / changed

`gradle/libs.versions.toml`:
```
-kotlin = "2.4.0"
+kotlin = "2.0.21"
```
(jediterm / pty4j version entries and `[libraries]` aliases kept unchanged.)

`app/build.gradle.kts`:
```
 configurations.all {
     resolutionStrategy {
-        force("org.jetbrains.kotlin:kotlin-stdlib:2.4.0")
+        force("org.jetbrains.kotlin:kotlin-stdlib:2.0.21")
     }
 }
+
+// jediterm-core / jediterm-ui 3.74 ship a stray META-INF/*.kotlin_module stamped with
+// Kotlin metadata version 2.4.0 (the jars are pure Java otherwise). The 2.0.21 compiler
+// refuses to read the newer metadata, so skip the version check for these Java-only deps.
+tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
+    compilerOptions {
+        freeCompilerArgs.add("-Xskip-metadata-version-check")
+    }
+}
```

`settings.gradle.kts`: unchanged (cache-redirector repo addition kept).

### Deviation from the brief — extra compiler flag was required

The brief's stated root cause (jediterm-core's POM declares a spurious `kotlin-stdlib:2.4.0`
compile dependency) is real but **incomplete**. Forcing `kotlin-stdlib` down to 2.0.21 alone
still fails to compile:

```
e: .../jediterm-core-3.74.jar!/META-INF/org.jetbrains.jediterm_core.kotlin_module
   Module was compiled with an incompatible version of Kotlin. The binary version of
   its metadata is 2.4.0, expected version is 2.0.0.
e: .../jediterm-ui-3.74.jar!/META-INF/org.jetbrains.jediterm_ui.kotlin_module ...
```

Jar inspection (verified):

| jar | `.class` files | `kotlin/` bytecode refs | stray `.kotlin_module` |
|---|---|---|---|
| jediterm-core:3.74 | 176 (all `com/jediterm/**`) | 0 | `META-INF/org.jetbrains.jediterm_core.kotlin_module` (v2.4.0) |
| jediterm-ui:3.74   | pure Java | 0 | `META-INF/org.jetbrains.jediterm_ui.kotlin_module` (v2.4.0) |
| pty4j:0.13.12      | pure Java | 0 | `META-INF/pty4j.kotlin_module` |

The libs are pure Java, but each ships a leftover `.kotlin_module` descriptor stamped with
metadata version 2.4.0. The Kotlin 2.0.21 compiler scans the classpath, reads that file, and
rejects it on the version check — independent of which `kotlin-stdlib` is resolved. The
`-Xskip-metadata-version-check` free compiler arg is the minimal fix that keeps the 2.0.21
toolchain: it is safe here because the jars contain zero actual Kotlin classes.

### Verification (Kotlin toolchain 2.0.21)

`./gradlew :app:dependencies --configuration runtimeClasspath` — no FAILED markers:
```
+--- org.jetbrains.kotlin:kotlin-stdlib:2.0.21
+--- org.jetbrains.jediterm:jediterm-core:3.74
|    \--- org.jetbrains.kotlin:kotlin-stdlib:2.4.0 -> 2.0.21 (*)
+--- org.jetbrains.jediterm:jediterm-ui:3.74
|    \--- org.jetbrains.kotlin:kotlin-stdlib:2.4.0 -> 2.0.21 (*)
\--- org.jetbrains.pty4j:pty4j:0.13.12
     +--- net.java.dev.jna:jna:5.14.0
     +--- net.java.dev.jna:jna-platform:5.14.0
     \--- org.jetbrains.kotlin:kotlin-stdlib:2.1.21 -> 2.0.21 (*)
```
jediterm-core:3.74, jediterm-ui:3.74, pty4j:0.13.12, jna:5.14.0, jna-platform:5.14.0 all
present; every `kotlin-stdlib` node shows `-> 2.0.21`.

`./gradlew :app:compileKotlin --rerun-tasks` — **BUILD SUCCESSFUL**.

`./gradlew :app:test` — **BUILD SUCCESSFUL**, 826 tests across 137 result files, 0 failures,
0 errors (matches baseline).

### Commit

Amended in place via `git rebase --onto` (interactive rebase unavailable in this
environment) so the pre-existing docs commit on top was preserved rather than lost.

- Task 1 build commit: `ef4434c` → **`67226c9`** — `build: add jediterm + pty4j deps for embedded AI CLI terminal`
- Docs commit replayed on top: `63aaf84` → `62e9d12` (unchanged content)
- `git show --stat 67226c9` → 3 files: `app/build.gradle.kts`, `gradle/libs.versions.toml`, `settings.gradle.kts`
- `grep 2.4.0 gradle/libs.versions.toml` → no match

### Open follow-up

The plan doc (`docs/superpowers/plans/2026-09-05-embedded-ai-cli-terminal.md`, commit
`62e9d12`) still describes the fix as "stdlib force only" and does not mention the required
`-Xskip-metadata-version-check` flag. That doc was out of scope for this fix pass and should
be reconciled separately.
