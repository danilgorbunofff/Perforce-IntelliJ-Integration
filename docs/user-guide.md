# Perforce IntelliJ Integration — user guide

*Status: v1 skeleton (Day-0 → Day-13 state). All behavior below is verified against Helix Core r25.2 on Windows; see [why.md](why.md) for the product rationale and [day0-gate-report.md](day0-gate-report.md) for the gate evidence.*

## Requirements

- A JetBrains IDE (IntelliJ IDEA, PyCharm, GoLand, Rider, …) — the build targets the platform's `com.intellij.modules.platform` module, so any of them load it.
- The **`p4` CLI client** installed and reachable. Point the plugin at it with the system property `-Dp4.executable=/path/to/p4` (default: `p4` from PATH).
- A reachable Perforce server and workspace, configured the way any `p4` client is: environment variables, `p4 set` (OS registry), and/or a P4CONFIG file. The plugin runs `p4` and inherits the normal resolution order.

## Installing the current build

The scratch build is packaged as `scratch-plugin/out/perforce-intellij-integration-day0.zip` (`lib/p4-gate.jar` inside). Install via **Settings → Plugins → ⚙ → Install Plugin from Disk…**

The tool window **"Perforce P4"** appears docked right, with three tabs: **Changelists**, **Connection**, **Streams**.

## Connection tab — "why can't I connect"

Enter your workspace directory (where P4CONFIG resolution starts), then **Run diagnosis**. It runs the exact commands a terminal would and renders a six-step report:

1. **p4 executable** — which binary will run.
2. **This process's environment** — `P4PORT`, `P4USER`, `P4CLIENT`, `P4CONFIG`, `P4CHARSET` actually visible to the plugin.
3. **`p4 set`** — the OS-level defaults p4 falls back to.
4. **P4CONFIG lookup** — walks up from the workspace dir for the config file. Names matter: p4 reads a config file **only** when `P4CONFIG` names it (env or registry). If `P4CONFIG` is unset, p4 reads *no* config file at all and falls back to built-in defaults — the report says so explicitly.
5. **`p4 info`** — the actual connect attempt, raw output included, exit code shown.
6. **`p4 login -s`** — authentication state (informational: a reachable server may still reject auth).

It ends with a **VERDICT** naming the first failing step and a targeted hint.

**Import env from P4CONFIG** finds the config file and applies its `P4PORT`/`P4USER`/`P4CLIENT` to every subsequent `p4` call the plugin makes.

### The two silent misconfigurations to know

- **A config file p4 never reads.** `p4config.txt` sitting in the workspace does nothing until something sets `P4CONFIG=p4config.txt` (env var, registry, or the plugin's import button). This is the single most common "it works in the terminal but not here" cause.
- **Precedence: P4CONFIG file > env/`p4 set` > built-in defaults.** When the config file and the environment disagree, the config file wins — silently. The diagnosis report calls out every such conflict by name.

*(Both verified against real `p4.exe`: env `P4PORT=1999` + config `1666` → connected via 1666.)*

## Changelists tab

Press **Refresh**. All `p4` calls run off the UI thread; the UI never blocks.

**Upper pane — pending changelists.** Tree of `Change <n> — '<desc>' (k files) by <user>`, default changelist first. The listing is **batched**: one `p4 changes -s pending` + one `p4 opened`, files grouped locally — never one spawn per changelist (the official plugin's freeze pattern).

**Lower pane — submitted changelists.** Table `CL | user | client | description`, sorted newest-first, **click a column header to re-sort** (CL column sorts numerically). Note: Perforce may **renumber** a pending changelist when it is submitted while higher numbers already exist — the table always shows the real submitted number.

### Operations

| Button | Acts on | What it runs |
|---|---|---|
| Submit | selected changelist | `p4 submit -c <cl>` |
| Shelve | selected changelist | `p4 shelve -c <cl>` |
| Revert (-k) | selected changelist | `p4 revert -k -c <cl> //...` — clears open state, **keeps disk contents** |
| Diff | selected file | `p4 diff <file>` — work against the depot revision it was based on |
| Annotate | selected file | `p4 annotate <file>` — submitted history per line |
| Ignore file | selected file | appends the file name to the P4IGNORE file in the file's directory (`p4ignore.txt` by default — r25.2 honors both `p4ignore.txt` and `.p4ignore` when `P4IGNORE` is unset), then re-checks and confirms |
| Reconcile workspace | — | `p4 reconcile -n //...` preview — opens for add what exists locally but not in the depot; **ignored files are skipped automatically** |
| Sync + resolve | — | `p4 sync //...` then `p4 resolve -am //...` |
| Resolve theirs / mine | — | `p4 resolve -at/-ay //...` — picks a side for files `-am` left unresolved |
| p4 info | — | raw `p4 info` |

**Conflict resolution, end to end:** after a sync, a file that must be merged is marked for resolve; `resolve -am` auto-merges everything textually safe and reports the rest (`0 yours + 0 theirs + 0 both + 1 conflicting … resolve skipped`). For those, use **Resolve theirs** or **Resolve mine** — or submit and let Perforce reject with "must resolve" as a reminder.

### Notes on ignore rules (verified on r25.2)

- With `P4IGNORE` unset, **both** `p4ignore.txt` and `.p4ignore` are honored by default.
- Setting `P4IGNORE` names *the* ignore file to use.
- `p4 add` **refuses** ignored files ("ignored file can't be added.").
- The override is **`-I`** (`p4 add -I`), *not* `-f` — `-f` is wildcard-hex escaping, a common mistake.

## Streams tab

**Refresh** builds the stream tree from `p4 streams`: each stream shows name, type, and parent, nested under its parent (`none`-parented mainlines at the top; streams whose parent lives in another depot also show top-level). The current client's stream is marked in the header — for a classic (non-stream) client it says so.

## Troubleshooting

| Symptom | First check |
|---|---|
| Nothing connects | Connection tab → Run diagnosis; read the VERDICT |
| Works in terminal, not in IDE | A P4CONFIG file may exist that p4 only reads when `P4CONFIG` is set; or an env/config conflict (config wins) |
| No changelists listed | Wrong client? Run diagnosis; check `P4CLIENT` in steps 2–3 |
| "must resolve" on submit | Press **Sync + resolve**, then pick a side for the leftovers |
| A file you want tracked never appears in reconcile | It may be ignored — check with the Connection tab's env (`P4IGNORE`) and the ignore files in its directory |

## Current limitations

- This is the v1 scratch build: no VCS provider registration yet (native Local Changes/commit dialog stay dark), no p4 fstat caching, no virtual filesystem integration.
- Long-running `p4` calls are capped at 30 s per process.
- The submitted index is a simple newest-first listing (`p4 changes -s submitted -m 200`), incrementally refreshed on demand — not a background cache yet.
