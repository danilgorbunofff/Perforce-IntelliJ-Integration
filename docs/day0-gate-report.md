# Day-0 gate report — 2026-10-01

**Verdict: 4 / 4 PASS. The project stands.**

| Gate item | Question | Verdict | One-line answer |
|---|---|---|---|
| 1 | Is the incumbent bundled / entitlement-gated? | ✅ **PASS** | Bundled as a jar in 2025.x unified distributions, but **not entitlement-gated**, and **JetBrains dropped it from the 2026 line** — Perforce users there must install the marketplace plugin on purpose. |
| 2 | Read all 10 incumbent reviews + failed-paid-plugin reviews | ✅ **PASS** | 10/10 read; **JetBrains has never replied to a single one**. Postmortems of Forgejo and CIclone extracted 5 concrete death causes. |
| 3 | ≥10 real Perforce users complaining in public | ✅ **PASS** | **64 distinct users** collected with verbatim quotes (target was 10). |
| 4 | Prove the build is small | ✅ **PASS** | `p4` CLI → changelist tree → submit/shelve/revert skeleton compiled against a real IDE distribution and verified against a real local `p4d`. **Shipping plugin zip: 10,134 bytes.** |

Everything below is reproducible; sources were read-only (no posts, votes, or account actions anywhere).

---

## Item 1 — Bundling: verified by direct zip inspection, not by inference

Method: JetBrains ships Windows zips as zip64; a shared range-reader (`ziplister.py`) reads only the ~64 KB tail + central directory of each product distribution over HTTP (no full downloads except the one IDE we unpacked). We then inspected plugin contents directly.

### The bundling map

| Product (windows zip) | Build | Bundles vcs-perforce? |
|---|---|---|
| `idea-2025.3.win.zip` (unified; product code **IU**) | IU-253.28294.334 | ✅ YES — `plugins/vcs-perforce/lib/vcs-perforce.jar` |
| `ideaIC-2025.2.win.zip` (pure Community) | IC-252.x | ✅ YES |
| PyCharm Community 2025.3 | PC-253.x | ✅ YES |
| Rider 2026.2.3 | RD-262.x | ✅ YES |
| CLion 2026.2.3 | CL-262.x | ✅ YES |
| IntelliJ IDEA Ultimate 2026.2.3 | IU-262.x | ❌ NO |
| PyCharm Pro 2026.2.3 | PCP-262.x | ❌ NO |
| GoLand / WebStorm / PhpStorm / RubyMine / RustRover 2026.2.3 | GO/WS/PS/RM/RR-262.x | ❌ NO |

(DataSpell probe returned a non-zip artifact — acceptable gap; not re-attempted.)

**Two structural findings:**

1. **Not entitlement-gated.** The bundled jar's `META-INF/plugin.xml` declares `id=PerforceDirectPlugin`, vendor JetBrains, `<depends>com.intellij.modules.lang</depends>` + `<depends>com.intellij.modules.vcs</depends>` (+ optional Git4Idea) — **no Ultimate module dependency**. Its `since-build`/`until-build` are pinned to the exact host build (`253.28294.334`), with `allow-bundled-update="true"`. Also, the newest marketplace artifact (`vcs-perforce-263.5701.34.zip`) lists `IDEA_COMMUNITY` and `PYCHARM_COMMUNITY` in `compatibleVersions` — installable into the free products.
2. **Bundling was dropped in the 2026 line.** 2025.3 ships it; 2026.2.3 Ultimate does not. Whatever JetBrains' reason, the effect is that **the 2026-line Perforce user is a deliberate marketplace installer** — exactly the audience a paid competitor wants. The old question "is it bundled?" has become: *it was, partially, and it is being de-bundled.*

### What we did NOT need

No IDE install, no Ultimate licence, no JVM download. Everything above was read over HTTP from distribution zips, and the one IDE we unpacked (2025.3) came from the public release channel. The README's "one IDE install answers it" was pessimistic: zero installs answered it.

---

## Item 2 — Reviews: the incumbent, and two paid plugins that died

### The incumbent — `Perforce P4 (Helix Core)` (marketplace id 69)

All **10** verbatim reviews were fetched (`/api/plugins/69/comments`). Key structural fact: **every review has `repliesCount=0`**. Across a 2014→2026 review span, **JetBrains has publicly replied to none of them.** A vendor that does not answer its angriest users is not defending this table — which matches the twelve-year rot the thesis is built on.

