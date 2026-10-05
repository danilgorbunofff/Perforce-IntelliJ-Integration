# Perforce Integration — user guide

*Status: v0.4. Every p4 operation below is covered by automated tests against a live Helix Core r25.2 server, and the VCS integration by headless tests inside IntelliJ IDEA 2025.3. Compatibility with 2025.3, 2026.1.5 and 2026.2.3 is checked by the JetBrains Plugin Verifier. Still open: a manual click-through of the dialogs (see the release notes). See [why.md](why.md) for the product rationale.*

## Requirements

- A JetBrains IDE (IntelliJ IDEA, PyCharm, GoLand, Rider, …), build 253 (2025.3) or newer. The plugin depends only on `com.intellij.modules.platform` and `com.intellij.modules.vcs`.
- The **`p4` CLI client**. Set its path in the Connection tab (**p4 executable**); the default is `p4` from PATH. (`-Dp4.executable=…` in the IDE VM options still works as the initial default.)
- A reachable Perforce server and workspace, configured the way any `p4` client is: environment variables, `p4 set` (OS registry), and/or a P4CONFIG file.
- **JetBrains' bundled "Perforce Helix Core" plugin must be disabled.** Both plugins register the VCS called `Perforce`. That is deliberate, so existing `.idea/vcs.xml` mappings keep working. The plugin therefore declares itself incompatible with the bundled one, and while that one is enabled the IDE keeps this plugin off and says why on the Plugins page. IntelliJ IDEA 2025.3 bundles it; the 2026.2 Ultimate line no longer does.

## Building and installing

From `scratch-plugin/`:

```bash
./gradlew buildPlugin
```

This produces `build/distributions/perforce-intellij-integration-<version>.zip`. Install it via **Settings → Plugins → ⚙ → Install Plugin from Disk…**. Gradle needs a JDK 21 (`JAVA_HOME`); an IDE's bundled `jbr` folder works. On Windows, if Gradle fails with `Unable to establish loopback connection`, point `TEMP`/`TMP` at a short path such as `C:\Temp`: the JDK's socket selector cannot handle a long temp path.

Other tasks:

| Task | What it does |
|---|---|
| `./gradlew test` | 28 unit tests, plus (with `P4_BIN`) 37 live tests against a throwaway p4d and 9 headless in-IDE tests of the VCS integration. Without `P4_BIN` the live tests are reported as skipped and the in-IDE tests are not run |
| `./gradlew verifyPlugin` | JetBrains Plugin Verifier against IDEA 2025.3, 2026.1.5 and 2026.2.3 |
| `./gradlew runIde` | a sandbox IDE with the plugin installed (bundled Perforce plugin disabled) |

**Live tests:** set `P4_BIN` to a directory that holds `p4` and `p4d` (free from Perforce). Each test starts its own server in rsh mode (`P4PORT=rsh:p4d -r <tmp> -i`). No port is opened and nothing outside a temp dir is touched. Without `P4_BIN` the live tests are skipped.

The tool window **"Perforce P4"** appears docked right, with three tabs: **Changelists**, **Connection**, **Streams**. Settings are **per project**: two open projects never share a connection.

## How the plugin runs p4

Every call runs as `p4 -d <workspace dir> …`. The workspace dir defaults to the project's base directory and is set in the Connection tab. `-d` matters: p4 resolves P4CONFIG from `$PWD`, not from the process's real working directory (verified on r25.2), so without it an IDE would read config from wherever it was launched.

Lists of files go through an argument file (`p4 -x <file> …`, written to the system temp dir, never the workspace). That avoids command-line limits and keeps spaces intact. Every operation costs a constant number of p4 processes, whatever the number of files. File names containing `@ # % *` (e.g. `icon@2x.png`) are escaped the way p4 requires (`icon%402x.png`). `p4 add` and `reconcile` get `-f`, so they accept such names.

