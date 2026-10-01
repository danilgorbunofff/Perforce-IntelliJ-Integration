# Perforce IntelliJ Integration — user guide

*Status: v0.2 skeleton (post-review). The p4 behaviour below is verified against Helix Core r25.2 on Windows; the IDE dialogs are compiled against 2025.3 but have not yet had a GUI pass. See [why.md](why.md) for the product rationale and [day0-gate-report.md](day0-gate-report.md) for the gate evidence.*

## Requirements

- A JetBrains IDE (IntelliJ IDEA, PyCharm, GoLand, Rider, …), build 253 (2025.3) or newer — the plugin depends only on `com.intellij.modules.platform`.
- The **`p4` CLI client**. Set its path in the Connection tab (**p4 executable**); default: `p4` from PATH. (`-Dp4.executable=…` in the IDE VM options still works as the initial default.)
- A reachable Perforce server and workspace, configured the way any `p4` client is: environment variables, `p4 set` (OS registry), and/or a P4CONFIG file.

## Installing the current build

Build it: `powershell -File scratch-plugin/build.ps1 -IdeHome <unpacked IntelliJ 2025.3>` (or set `IDEA_HOME`). The build compiles, runs the parser tests, and writes `scratch-plugin/out/perforce-intellij-integration-day0.zip`. Install via **Settings → Plugins → ⚙ → Install Plugin from Disk…**

The tool window **"Perforce P4"** appears docked right, with three tabs: **Changelists**, **Connection**, **Streams**. Settings are **per project**: two open projects never share a connection.

## How the plugin runs p4

Every call runs as `p4 -d <workspace dir> …`. The workspace dir defaults to the project's base directory and is set in the Connection tab. `-d` matters: p4 resolves P4CONFIG from `$PWD`, not from the process's real working directory (verified on r25.2), so without it an IDE would read config from wherever it was launched.

Read-only queries are killed after **30 s**; long operations (sync, submit, shelve, reconcile, revert, diff, annotate) have no time limit and show progress in the status bar. Sync, reconcile, revert (keep files), diff and annotate can be cancelled there; submit, shelve and revert (discard) cannot, because killing them half-way leaves the changelist in a worse state. p4 never waits on a prompt: its stdin is closed, so it fails with a message instead of hanging.

## Connection tab — "why can't I connect"

Set **p4 executable** and **workspace dir**, then **Save & run diagnosis**. The values are saved for the project and used by every tab, so the report describes exactly what the plugin experiences:

1. **p4 executable and workspace dir**, and the exact command line every call uses.
2. **IDE process environment** — the `P4*` variables the IDE itself has, plus any imported override.
3. **`p4 set` from the workspace dir** — p4's own answer, with the source of each value (e.g. `(config 'C:\ws\.p4config')`, `(set)` for the registry, nothing for the environment).
4. **P4CONFIG lookup** — walks up from the workspace dir. p4 reads a config file **only** when `P4CONFIG` names it; if it is unset, p4 reads *no* config file and falls back to built-in defaults — the report says so.
5. **`p4 info`** — the actual connect attempt. On success: server, user, client, host, root. Flags a **client that does not exist** (p4 still exits 0 here), a **client locked to another host**, and a workspace dir outside the client root.
6. **`p4 login -s`** — authentication state.

It ends with a **VERDICT**: **READY**, **NOT READY** (server answers, but the first failing step is named — e.g. unknown client, host lock, not logged in) or **NOT CONNECTED** (with a targeted hint: executable not found, host does not resolve, port unreachable, SSL server not trusted yet → `p4 trust`, …).

**Import env from P4CONFIG** finds the config file above the workspace dir and passes its `P4PORT`/`P4USER`/`P4CLIENT` to every subsequent `p4` call of this project, as environment variables, for this session. (It does not set `P4CONFIG`.)

### The silent misconfigurations to know

- **A config file p4 never reads.** `p4config.txt` sitting in the workspace does nothing until something sets `P4CONFIG=p4config.txt` (env var or `p4 set`). This is the most common "it works in the terminal but not here" cause.
- **Precedence: P4CONFIG file > environment > `p4 set` > built-in defaults.** When the config file and the environment disagree, the config file wins — silently. The report calls out every such conflict by name.

## Changelists tab

Press **Refresh**. It shows only **your client's** pending changelists (`p4 changes -s pending -c <client>` + `p4 opened`, files grouped locally — a constant number of calls, never one per changelist). If the server is unreachable or the client does not exist, the tree and the status area say so — an empty tree always means "nothing pending", never "something failed".