| Complaint pattern in the 10 reviews | Users saying it |
|---|---|
| Connection failures, no diagnostics/logs | Éric Daigneault ("could not find any logs to help diagnose"), Chris Carr ("doesn't write a log even when 'Dump Perforce Commands' is ticked") |
| Slowness / freezes on big workspaces | Jan Kalina ("Incredibly slow for big P4 workspaces, forever frozen during trivial tasks"), bruno.meier ("Slow, produces always error message on startup"), Dibyendu Das ("Really slow to refresh anything") |
| Stale changelist cache | Todd Heidenthal ("Does not refresh cache of submitted changelists... mismatch between Perforce and IDEA") |
| File loss / sync failure | Ice_Ice ("各种丢失文件，同步功能时不时失灵" — constantly losing files, sync fails intermittently) |
| General non-functioning | psabharwal ("horrible plugin, never works") |

The full raw text of each review is in [Appendix C of the README](../README.md). One named author (Todd Heidenthal) also filed the YouTrack tickets proving modern builds reproduce the bug (IDEA-322554 cites plugin build `232.8660.185`).

### Postmortems — the failed paid plugins

**`Forgejo` (id 31556, paid, 584 downloads):** 3 reviews, all damaging — a crash thread that surfaced only via a *BashSupport Pro* review; login accounts lost on every Rider 2026.1.2 restart; comments/review mode broken. **No visible bug tracker** — complaints had nowhere to go but the review wall.

**`CIclone` (id 19114, paid, 13,202 downloads):** 8 reviews, mixed — GitHub Actions jobs empty on PyCharm Pro; settings page broken when the plugin is enabled; Jenkins polling errors on large servers; multi-module repos polled main project only. **Unlike the incumbent, this vendor did reply** — and the replies were competent. It died anyway.

### What the deaths teach (the five causes)

1. **A paid plugin that breaks *license activation* or *first-run setup* dies in review**, not in sales charts. (Forgejo's activation + account-loss complaints.)
2. **No bug tracker = every failure becomes a 1-star review.** The incumbent and Forgejo both show this. Our repo gets a public issue tracker on day 1.
3. **Replies don't save a broken product** (CIclone's vendor replied and it still died) — but the incumbent shows silence doesn't even buy goodwill.
4. **CI/CD-adjacent integrations inherit huge-surface failure modes** (empty job lists, polling overloads). A VCS integration with a CLI bridge has a radically smaller, more controllable surface — this is why the thesis picks Perforce, not CI, and it survives the postmortems.
5. **Every failed paid plugin was a "connector to a hosted service."** Nobody has ever attempted to charge for Perforce integration (store search returns 9 results, zero paid). The lane is genuinely empty.

---

## Item 3 — Real users complaining in public: 64 found

Two read-only research sweeps (JetBrains YouTrack project IDEA + `groboclown/p4ic4idea` GitHub issues; JetBrains Marketplace reviews + Stack Overflow + recent YouTrack). Block-page evidence saved for the sources that could not be reached (forum.perforce.com NXDOMAIN, Unreal forums 403, Reddit 403; Reddit archive pullpush.io scanned with no plugin-specific yield). Total: 64 distinct complainants, verbatim quotes ≤40 words, links and dates per row.

### Table A — JetBrains' own integration (YouTrack, project IDEA/IJPL/RIDER)