Read-only queries are killed after **30 s**. Long operations (sync, submit, shelve, reconcile, revert, resolve, diff, annotate) have no time limit and show progress in the status bar. Sync, reconcile, revert (keep files), diff and annotate can be cancelled there; submit, shelve and revert (discard) cannot, because killing them half-way leaves the changelist in a worse state. p4 never waits on a prompt: its stdin is closed, so it fails with a message instead of hanging. The status area shows at most 500 lines of p4 output (a full sync prints one line per file); run the command in a terminal for the rest.

## Connection tab — "why can't I connect"

Set **p4 executable** and **workspace dir**, then **Save & run diagnosis**. The values are saved for the project and used by every tab, so the report describes exactly what the plugin experiences:

1. **p4 executable and workspace dir**, and the exact command line every call uses. A workspace dir that does not exist is reported as exactly that. (Windows reports it as a failure to start the program, which earlier versions mistook for a missing executable.)
2. **IDE process environment**: the `P4*` variables the IDE itself has, plus any imported override.
3. **`p4 set` from the workspace dir**: p4's own answer, with the source of each value (e.g. `(config 'C:\ws\.p4config')`, `(set)` for the registry, nothing for the environment).
4. **P4CONFIG lookup**: walks up from the workspace dir. p4 reads a config file **only** when `P4CONFIG` names it. If it is unset, p4 reads *no* config file and falls back to built-in defaults, and the report says so.
5. **`p4 info`**: the actual connect attempt. On success it shows server, user, client, host and root. It flags a **client that does not exist** (p4 still exits 0 here), a **client locked to another host**, and a workspace dir outside the client root.
6. **`p4 login -s`**: authentication state.

It ends with a **VERDICT**:
- **READY**
- **NOT READY**: the server answers, but the first failing step is named (e.g. unknown client, host lock, not logged in).
- **NOT CONNECTED**: with a targeted hint (executable not found, workspace dir missing, host does not resolve, port unreachable, SSL server not trusted yet → `p4 trust`, …).

**Import env from P4CONFIG** finds the config file above the workspace dir. It passes that file's `P4PORT`/`P4USER`/`P4CLIENT` to every later `p4` call of this project, as environment variables, for this session. (It does not set `P4CONFIG`.)

### The silent misconfigurations to know

- **A config file p4 never reads.** `p4config.txt` sitting in the workspace does nothing until something sets `P4CONFIG=p4config.txt` (env var or `p4 set`). This is the most common "it works in the terminal but not here" cause.
- **Precedence: P4CONFIG file > environment > `p4 set` > built-in defaults.** When the config file and the environment disagree, the config file wins, silently. The report calls out every such conflict by name.

## Local Changes, commit, rollback and file operations

The plugin is the project's **Perforce** VCS. The platform's own **Local Changes** view, **Commit**, **Rollback**, diff and file operations work on the workspace.

**Which projects are claimed.** A directory that directly holds a P4CONFIG file is a Perforce root: `.p4config`, `p4config.txt`, or the name in the IDE's `P4CONFIG` environment variable. The IDE finds it by walking up from the project. A project that is not claimed gets no Perforce change lists, and the tool window still works. You can also map a directory by hand: **Settings → Version Control → Directory Mappings → Perforce**.

- **Local Changes** lists exactly what `p4 opened` reports for the current client: two p4 calls (`p4 opened`, then one `p4 fstat -Ro` of those files), however large the workspace. There is no directory scan, so a file that is not open in Perforce never appears as changed.
- **Status** comes from the p4 action:

  | p4 action | Shown as |
  |---|---|
  | `add`, `branch`, `import` | **new** |
  | `delete`, `move/delete`, `purge` | **deleted** |
  | `move/add` + `move/delete` | one **renamed** change |
  | unresolved file | **merged with conflicts** |
  | `integrate` | **merge** |
  | anything else (usually `edit`) | **modified** |

  An opened file missing from disk is listed under **locally deleted**.
