# Release notes

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

- VCS-provider registration (native Local Changes / commit dialog).
- Gradle build with `verifyPlugin` / `signPlugin`.
- A GUI pass of the new dialogs in a running IDE.

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

- Not registered as the IDE's project VCS provider (v2).
- 30 s per-process timeout; no fstat caching; no background refresh scheduler.