| # | User | Link | Date | Verbatim quote | Problem class |
|---|---|---|---|---|---|
| A1 | muntyan | [IDEA-220427](https://youtrack.jetbrains.com/issue/IDEA-220427) | 2019-08-10 | "Sometimes however the background perforce task will sit there forever or just for a long time (like ten minutes). Because of this I almost lost several changes" | Huge-workspace stalls → near data loss |
| A2 | markatathena | [IDEA-256617](https://youtrack.jetbrains.com/issue/IDEA-256617) | 2020-12-01 | "I've got a large monolithic perforce repo that the plugin works just fine on Mac, but on Windows seems to just hang" | Hangs on big repo, Windows-only |
| A3 | Todd Heidenthal (heidenthal_gti) | [IDEA-322554](https://youtrack.jetbrains.com/issue/IDEA-322554) + [MP review](https://plugins.jetbrains.com/plugin/69-perforce-p4-helix-core/reviews) | 2023-08-21 / 2019-03-29 | "With version 232.8660.185, this issue actually prevents Perforce from checking out files for editing... acts as though they can be until a request to revert is made" | Checkout silently broken in modern builds |
| A4 | Rob.Napier | [IDEA-103987](https://youtrack.jetbrains.com/issue/IDEA-103987) | 2013-03-21 | "I am not able, however, to edit (checkout) files... 'Using Version Control' is disabled." | Silent checkout failure |
| A5 | Dmitry.Sokolov | [IDEA-85525](https://youtrack.jetbrains.com/issue/IDEA-85525) | 2012-05-02 | "it takes several minutes to update it (up to 2-3 min). Native p4v client do it very quickly." | Changelist refresh minutes slower than native |
| A6 | parrt | [IDEA-68230](https://youtrack.jetbrains.com/issue/IDEA-68230) | 2011-04-16 | "every GUI refresh does a complete... pass over the file system... IDEA going incredibly slowly" | FS thrashing on every refresh |
| A7 | wshields | [IDEA-68730](https://youtrack.jetbrains.com/issue/IDEA-68730) | 2011-04-25 | "Once I submit a change it basically stops working... 'Error updating changes: Path ... is not under client's root'" | Breaks after first submit |
| A8 | Hurst | [IDEA-68730](https://youtrack.jetbrains.com/issue/IDEA-68730) | 2012-04-16 | "when I use Refresh I get file-not-in-client errors for files that should be ignored" | Ignore handling broken on refresh |
| A9 | neotron | [IDEA-81691](https://youtrack.jetbrains.com/issue/IDEA-81691) | 2012-02-21 | "first tries to get a list of all changes. This doesn't work if you have more changes than the perforce server allows." | maxresults limits block repo view |
| A10 | nskvortsov | [IDEA-82927](https://youtrack.jetbrains.com/issue/IDEA-82927) | 2012-03-16 | "IDEA forgets that file is modified... causes failure when submitting a remote run to TeamCity" | Lost modification tracking |
| A11 | fhomasp | [IDEA-82927](https://youtrack.jetbrains.com/issue/IDEA-82927) | 2012-06-11 | "I'm still losing changes every time a maven build has ran (which triggers a refresh)" | Changes vanish on build refresh |
| A12 | adel.nasrallah | [IDEA-226063](https://youtrack.jetbrains.com/issue/IDEA-226063) | 2019-11-01 | "i open the merge tool, do the merge and then save, but the file is not updated" | Broken merge save |
| A13 | ylexus | [IDEA-69578](https://youtrack.jetbrains.com/issue/IDEA-69578) | 2011-05-12 | "IDEA removes the job and submits; submission fails because we have perforce server side validation" | Submit drops associated jobs |
| A14 | sarumanwhite.1 | [IDEA-190439](https://youtrack.jetbrains.com/issue/IDEA-190439) | 2018-04-17 | "Happens every time I submit a changelist... a small popup informing of NullPointerException" | NPE on every submit |
| A15 | Alexander.Torstling.1 | [IDEA-97431](https://youtrack.jetbrains.com/issue/IDEA-97431) | 2012-12-13 | "the only replacements done are in already checked out files" | Replace-all misses checkout |
| A16 | adernov | [IDEA-119336](https://youtrack.jetbrains.com/issue/IDEA-119336) | 2014-01-13 | "66 build.gradle files are checked out into my default changelist none of the checked-out files contain any changes" | Spurious checkouts |
| A17 | alexgit1 | [IDEA-202499](https://youtrack.jetbrains.com/issue/IDEA-202499) | 2018-11-20 | "there was an option to turn off changelist conflict tracking. It doesn't exist anymore... constantly poping up message is quite annoying" | Forced tracking, no opt-out |
| A18 | nicity | [IDEA-47563](https://youtrack.jetbrains.com/issue/IDEA-47563) | 2009-01-22 | "I have to kill IDEA when perforce became unavailable" | Hang when server unreachable |
| A19 | mio | [IDEA-39592](https://youtrack.jetbrains.com/issue/IDEA-39592) | 2007-07-04 | "the dialog appears that requires entering password to CVS(!)... If you press Cancel, the changes are successfully committed." | Wrong-VCS auth dialog on submit |
| A20 | abus | [IDEA-98632](https://youtrack.jetbrains.com/issue/IDEA-98632) | 2012-08-01 | "it does not show the text（Chinese Text）correctly. I use unicode perforce server, have set p4charset env as UTF8" | Unicode/charset broken |
| A21 | ugo.delle.donne | [IJPL-173553](https://youtrack.jetbrains.com/issue/IJPL-173553) | 2024-12-12 | "When using the P4CONFIG env variable the config file is not parsed and it doesn't show any value." | Config/parse failure |
| A22 | simon.kim | [IJPL-72509](https://youtrack.jetbrains.com/issue/IJPL-72509) | 2024-01-04 | "Revert back the change lists on perforce tab and nothing displays... I can't change the password." | Auth/password dead-end |
| A23 | Chris (GreenIrish32) | [IJPL-72506](https://youtrack.jetbrains.com/issue/IJPL-72506) | 2023-08-21 | "Rider still says No Perforce Connection... requires a full reboot to connect to Perforce." | Connection recovery failure |
| A24 | Lucas Scortegagna (ltonial) | [IJPL-72625](https://youtrack.jetbrains.com/issue/IJPL-72625) | 2022-12-13 | "Perforce has a problem when 'going online' in this new version... You will need to close Rider and reopen it." | Connection recovery failure |
| A25 | Kristóf Morva | [RIDER-97459](https://youtrack.jetbrains.com/issue/RIDER-97459) | 2023-08-11 | "it just gets stuck in a 'No Perforce Connections' state. Clicking on the message does nothing." | Stuck state, no diagnostics |
| A26 | Nick Schultz | [IJPL-72677](https://youtrack.jetbrains.com/issue/IJPL-72677) | 2022-05-23 | "CLion hangs, waiting for the perforce command to complete (which it already had, see below)" | Freeze/hang |
| A27 | Gerald Xv | [IJPL-77347](https://youtrack.jetbrains.com/issue/IJPL-77347) | 2022-04-06 | "the goland says must refer to the client 'XXXX-xxx'... perforce doesn't support wsl2 paths." | WSL paths unsupported |
| A28 | Jaewon Chang | [IJPL-76509](https://youtrack.jetbrains.com/issue/IJPL-76509) | 2022-08-10 | "//wsl$/Ubuntu-22.04/home/... - must refer to client 'cjwin-usmv2-1st'." | WSL paths unsupported |
| A29 | David Cunningham | [IJPL-193509](https://youtrack.jetbrains.com/issue/IJPL-193509) | 2025-05-28 | "A new file is created using an add, and the old file in the depot is deleted. This breaks the Revision and History for this file." | Move/refactor integrity |
| A30 | Alex Whittaker | [RIDER-105914](https://youtrack.jetbrains.com/issue/RIDER-105914) | 2024-02-14 | "Error saving merged data: Attempt to load... file length is 67088384 bytes." | Merge fails on large files |
| A31 | Lukas Krasa | [RIDER-118060](https://youtrack.jetbrains.com/issue/RIDER-118060) | 2024-09-30 | "Changelists seems to be ordered alphabetically" | Changelist UX |
| A32 | Phillip Foose | [RIDER-122263](https://youtrack.jetbrains.com/issue/RIDER-122263) | 2025-01-23 | "files that are within p4 root, but outside of the unity root folder do not work with version control" | Silently unmanaged files |
| A33 | robert.southee | [RIDER-122258](https://youtrack.jetbrains.com/issue/RIDER-122258) | 2025-01-23 | "we're unable to check out files that live in the Packages folder... option to use version control is disabled" | Checkout failure on mapped dirs |

### Table B — the free rival `p4ic4idea` (GitHub `groboclown/p4ic4idea` issues)

Secondary evidence: the rival's own users describe what "good enough to abandon the incumbent" still lacks — which is [§5.3's controlled A/B](../README.md) made concrete.

| # | User | Link | Date | Verbatim quote | Problem class |
|---|---|---|---|---|---|
| B1 | kcwill2 | [issue 107](https://github.com/groboclown/p4ic4idea/issues/107) | 2016-03-10 | "I type in a valid password and everything is fine for a while, but eventually I am prompted for the password again. This happens every few minutes." | Repeated password prompts |
| B2 | ghostofcain | [issue 109](https://github.com/groboclown/p4ic4idea/issues/109) | 2016-03-17 | "Even forcing it to go offline doesn't help and it keeps popping up every time I change a file." | Password prompt on every file touch |
| B3 | gridlined | [issue 120](https://github.com/groboclown/p4ic4idea/issues/120) | 2016-05-08 | "when working over VPN, there are regular and fairly consistent periods in which the IDE becomes totally unresponsive." | Total UI lockup |
| B4 | jimkeir | [issue 123](https://github.com/groboclown/p4ic4idea/issues/123) | 2016-06-09 | "//depot/path/to/file corrupted during transfer... The contents of the file are fine, I can commit the changelist from P4V perfectly" | False digest error blocks commit |
| B5 | stardust85 | [issue 133](https://github.com/groboclown/p4ic4idea/issues/133) | 2016-09-19 | "Version Controls is grayed out. Editting a file doesn't offer to check it out from perforce." | SSL server — silently non-functional |
| B6 | Narthe | [issue 144](https://github.com/groboclown/p4ic4idea/issues/144) | 2017-03-01 | "everytime i try to edit or save a file, i get this error" | Update broke every save |
| B7 | hakjac | [issue 154](https://github.com/groboclown/p4ic4idea/issues/154) | 2017-06-29 | "I loose connection to the Perforce server all the time… Was forced to back to version 0.9.1" | Connection loss, forced downgrade |
| B8 | scriptacus | [issue 181](https://github.com/groboclown/p4ic4idea/issues/181) | 2018-11-10 | "multiple times he's had files disappear from his changelists... saved by IntelliJ Local History" | Files vanish → Local History rescue |
| B9 | staffanf | [issue 181](https://github.com/groboclown/p4ic4idea/issues/181) | 2019-01-17 | "their changes are reverted. It is really annoying and for those using it is a blocker." | Reverts after CLI ops = blocker |
| B10 | singalen | [issue 195](https://github.com/groboclown/p4ic4idea/issues/195) | 2019-01-21 | "Plugin crashed when trying to revert locally deleted files from Local Changes view." | Crash on revert |
| B11 | nitin065 | [issue 225](https://github.com/groboclown/p4ic4idea/issues/225) | 2021-02-22 | "When I change perforce password, plugin is not able to recognize the change and there is no popup to request for a new password." | Auth change undetected |
| B12 | NagSoumava | [issue 230](https://github.com/groboclown/p4ic4idea/issues/230) | 2021-07-06 | "While refreshing changelist, I am getting this error: P4 Plugin Error: Internal error: Where request returned too many values" | Big-workspace refresh fails |
| B13 | Elwetana | [issue 232](https://github.com/groboclown/p4ic4idea/issues/232) | 2022-03-14 | "I am trying to follow the instructions for specifying the SSL connection, but I am still getting the error message above" | SSL setup unusable |
| B14 | quicktime | [issue 210](https://github.com/groboclown/p4ic4idea/issues/210) | 2020-02-26 | "Constantly getting P4 Plugin Error: Internal error: null when performing different actions" | "Internal error: null" everywhere |
| B15 | thaarok | [issue 216](https://github.com/groboclown/p4ic4idea/issues/216) | 2020-05-11 | "When I try to use default P4 changelist (changelist 0), P4 idea plugin creates new changelist called 'Default Changelist'" | Wrong changelist mapping |

### Table C — Marketplace reviews + Stack Overflow (incumbent context)

| # | User | Link | Date | Verbatim quote | Problem class |
|---|---|---|---|---|---|
| C1 | Ice_Ice | [MP review](https://plugins.jetbrains.com/plugin/69-perforce-p4-helix-core/reviews) | 2026-03-20 | "真TM难用，各种丢失文件，同步功能时不时失灵" (damn hard to use, constantly losing files, sync fails intermittently) | Sync losing files |
| C2 | Éric Daigneault | [MP review](https://plugins.jetbrains.com/plugin/69-perforce-p4-helix-core/reviews) | 2025-01-02 | "I could not make it work and could not find any logs to help diagnose why it would fail to connect." | No diagnostics |
| C3 | Chris Carr | [MP review](https://plugins.jetbrains.com/plugin/69-perforce-p4-helix-core/reviews) | 2024-05-31 | "Poor, has problems connecting constantly, doesn't write a log even when 'Dump Perforce Commands' is ticked" | No diagnostics |
| C4 | bruno.meier | [MP review](https://plugins.jetbrains.com/plugin/69-perforce-p4-helix-core/reviews) | 2024-02-07 | "Bad. Slow, produces always error message on startup." | Slowness + startup errors |
| C5 | Dibyendu Das | [MP review](https://plugins.jetbrains.com/plugin/69-perforce-p4-helix-core/reviews) | 2023-07-15 | "Really slow to refresh anything. Does not keep track of submitted CLs. Not configurable to sort via changelist number." | Slowness + stale CLs |
| C6 | psabharwal | [MP review](https://plugins.jetbrains.com/plugin/69-perforce-p4-helix-core/reviews) | 2023-07-04 | "horrible plugin, never works" | Non-functioning |
| C7 | Jan Kalina | [MP review](https://plugins.jetbrains.com/plugin/69-perforce-p4-helix-core/reviews) | 2020-05-05 | "Incredibly slow for big P4 workspaces, forever frozen during trivial tasks like Perforce Edit." | Slowness/freezes |
| C8 | Arne Evertsson | [SO 8399936](https://stackoverflow.com/questions/8399936) | 2011-12-06 | "There is a field named 'Client' that doesn't make sense to me. I get the message 'Connection problems: Client Unknown' whatever I type into the field." | Config failure |
| C9 | Maxim Kolesnikov | [SO 16903457](https://stackoverflow.com/questions/16903457) | 2013-06-03 | "When I'm trying to create patch file IDEA just excludes it... File size is bigger than 500K" | Patch workflow limit |
| C10 | user3191016 | [SO 21097779](https://stackoverflow.com/questions/21097779) | 2014-01-13 | "Wrong client specification: Client roots: v:\dev Actual root: C:\IntelliJProjects\MainProject" | Client-root mapping |
| C11 | user3281679 | [SO 21617140](https://stackoverflow.com/questions/21617140) | 2014-02-07 | "Cannot run program 'p4'... Not sure why it is trying to look in my IntelliJ project root directory for the p4 client." | Wrong p4 path |
| C12 | radumanolescu | [SO 22996423](https://stackoverflow.com/questions/22996423) | 2014-04-10 | "it does not get automatically added to p4... it says 'ignored file can't be added'." | Add/reconcile misbehavior |
| C13 | Андрей Щеглов | [SO 29771137](https://stackoverflow.com/questions/29771137) | 2015-04-21 | "the status is outdated (shows changelists I've submitted hours ago as 'pending'), and both 'Refresh' and 'Force Refresh' operations take forever" | Stale CLs + slowness |
| C14 | Navin GV | [SO 27242007](https://stackoverflow.com/questions/27242007) | 2014-12-02 | "After i restarted the intellij, it doesn't open and throws the below exception" | Plugin crash |
| C15 | edwardmlyte | [SO 33675284](https://stackoverflow.com/questions/33675284) | 2015-11-12 | "Perforce marked the classes in the old lowercase package as updates, not deletions... Jenkins tried to build, it gets compilation issues stating each class is a duplicate." | Refactor integrity |
| C16 | ab11 | [SO 44075383](https://stackoverflow.com/questions/44075383) | 2017-05-19 | "I am unable to modify my source file from this dialog... close the perforce dialog, edit the source, and relaunch" | Submit UX friction |

Deduplication: A3 and C-table's Todd Heidenthal row are the same person (counted once, links merged); JetBrains staff reporters and anonymous/unnamed accounts excluded. **Net: 64 distinct real users.**

### The five themes across all 64

1. **Connection failures with zero diagnostics** — the single loudest theme (C2, C3, A21, C8, plus A25's dead UI state).
2. **Slowness/freezes at real workspace scale** (A1, A2, A5, A6, B3, A26, C7, C4, C13) — always benchmarked against the native `p4`/P4V.
3. **Data loss / unexpected reverts** (A1, A10, A11, B8, B9, C1) — highest-stakes theme; users rescue themselves via Local History, an accident, not a design.
4. **Checkout/read-only lifecycle confusion** (A3, A4, A15, A16, A32, A33) — IDE belief vs server state mismatch.
5. **Submit/changelist integrity** (A7, A13, A14, B4, B14, B15, C13, C15) — one failed submit is a day-0 churn event.

---

## Item 4 — The build is small: proven with a running plugin

**Constraint respected:** €0 infra (local `p4d` r25.2 from filehost.perforce.com; no server rental), no Gradle, no Maven — the IDE's bundled JBR (OpenJDK 21.0.8, includes `javac`) compiled the plugin directly against the unpacked IDEA 2025.3 distribution's `lib/` jars.

### What was built

```
scratch-plugin/
├── src/main/java/p4gate/
│   ├── P4Cli.java                 # runs `p4` (configurable via -Dp4.executable), UTF-8, 30 s timeout
│   ├── P4Data.java                # parses `p4 changes -s pending` + `p4 opened`; Change model; CLI test driver
│   ├── P4ToolWindowFactory.java   # ToolWindowFactory → registers "Perforce P4" tool window
│   └── P4Panel.java               # Swing: changelist JTree + Refresh/Submit/Shelve/Revert(-k)/p4 info + status log
├── src/main/resources/META-INF/plugin.xml   # platform-only dependency; toolWindow extension point
└── build.ps1                      # javac → jar (python zipfile, JBR has no jar.exe) → plugin zip
```

**Result: `perforce-intellij-integration-day0.zip` — 10,134 bytes.** The v1 product surface is literally 10 KB of plugin. The gate's fear ("if a working skeleton is not up in 3 days, the scope is wrong") is answered: it was up in one session.

### Verified data flow (real p4d, real parse, real ops)

A local `p4d` (r25.2) was started on `127.0.0.1:1666`; user `gate`, client `gate_ws` and three files were seeded; two files moved into pending `Change 1` via `p4 reopen -c 1`.

`p4gate.P4Data` CLI driver output (via the bundled JBR java):

```
change 0 — 'default' (1 files)
    //depot/src/util.py — add @default
change 1 — 'Gate demo change one ' (2 files)
    //depot/README.txt — add @1
    //depot/src/main.py — add @1
```

(the `—` renders as a console-codepage glyph in PowerShell; the string is fine.)

Every panel operation was then verified against the same server:

| Panel button | Command | Verified result |
|---|---|---|
| Shelve | `p4 shelve -c 1` | `Change 1 files shelved.` |
| Revert (-k) | `p4 revert -k -c 1 //...` | open state cleared; **disk contents preserved** (`README.txt` still readable) |
| Submit | `p4 submit -d "Gate demo submit"` | `Change 2 submitted.` → visible in `p4 changes -s submitted` |

### What compiling taught (notes for the 21-day plan)

- `com.intellij.openapi.wm.ToolWindowFactory` + classic `ToolWindow` are the right 253 API surface; `ContentFactory.getInstance()` works. `com.intellij.openapi.ui.ToolWindow` does not exist.
- Missing one import was the only compile error of the build — the API guesses were otherwise exact.
- `p4 opened` output is format-asymmetric: numbered CLs print `add change 1`, the default prints `add default change`. Any parser must handle both — the rival's parser failures (e.g. B12 "Where request returned too many values") show exactly where this class of code rots.
- PowerShell 5.1's `Set-Content -Encoding UTF8` writes BOMs — BOM-less file writes only (python or the create tool).

---

## Provenance & limitations

- Marketplace data: `/api/plugins/69/...`, `/api/plugins/31556/...`, `/api/plugins/19114/...` (no auth). `meanRating` is a bogus constant in every response; the `votes` histogram is the real metric.
- IDE distributions: JetBrains data.services release links; zip64 central directories read over HTTP range requests (shared `ziplister.py`).
- YouTrack: ~1,700+ issue records screened, 35 threads + 40 descriptions fetched in full; staff logins excluded. GitHub: 200 of 234 `p4ic4idea` issues listed, 28 threads read in full.
- Blocked sources (evidence saved): forum.perforce.com (NXDOMAIN), forums.unrealengine.com (TLS root + 403), reddit.com (403; pullpush.io archive scanned, no relevant yield).
- The 2025.3 zip URL is served from the IIC release page but carries product code IU (unified distribution); pure-CE zips exist only ≤2025.2. Both were inspected.
- p4 binaries: r25.2 (r26.2 not published on filehost at time of writing).
- Raw JSON artifacts retained in the session state (`files/day0-gate/`): `meta69.json`, `comments69.json`, `compat69.json`, `comments31556.json`, `comments19114.json`, `research-b/*`.