- **Diff**: the "before" side is the depot content at `#have`, fetched with `p4 print` (binary filetypes are shown as binary). The "after" side is the file on disk.
- **Auto-checkout**: start typing into a read-only workspace file and the IDE offers to make it writable. Accepting runs **`p4 edit`**, so the file is opened in Perforce and appears in Local Changes.
- **Commit** submits **exactly the files you selected, with the message you typed**. p4 can only submit a whole changelist, so the plugin creates a new changelist with your message, moves the selected files into it (`p4 reopen`) and submits it (`p4 submit -c`). Files you did not select stay where they were. Jobs fixed by the changelist the files came from are fixed by the submitted one, so they close as usual. A source changelist left empty is deleted. If p4 refuses the submit (e.g. a file must be resolved first), nothing is lost: the error names the pending changelist that now holds your files and description, ready to submit again. A file that is not open, or an empty message, is refused before anything changes.
- **Rollback** runs **`p4 revert`**: the open state is dropped and the depot content restored, exactly as the IDE's Rollback dialog says. A file deleted from disk comes back the same way. Files opened for add stay on disk, unopened. The non-destructive `revert -k` stays in the tool window as **Revert (keep files)**.
- **Renames and moves in the IDE** (including refactorings) become real **`p4 move`s**, so history follows the file. Files the IDE **creates** are offered for **`p4 add`** (except ones `P4IGNORE` excludes), and files it **deletes** are opened for **delete**. The IDE asks first, per **Settings → Version Control → Confirmation**.

## Changelists tab

Press **Refresh**. It shows only **your client's** pending changelists (`p4 changes -s pending -c <client>` + `p4 opened`, files grouped locally: a constant number of calls, never one per changelist). If the server is unreachable or the client does not exist, the tree and the status area say so. An empty tree always means "nothing pending", never "something failed".

**Lower pane: submitted changelists** touching your client's view (`p4 changes -s submitted -m 200 //<client>/...`), newest first. Click a column header to re-sort (CL sorts numerically). It is re-fetched on every Refresh. Perforce may **renumber** a pending changelist when it is submitted; the table shows the submitted number.

### Operations

Mutating operations run one at a time per project (a double click cannot submit twice). Afterwards they refresh both this tab and the IDE's Local Changes.

