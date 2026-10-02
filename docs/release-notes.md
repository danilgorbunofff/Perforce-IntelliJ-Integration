# Release notes

## v0.4 (reverification, 2026-10-02)

A full review of v0.3 against a **live** Helix Core r25.2 server found that its VCS provider could not work: it had only ever been compiled and unit-tested. All of it is fixed below, and every fix is now covered by automated tests that run against a real p4d and inside a headless IntelliJ IDEA 2025.3.

### Fixed — the VCS provider (v0.3) did not work

- **Every multi-file p4 call was malformed.** `-x <argfile>` was passed *after* the command name, where p4 reads it as a command flag. `p4 opened -x` means "exclusive locks" ("only supported in a distributed configuration"), and `fstat … -x` is "Invalid option: -x". **Local Changes was always empty**, and commit, rollback, add and delete all failed. Global options now always precede the command.
- **Commit could not submit.** `p4 submit -c N` takes no file arguments, and the commit message was dropped for numbered changelists. Commit now submits exactly the selected files with the typed message: new changelist → `reopen` → `submit -c`. A refused submit leaves the files, with the description, in a named pending changelist.
- **Every diff was empty.** The "before" side read the edited file on disk; Perforce keeps no pristine copy. It is now `p4 print -q <file>#have`, with binary filetypes shown as binary and BOM-aware decoding.
- **The root checker could never load.** It was an application-level extension with a constructor parameter. Now it has a no-arg constructor and treats a directory holding a P4CONFIG file as the root.
- **VCS name clash** with JetBrains' bundled plugin (both register `Perforce`). The name is kept on purpose, so `vcs.xml` mappings carry over, and plugin.xml declares `<incompatible-with>PerforceDirectPlugin`.
- **Dirty-scope refresh lost files** (recursively dirty directories were ignored) and could scan whole depot subtrees. Local Changes now always costs two p4 calls, filtered by the scope.
- **A possible refresh loop.** Argument files were written into the workspace, and each one fired a VFS event that re-ran the change provider. They now go to the system temp dir.
- **Errors were lost on the UI thread.** The error list was returned before the work ran. VCS actions now return p4's real errors, running under modal progress when called on the UI thread.
- **Missing files.** An opened file deleted from disk is now reported as locally deleted, and its rollback restores it. It used to be `revert -k`, which left the file missing.

### Changed

- **Rollback = `p4 revert`** (restores depot content), matching the platform's Rollback dialog. `revert -k` stays in the tool window as **Revert (keep files)**.
- **Plugin name: "Perforce Integration".** The Marketplace rejects new plugin names containing "IntelliJ" (Plugin Verifier rule `TemplateWordInPluginName`, since 2024-03-26). The id is `dev.perforce-intellij-integration`.
- **Build: Gradle + IntelliJ Platform Gradle Plugin 2.19.** This adds `buildPlugin`, `verifyPlugin` (2025.3, 2026.1.5, 2026.2.3: compatible, no internal or experimental API) and `runIde`. `build.ps1` is gone.

### Added

- **Auto-checkout** (`EditFileProvider`): typing into a read-only file runs `p4 edit`.
- **IDE file operations follow into Perforce** (`VcsVFSListener`):
  - a rename or move (including refactorings) becomes `p4 edit -k` + one `p4 -b 2 -x <pairs> move -k`;
  - a deleted file becomes `reconcile -d` (or `revert` for a file only opened for add);
  - a created file becomes `p4 add -f`, except files P4IGNORE excludes.
- **File names with `@ # % *`** (e.g. `icon@2x.png`) work everywhere: escaped for p4, or passed with `-f`. Reconcile's preview used to skip them silently.
- Shelving a changelist that is already shelved asks, then replaces the shelf (`shelve -f`).
- Tool-window operations also refresh the IDE's Local Changes.

### Fixed — tool window and diagnosis

- A missing **workspace dir** was diagnosed as "p4 executable not found": `CreateProcess error=267` matched the substring `error=2`. It is now checked first and named.
- `p4 set -q` values containing parentheses (`C:\Program Files (x86)\…`) were truncated. Only real p4 annotations are stripped now.
- Unparsable records no longer crash a refresh, and any unexpected failure in a background task is shown as a message instead of an IDE error report with a frozen panel.

