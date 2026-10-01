# Release notes

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