The operations are the icons in the toolbar above the tree (hover one for its name). **Revert Changelist** and **Resolve Conflict** are dropdowns; **Edit current file**, **Add current file**, **Ignore file…**, **Reconcile…** and **p4 info** are under **More Actions** (⋮). When the tool window is narrow, the toolbar folds the icons that do not fit into a **»** menu, so none is ever cut off. **Right-click** a changelist or a file for the operations that apply to it (Submit and Shelve are offered only for numbered changelists, so the default changelist's menu has just the two reverts). In the tree, file names are coloured by what is opened for them (add green, edit blue, delete grey), with the folder below the depot in grey. A refresh, which every operation ends with, keeps the changelists you had expanded and the row you had selected.

| Operation | Acts on | What it runs |
|---|---|---|
| Submit… | selected numbered changelist | asks first, then `p4 submit -c <cl>`. If the changelist no longer holds exactly the files you confirmed (something was opened into it after the last Refresh), nothing is submitted and you are asked to Refresh. Not cancellable: a half-finished submit leaves a locked changelist |
| Shelve | selected numbered changelist | `p4 shelve -c <cl>`; if it already has a shelf, asks before replacing it (`shelve -f`) |
| Revert (keep files) | selected changelist (incl. default) | `p4 revert -k -c <cl> //...`: clears the open state and leaves the files on disk **as they are** (edits become unopened local changes; Reconcile finds them again) |
| Revert (discard edits)… | selected changelist (incl. default) | lists the files, asks, then `p4 -x <those files> revert -c <cl>`, which restores depot content of **exactly the listed files**. A file opened after the last Refresh is never touched |
| Edit current file | file in the active editor | `p4 edit <path>` (makes it writable) |
| Add current file | file in the active editor | `p4 add -f <path>`; p4 refuses ignored files and says so |
| Diff | selected file | `p4 diff <file>` |
| Annotate | selected file | `p4 annotate <file>` (not for files opened for add, which have no history yet) |
| Ignore file… | selected file opened for add, else the active editor file | see below |
| Accept theirs… | selected file | checks `p4 resolve -n <file>`, warns that **your edits in this file are discarded**, then `p4 resolve -at <file>` |
| Accept yours… | selected file | checks, confirms, then `p4 resolve -ay <file>` |
| Sync + auto-merge | workspace | `p4 sync`, then `p4 resolve -am`, which merges only files without conflicting chunks; conflicts are listed and left alone |
| Reconcile… | workspace | `p4 reconcile -n -f //...` preview → lists what it found → on confirm opens **exactly those files** (`p4 -x <list> reconcile -f`) |
| p4 info | — | raw `p4 info` |

**Conflict resolution, end to end:** after **Sync + auto-merge**, any file still reported as conflicting (`… 1 conflicting … resolve skipped`) stays opened and must be resolved before submit. Select it, then **Accept theirs…** or **Accept yours…**. Each acts on that one file only.

### Ignore rules (verified on r25.2)

- Ignore rules stop files from being **added** (by `p4 add` and reconcile). They cannot untrack a file already in the depot, so **Ignore file…** refuses such files and says why.
- For a file **opened for add**, Ignore file… first un-adds it with `revert -k` (the file stays on disk), then writes the rule.
- The rule (`/<file name>`, anchored to that directory, so same-named files in subdirectories are not affected) goes into the ignore file p4 actually reads, in the file's directory: the first name in `P4IGNORE` as p4 resolves it (env, `p4 set` or P4CONFIG), else `p4ignore.txt`. It then re-checks with `p4 ignores -i` and reports success or failure.
- With `P4IGNORE` unset, **both** `p4ignore.txt` and `.p4ignore` are honored. Once `P4IGNORE` is set, only the files it names are.
- `p4 add` **refuses** ignored files ("ignored file can't be added."). The override is **`-I`** (`p4 add -I`), *not* `-f`: `-f` permits wildcard characters in names, a common mix-up.

## Streams tab

**Refresh** builds the stream tree from `p4 streams`. Each stream shows its name, type and parent, nested under its parent: `none`-parented mainlines at the top, and streams whose parent is not listed also show top-level. The header shows the current client's stream, or says it is a classic client. Failures are shown in the tree.

## Troubleshooting

| Symptom | First check |
|---|---|
| The plugin shows as disabled, "incompatible with Perforce Helix Core" | Disable JetBrains' bundled **Perforce Helix Core** plugin and restart |
| Nothing connects | Connection tab → Save & run diagnosis; read the VERDICT |
| Works in terminal, not in IDE | Is the **workspace dir** the directory you run `p4` from in the terminal? Then check step 3: which config file p4 read |
| Tree says "Not ready —" | The message names the cause (server unreachable / client does not exist); run the diagnosis |
| Local Changes stays empty | Is the project mapped to Perforce (Settings → Version Control → Directory Mappings)? Is the file actually open (`p4 opened`)? |
| "must resolve" on submit | **Sync + auto-merge**, then Accept theirs/yours on each remaining file |
| A file you want tracked never appears in reconcile | It may be ignored: check `P4IGNORE` in diagnosis step 3 and the ignore files in its directory |

## Current limitations

- One workspace per project: all VCS roots of a project use the project's p4 executable and workspace dir.
- p4 changelists are not mirrored as IDE changelists: Local Changes shows every opened file in the IDE's own list, and Commit always submits through a new changelist carrying the commit message. The tool window shows the real p4 changelists.
- No unshelve, history (log) view or editor-gutter annotate yet. Annotate and diff are in the tool window.
- A file edited outside the IDE without `p4 edit` (writable but unopened) is invisible until **Reconcile…** opens it.
- Output is decoded as UTF-8; on a non-unicode server, non-ASCII file names may display incorrectly.
- Paid licensing (Marketplace product descriptor) is not wired in yet; it needs the product code JetBrains issues for a paid listing.