### Tests

- **26 unit tests** (parsers, argv construction, escaping, change mapping, submit spec, hints).
- **28 live tests** against a throwaway p4d (rsh mode, no port), run when `P4_BIN` is set.
- **9 headless in-IDE tests**: plugin loading, root detection, Local Changes, `#have` diff, commit, rollback, a locally deleted file, auto-checkout, and IDE rename / create / delete.

### Still open

- A manual click-through of the tool window and dialogs in a running IDE (`./gradlew runIde`). The VCS wiring itself is covered by the headless platform tests.
- Paid licensing (product descriptor), which needs the Marketplace product code.

## v0.3 (VCS provider, 2026-10-02)

> **Superseded by v0.4.** The v0.3 VCS provider did not work against a real server; see v0.4's "Fixed" list.

The plugin is now the IDE's Perforce VCS provider: **Local Changes**, the **commit dialog** and **Rollback** are wired to `p4`. Compiled and tested against 2025.3 (94 parser/logic checks, 0 failures). The provider itself has not yet been exercised in a running IDE with a real `p4` — see *Still open*.

### Added

- **Registered as the project VCS** — `<vcs name="Perforce" vcsClass="p4gate.P4Vcs">` plus a `<vcsRootChecker>`, with a new dependency on `com.intellij.modules.vcs`. A project is a Perforce project when a content root holds a `.p4config` (or sits under one); `needsLegacyDefaultMappings()` is off, so a project the plugin does not claim gets no Perforce change lists at all.
- **Local Changes feed** — `p4 opened` (current client only) plus one `p4 fstat -T depotFile,clientFile` call to translate depot paths into local ones. No directory scan: a file you never opened cannot appear as changed, which is the difference that matters on a real workspace. A dirty-scope refresh uses the same two calls, scoped to the dirty paths.
- **Statuses from the p4 action** — `add`/`branch`/`import`/`move/add` → new, `delete`/`move/delete`/`purge` → deleted, `integrate`/`resolve` → merge, everything else (typically `edit`) → modified; an opened file missing from disk → "deleted from filesystem", which is what makes plain `p4 revert` (not `-k`) the right rollback for it.
- **Commit → `p4 submit`** — the selected files are grouped by the changelist p4 holds them open in and submitted per changelist, so one commit dialog can submit several changelists. A numbered changelist is submitted with `-c <cl>`; the default changelist takes the commit message through `-d`. Files that are not open are reported as an error naming them, instead of being skipped silently.
- **Add / Delete** — "add to VCS" runs `p4 add`; a file removed from disk is recorded with `p4 reconcile -d`, because `p4 delete` needs the file to still exist.
- **Rollback → `p4 revert -k`** — drops the open state and leaves the file on disk, the non-destructive form. The destructive `p4 revert` (restores depot content) stays in the tool window's **Revert (discard edits)…**, behind a confirmation. "Roll back modified without checkout" is deliberately a no-op: Perforce has no modified-but-unopened state.
- **Revisions** — the before-side is `#have` and the after-side is the workspace file, so the IDE's own differ shows your edits against the synced copy with no `p4 print` round trip.

### Changed

- `p4 -x` argument-file plumbing extracted to `P4Args` and shared by the provider, commit, rollback and reconcile; written with LF line endings only and deleted in a `finally`.
- VCS actions run inline on the platform's worker thread when that is where the platform called them, so the errors returned to the commit dialog are the real p4 errors; a call that arrives on the UI thread is queued instead, keeping the "no `p4` process on the UI thread" rule intact.
- `build.ps1` now runs **94** checks (was 51): the new ones cover action→status mapping, revision comparison, depot↔local mapping with case and separator differences, `p4 opened` records, change→path extraction, and the argument file.

### Still open

- Gradle build with `verifyPlugin` / `signPlugin`.
- A GUI pass of the tool window **and** the new VCS integration in a running IDE with `p4` installed.
- No auto-checkout: editing a read-only file does not run `p4 edit` (`EditFileProvider` is not implemented), and a writable-but-unopened file — one left behind by **Revert (keep files)**, say — does not appear in Local Changes until you run **Reconcile…**.

## v0.2 (post-review, 2026-10-01)

Fixes from a strict review; every p4-side behaviour re-verified live against r25.2. Not yet published.