**Lower pane — submitted changelists** touching your client's view (`p4 changes -s submitted -m 200 //<client>/...`), newest first; click a column header to re-sort (CL sorts numerically). It is re-fetched on every Refresh. Perforce may **renumber** a pending changelist when it is submitted; the table shows the submitted number.

### Operations

Mutating operations run one at a time per project (a double click cannot submit twice) and refresh the tree afterwards.

| Button | Acts on | What it runs |
|---|---|---|
| Submit… | selected numbered changelist | asks first, then `p4 submit -c <cl>` (not cancellable — a half-finished submit leaves a locked changelist) |
| Shelve | selected numbered changelist | `p4 shelve -c <cl>` |
| Revert (keep files) | selected changelist (incl. default) | `p4 revert -k -c <cl> //...` — clears the open state, leaves the files on disk **as they are** (edits become unopened local changes; Reconcile finds them again) |
| Revert (discard edits)… | selected changelist (incl. default) | lists the files, asks, then `p4 revert -c <cl> //...` — restores depot content |
| Edit current file | file in the active editor | `p4 edit <path>` (makes it writable) |
| Add current file | file in the active editor | `p4 add <path>` — p4 refuses ignored files and says so |
| Diff | selected file | `p4 diff <file>` |
| Annotate | selected file | `p4 annotate <file>` (not for files opened for add — they have no history yet) |
| Ignore file… | selected file opened for add, else the active editor file | see below |
| Accept theirs… | selected file | checks `p4 resolve -n <file>`, warns that **your edits in this file are discarded**, then `p4 resolve -at <file>` |
| Accept yours… | selected file | checks, confirms, then `p4 resolve -ay <file>` |
| Sync + auto-merge | workspace | `p4 sync`, then `p4 resolve -am` — merges only files without conflicting chunks; conflicts are listed and left alone |
| Reconcile… | workspace | `p4 reconcile -n //...` preview → lists what it found → on confirm opens **exactly those files** (`p4 -x <list> reconcile`) |
| p4 info | — | raw `p4 info` |

**Conflict resolution, end to end:** after **Sync + auto-merge**, any file still reported as conflicting (`… 1 conflicting … resolve skipped`) stays opened and must be resolved before submit. Select it, then **Accept theirs…** or **Accept yours…** — each acts on that one file only.

### Ignore rules (verified on r25.2)

- Ignore rules stop files from being **added** (by `p4 add` and reconcile). They cannot untrack a file already in the depot — **Ignore file…** refuses such files and says why.
- For a file **opened for add**, Ignore file… first un-adds it with `revert -k` (the file stays on disk), then writes the rule.
- The rule (the file's name) goes into the ignore file p4 actually reads, in the file's directory: the first name in `P4IGNORE` as p4 resolves it (env, `p4 set` or P4CONFIG), else `p4ignore.txt`. It then re-checks with `p4 ignores -i` and reports success or failure.
- With `P4IGNORE` unset, **both** `p4ignore.txt` and `.p4ignore` are honored. Once `P4IGNORE` is set, only the files it names are.
- `p4 add` **refuses** ignored files ("ignored file can't be added."). The override is **`-I`** (`p4 add -I`), *not* `-f` — `-f` is wildcard-hex escaping, a common mistake.

## Streams tab

**Refresh** builds the stream tree from `p4 streams`: each stream shows name, type and parent, nested under its parent (`none`-parented mainlines at the top; streams whose parent is not listed also show top-level). The header shows the current client's stream, or says it is a classic client. Failures are shown in the tree.

## Troubleshooting

| Symptom | First check |
|---|---|
| Nothing connects | Connection tab → Save & run diagnosis; read the VERDICT |
| Works in terminal, not in IDE | Is the **workspace dir** the directory you run `p4` from in the terminal? Then check step 3: which config file p4 read |
| Tree says NOT READY | The message names the cause (server unreachable / client does not exist); run the diagnosis |
| "must resolve" on submit | **Sync + auto-merge**, then Accept theirs/yours on each remaining file |
| A file you want tracked never appears in reconcile | It may be ignored — check `P4IGNORE` in diagnosis step 3 and the ignore files in its directory |

## Current limitations

- Not registered as the IDE's project VCS provider yet: the native Local Changes view, commit dialog and gutter diff stay dark; edits are not checked out automatically when you type.
- No fstat cache, no background refresh — Refresh is on demand.
- No unshelve, new-changelist, move/rename or delete operations yet.
- Output is decoded as UTF-8; on a non-unicode server, non-ASCII file names may display incorrectly.
- Built with a PowerShell + `javac` script, not Gradle: no `verifyPlugin`, no signing yet.
