# Why we built a paid Perforce plugin for JetBrains IDEs

*This page is the honest version of the pitch. Every number below is reproduced from live marketplace data (2026-09-30) and every feature claim is verified against a real Perforce server — see [user guide](user-guide.md).*

## The tool you are forced to use is free. And it is not very good.

You didn't choose Perforce. Your employer did, because your repository is hundreds of gigabytes of art, models, audio or CAD binaries, and Git physically cannot hand every artist a copy of that. So Perforce sits on your machine, and you use it eight hours a day, forever.

JetBrains' own IDE integration — `Perforce P4 (Helix Core)` — has **13,378,920 downloads**. It has **7 votes on the entire marketplace, 4 of them one-star**, averaging **2.29**. It carries **10 full-text reviews spanning 2014 → 2026**, and **JetBrains has never replied to a single one of them, once, in twelve years.**

Two of those reviews, verbatim:

> *"could not make it work and could not find any logs to help diagnose why it would fail to connect… I can run commands through the command line from inside goland itself and through p4v normally."*

> *"Incredibly slow for big P4 workspaces, forever frozen during trivial tasks like Perforce Edit."*

A developer who used both plugins reviewed them on the same day — one star for JetBrains' own ("incredibly slow"), and for a free third-party replacement: *"works like a charm — no lags."* The free version is beatable. We are not selling against Perforce, we are selling a better bridge to it.

## The two claims that matter

Everything else on a marketplace listing is decoration. Per [the build spec](https://github.com/danilgorbunofff/Perforce-IntelliJ-Integration#54-ranked-build-order), this product wins on exactly two claims:

**1. It connects — and when it can't, it tells you why.**
The Connection tab runs the real `p4 info` and `p4 login -s` **from your workspace directory, exactly the way every other plugin call runs**, shows p4's own answer for where each setting came from (environment, `p4 set` / OS registry, or which P4CONFIG file), and ends with a verdict — READY, NOT READY (the server answers but e.g. your client does not exist, is locked to another host, or you are not logged in) or NOT CONNECTED — naming the **first failing step** with a targeted hint. It also catches the silent misconfigurations that eat hours: a P4CONFIG file that p4 ignores because `P4CONFIG` was never set, and a config file that quietly overrides your environment variables. One click imports a known-good environment from a found P4CONFIG file.

**2. It doesn't freeze.**
Users report the official plugin freezing on real workspaces. We do not know its internals, so we make a narrower, measurable promise: every listing costs a **constant number of `p4` calls**, no matter how many changelists exist (our own first draft spawned one process per changelist — **152 spawns / 21.1 s** for 151 changelists — and was rewritten to batch), **no `p4` call ever runs on the UI thread**, listings and long reads can be cancelled (a submit or shelve deliberately cannot: killing one half-way is worse), and read-only queries are killed after 30 seconds instead of hanging.

## What v1 does

| You want to | Do |
|---|---|
| See changed files grouped by changelist | **Changelists tab** — pending tree, default changelist first |
| See your submitted history, sorted by CL number | Sortable table — and it shows the *real* submitted numbers, even when the server renumbered a changelist on submit |
| Start a change | **Edit current file** / **Add current file** (`p4 edit` / `p4 add` on the active editor file) |
| Submit, shelve, revert | Submit asks first; **Revert (keep files)** uses `-k`; **Revert (discard edits)** names the files and asks first |
| Diff and annotate a file | Select the file, press the button |
| Pick up files edited outside the IDE (artists and TAs do this constantly) | **Reconcile…** — previews, lists what it found, then opens exactly those files; P4IGNORE files are honored automatically |
| Resolve after a sync conflict | **Sync + auto-merge** merges everything without conflicting chunks; for a file still in conflict, **Accept theirs… / Accept yours…** act on that one file and say what will be discarded before doing it |
| Keep generated files out of Perforce | **Ignore file…** writes the rule into the ignore file p4 actually reads (your `P4IGNORE`, else `p4ignore.txt`) and verifies it took effect; for a file opened for add it first un-adds it (file stays on disk) |
| See your streams | **Streams tab** — parent hierarchy, your client's stream marked |
| Work the way the IDE expects | The plugin **is** the project's VCS provider. The native **Local Changes** view runs on `p4 opened`, with no directory scan behind it. The diff's left side is the real `#have` depot content. The **commit dialog** submits exactly the files you selected, with your message. **Rollback** is `p4 revert`. Typing into a read-only file runs `p4 edit`. Renames and moves, including refactorings, become real `p4 move`s |

Every row is covered by automated tests that run against a live Perforce server (Helix Core r25.2), including a real out-of-date submit between two clients. The VCS integration also runs inside a headless IntelliJ IDEA in tests, and the JetBrains Plugin Verifier reports the plugin compatible with IDEA 2025.3 through 2026.2.3. The Swing dialogs themselves still need one manual click-through before release.

## What v1 does not do (yet)

- A file edited outside the IDE without `p4 edit` (writable but unopened) stays invisible in Local Changes until you run **Reconcile…**. That is how Perforce itself sees it, and the reason a directory scan is never needed.
- Perforce changelists are not mirrored as IDE changelists (the tool window shows them); no unshelve or history view yet.
- No merge/branch automation, no code review integration, no CI integration. Those are different products.
- It replaces JetBrains' bundled Perforce plugin rather than running beside it: disable that one (IntelliJ IDEA 2025.3 bundles it), and your existing project mappings carry over.

## Pricing

**$19/year for the first year, $15 renewal** — in line with the proven band for this product shape ($15–30/yr; the top-earning integration plugins of 2026 charge $15–18). The 21-day plan from [the repo README](https://github.com/danilgorbunofff/Perforce-IntelliJ-Integration) ships the trial first and *measures* the trial-to-paid rate, because that number is not published anywhere and cannot be derived from public endpoints.

## One honest caveat

The size of the pain is **not** proven. Seven votes cannot tell you how widespread it is — the recommendation rests on the review *text* (specific, current, unfixed across twelve years), not review count. What is proven: the incumbent's failure modes are real and reproducible, and nobody on the entire marketplace is selling a better one. See the [market analysis](https://github.com/danilgorbunofff/Perforce-IntelliJ-Integration) for the full evidence.