### Fixed — could lose work or mislead

- **Accept theirs / yours** ran `p4 resolve -at|-ay //...` on the whole workspace without asking — one click discarded local edits in every conflicting file. Now: selected file only, `resolve -n` check, confirmation that names what is discarded.
- **Pending tree** listed every pending changelist on the server (all users, all clients). Now only the current client's.
- **Connection diagnosis** described one directory while every `p4` call ran in the IDE's own directory — and p4 resolves P4CONFIG from `$PWD`, not the process cwd. Now every call runs `p4 -d <workspace dir>`, configured per project in the Connection tab, and the diagnosis uses the same runner.
- **Verdict** said "connected" for a client that does not exist (`p4 info` exits 0). Now READY / NOT READY / NOT CONNECTED, with checks for unknown client, host-locked client, workspace outside the client root, and failed `p4 login -s`.
- **Refresh** swallowed errors: an unreachable server looked like an empty default changelist. Errors are now shown in the tree and status area.
- **30 s timeout** never fired (output was drained before the timer started). Now a real timeout for queries, no timeout for long operations, cancellation from the progress bar, child processes killed, stdin closed so p4 cannot hang on a prompt.
- **Ignore file** broke on paths with spaces (`p4 where` output split on spaces), ignored `P4IGNORE` from env/registry/config, and claimed to stop already-opened files from being added. Now uses tagged `where`, the ignore file p4 actually reads, and refuses (or first un-adds) files that ignore rules cannot affect.
- **Missing `p4` executable** on Windows produced a generic hint. Hints added for it, DNS, refused port, SSL trust (`p4 trust`), host lock, unknown client.

### Added

- **Edit current file / Add current file** — the plugin can now start a change.
- **Reconcile…** opens exactly the previewed files (was preview-only).
- **Revert (discard edits)…** with confirmation; **Submit…** asks first.
- Per-project settings (p4 executable, workspace dir); mutating operations are exclusive per project.

### Changed

- All p4 data is read as tagged JSON (`p4 -ztag -Mj`) instead of regex over human-readable output; full multi-line descriptions are fetched (`-l`).
- Submitted index is scoped to the client's view.
- Platform background tasks and JB UI components; tool window is `DumbAware`; `since-build="253"`.
- `build.ps1` takes `-IdeHome` / `IDEA_HOME` (no hard-coded path) and runs 51 fixture-based parser/diagnosis checks; the build fails if any fail.

### Still open

- Gradle build with `verifyPlugin` / `signPlugin`.
- A GUI pass of the new dialogs in a running IDE.

*(The VCS-provider registration this section listed was done in v0.3.)*

## v0.1 (Day-0 → Day-13 skeleton)

First internally verified build. Not yet published to the Marketplace.

### Added

- **Tool window "Perforce P4"** with three tabs: Changelists, Connection, Streams.
- **Connect-and-explain**: six-step diagnosis (executable, process env, `p4 set`, P4CONFIG lookup, `p4 info`, `p4 login -s`) with verdict, targeted hints, silent-misconfiguration warnings, and one-click import of a P4CONFIG file's connection settings.
- **Pending changelists** tree, batched listing (2 `p4` spawns total, never one per changelist).
- **Submitted changelists** index — newest first, sortable table columns (CL sorts numerically).
- **Operations**: submit, shelve, `revert -k`, diff, annotate, reconcile preview (`-n`, honors ignore rules), sync + `resolve -am`, `resolve -at/-ay`.
- **Ignore file** action: writes the P4IGNORE rule, verifies it took effect (`p4 ignores -i`).
- **Streams** tab: parent-hierarchy tree, current client's stream marked.

### Verified against

- Helix Core server `P4D/NTX64/2025.2/3051938` (r25.2), Windows.
- Empirical facts captured: P4CONFIG resolution and precedence; `p4 opened` line format; renumbering-on-submit; default ignore filenames (`p4ignore.txt`, `.p4ignore`); `p4 add` refusal and `-I` override; stream spec requirements (`Parent:` field, depth-1 depot naming).

### Known limitations

- Not registered as the IDE's project VCS provider (v2). *Done in v0.3.*
- 30 s per-process timeout; no fstat caching; no background refresh scheduler.
