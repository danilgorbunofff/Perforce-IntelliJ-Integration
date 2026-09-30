# Perforce IntelliJ Integration

> **A paid Perforce / Helix Core integration for JetBrains IDEs.**
> The incumbent is JetBrains' own. It has **13,378,920 downloads** and a **2.29 / 5** from **seven** reviewers — and nobody on the entire marketplace is selling a better one.

**Status:** the idea is validated against live marketplace data. **No code has been written.**
**Next action:** run the [Day-0 gate](#7-day-0-gate--2-hours-before-any-code). It takes 2 hours. Do it before writing a line of code.

---

## Contents

| § | |
|---|---|
| [1](#1-what-this-is-in-plain-english) | What this is, in plain English |
| [2](#2-the-business-thesis-in-three-sentences) | The business thesis, in three sentences |
| [3](#3-the-evidence) | The evidence — every number, re-verified live |
| [4](#4-the-competition-exactly) | The competition, exactly |
| [5](#5-the-specification--written-by-its-own-users) | The specification — written by its own users |
| [6](#6-naming--listing-decided) | Naming & listing — decided |
| [7](#7-day-0-gate--2-hours-before-any-code) | Day-0 gate — 2 hours, before any code |
| [8](#8-the-21-day-plan) | The 21-day plan |
| [9](#9-economics) | Economics |
| [10](#10-risks-and-open-questions) | Risks and open questions |
| [11](#11-how-to-resume) | How to resume |
| [A](#appendix-a--api-recipes) | Appendix A — API recipes |
| [B](#appendix-b--plugin-id-reference) | Appendix B — plugin ID reference |
| [C](#appendix-c--the-raw-reviews-of-perforce-p4-id-69) | Appendix C — the raw reviews |
| [D](#appendix-d--sources-and-provenance) | Appendix D — sources and provenance |

---

## 1. What this is, in plain English

### What Perforce is

Perforce (now called **Helix Core**) is a **centralised** version control system. It is the opposite of Git.

Git gives every developer a full copy of the repository. That works because text diffs are tiny. It falls apart when the repository contains **hundreds of gigabytes of art, models, audio and video**, because you cannot hand every artist a copy of 300 GB. Perforce keeps **one authoritative server**, and you check out only the files you need.

That single property is why it survives despite being unfashionable. It owns the studios and factories that Git cannot serve:

- **game studios** (source art, per-platform builds)
- **VFX and animation** (frame sequences — huge, binary, immutable)
- **automotive, aerospace, semiconductor, hardware** (large CAD binaries, and requirements around file locking and audit trails)
- **any company with a large binary monorepo**

An estimated majority of AAA game development runs on it. Unreal Engine's own docs ship Perforce instructions.

### What the plugin does

A developer at one of those companies opens their IDE (IntelliJ IDEA, PyCharm, GoLand, Rider, WebStorm — all the same platform) and wants to:

- see **which files they have changed**, grouped into a **changelist** (Perforce's unit of "a batch of edits I will submit together")
- **diff, revert, and submit** those files without leaving the IDE
- **shelve** work-in-progress to the server (like a stash, but it survives machine loss and can be handed to a colleague)
- **reconcile** files edited outside the IDE (artists and TAs constantly do this)
- see **history, blame/annotate, and locks** for a file
- browse **streams** — Perforce's branching model

The plugin is the bridge between `p4` (the command line) and the IDE's own UI.

### Why anyone pays

**They don't choose Perforce. Their employer does.** It is installed on day one and used eight hours a day, forever. That is the whole investment case:

> You are not selling a tool. You are selling **a better version of a tool people are already forced to use.**

A $19/year price against a tool that occupies someone's entire working day is not a purchase decision — it is a rounding error on a corporate card, and often self-reimbursed by a developer who is simply tired of the freeze.

---

## 2. The business thesis, in three sentences

1. **Find a developer tool people are forced to use by their employer.**
2. **Check whether the first-party IDE integration is neglected — measured by review density, not by star rating — and whether anyone already charges for a better one.**
3. **If the seat is empty and the audience is large, ship the paid version at $15–30/year.**

Perforce is the largest forced-use audience on the JetBrains Marketplace with an **empty paid seat**.

---

## 3. The evidence

All figures below were pulled from the live JetBrains Marketplace API on **2026-09-30** and are reproducible in about two minutes using [Appendix A](#appendix-a--api-recipes).

### 3.1 The metric that made this findable

Downloads are a lie. JetBrains counts downloads of every version, including ones later deleted, and repeated fetches from their CDN. A free plugin with 27 million downloads does not have 27 million users.

**Reviews do not suffer from this**, because leaving one requires an account and a deliberate act. So:

> **Review density = reviews per 1,000 downloads.** It separates real engagement from an inflated install counter.

| Plugin | Downloads | Price | Reviews | **Reviews / 1k DL** |
|---|---|---|---|---|
| **Bitbucket Integration Pro** | 271,167 | **$18/yr** | **491** | **1.811** |
| **JetLab — Integration for GitLab** | 198,823 | **$15/yr** | **254** | **1.278** |
| Elasticsearch | 155,167 | $30/yr | 42 | 0.271 |
| SQLFormatter | 34,714 | $30/yr | 5 | 0.144 |
| Redis | 227,812 | $5/yr | 24 | 0.105 |
| DynamoDB | 223,678 | $30/yr | 13 | 0.058 |
| Jenkinsfile (Anbora) | 157,621 | $15/yr | 4 | 0.025 |
| Database Navigator | 5,030,934 | free | 110 | 0.022 |
| TeamCity *(JetBrains' own)* | 1,089,127 | free | 20 | 0.018 |
| GitToolBox | 10,539,242 | freemium | 146 | 0.014 |
| SonarQube for IDE | 15,771,559 | free | 159 | 0.010 |
| **GitLab** *(JetBrains' own)* | 27,631,823 | free | 31 | 0.0011 |
| **Perforce P4** *(JetBrains' own)* | **13,378,920** | free | **7** | **0.0005** |

**What it says:**

- The two plugins at the top are **paid integration plugins by a single vendor (Majera Software), both shipped in 2026**. They have **5× to 1,600× the review density of everything else in the catalogue.** That is not a rounding artefact; it is a reachable, engaged, paying audience.
- The integrations developers touch **on every single commit** (GitLab, Bitbucket) show the **thinnest engagement when JetBrains owns them** and the **thickest when a third party charges money for a better one.**
- **Votes track choice and satisfaction. Downloads track nothing in particular.** A 27.6M-download free plugin has 31 reviews.

### 3.2 The shape that repeats

| Instance | The outclassed original | The paid replacement |
|---|---|---|
| **Bitbucket** | `Bitbucket Linky` — free, 908,341 DL, **58 votes @ 4.41** | **Bitbucket Integration Pro** — $18/yr, 271,167 DL, **491 votes @ 4.76** |
| **GitLab** | `GitLab` — *JetBrains' own*, free, 27,631,823 DL, **31 votes @ 3.10** (29% one-star) | **JetLab** — $15/yr, 198,823 DL, **254 votes @ 4.83** |
| **Jenkinsfile** | `Jenkinsfile IDEA plugin` — free, 86,285 DL, **12 votes, 100% one-star**, abandoned 2020 | **Jenkinsfile** — $15/yr, 157,621 DL, **4 votes, 100% five-star** |

Note the Bitbucket row carefully, because it is the *strongest* commercial argument here and it is not the obvious one:

**`Bitbucket Linky` is not hated.** It sits at 4.41 stars with 58 votes. And the paid replacement still has **491 reviews — 8.5× the engagement — at $18/year.** So the pattern does not require the free option to be *bad*. It only requires it to be **adequate while a clearly better one exists.** Adequacy does not defend a price point; superiority takes one.

Three independent instances of the same structure means this is **a repeating bug in how marketplaces get populated**, not a coincidence. And therefore the *next* neglected integration is a **predictable, findable slot** rather than a lucky guess.

### 3.3 The full field

An 18-service sweep of the enterprise-integration space:

| Service | State | Numbers |
|---|---|---|
| GitLab | **taken — and it is the winning pattern** | JetLab $15/yr, 198,823 DL, 254 reviews |
| Bitbucket | **taken — same vendor** | Bitbucket Integration Pro $18/yr, 271,167 DL, 491 reviews |
| Gerrit | taken, well served | `Gerrit` 457,807 @4.81★ free; `GitLink` 422,994, 117 votes @4.89 |
| Gitea / Forgejo | **dead — two paid attempts already failed** | `Forgejo` PAID **584 DL**; `Gitea & Forgejo PRs` PAID **7 DL** |
| SonarQube | vendor gives it away | `SonarQube for IDE` 15,771,559 free |
| Sentry | taken, small | `Sentry` PAID 8,550 |
| Azure DevOps | taken | `Azd` PAID 62,380 @4.72★; free alt at 25,907 |
| Jenkins | taken, weak engagement | `Jenkinsfile` PAID 157,621 / 4 reviews |
| TeamCity | no paid rival — **but the free plugin is genuinely liked** | 1,089,127 DL, **20 votes @ 3.4**, histogram `{5:8, 4:3, 3:3, 2:1, 1:5}` |
| **Perforce / Helix Core** | **UNCLAIMED — largest forced-use audience** | **13,378,920 DL, 7 votes @ 2.29, 10 full-text reviews** |
| Buildkite, CircleCI, Grafana, Prometheus, Artifactory, Nexus, Phabricator, CodeCommit, TFS | **absent = no demand** | 0–1,932 DL |

**Two slots survived: Perforce and TeamCity.**

**Perforce wins** on audience by 12× — and on the thing that actually decides it: **TeamCity's users are satisfied, and satisfied users do not pay.**
The free TeamCity plugin's own histogram has **8 five-star votes out of 20**. There is nothing to fix and nobody to rescue. That is a terrible market, and it was almost the fallback recommendation. See [3.5](#35-the-mistake-that-almost-produced-a-bad-recommendation) for how that was caught, because the method matters more than this one answer.

### 3.4 What was killed first — 7 candidates

Recorded so that nobody re-litigates them later without new evidence.

| # | Candidate | Killed by | Evidence |
|---|---|---|---|
| 1 | MCP connectors for 33 SaaS systems | 30 / 33 already had an MCP server | `gh search repos "<system> MCP"` sweep |
| 2 | MCP security scanner | given away free by Cisco and Microsoft | vendor pages |
| 3 | Vector-DB / new-infra IDE plugins | empty on **both** marketplaces = absent demand, not a gap | VS Code + JetBrains sweeps |
| 4 | IDE AI agents | saturated | JetBrains AI Assistant 227M, Copilot 54.6M, Qoder 40.7M, Junie 34.8M |
| 5 | VS Code → JetBrains port gaps | the IDE's own built-ins already fill them | per-engine sweep |
| 6 | ClickHouse standalone DB client | demand too thin, and a free plugin already covers it | 62,895 VS Code-specific installs; JetBrains' free **PDB** lists ClickHouse |
| 7 | Enterprise-integration sweep (Gerrit, Gitea, Forgejo, Buildkite, CircleCI, Grafana, Prometheus, Sentry, Artifactory, Nexus, Phabricator, CodeCommit, TFS, TeamCity…) | see [3.3](#33-the-full-field) | 18-service live query |

**An empty seat on a marketplace is not automatically an opportunity.** Candidates 3 and 6 were "unclaimed" too. The difference is whether the audience is **forced** to be there:

> **Empty because nobody wants it ≠ empty because nobody built it.**

### 3.5 The mistake that almost produced a bad recommendation

Worth preserving, because it is the reason to trust the rest.

I was one step from recommending Perforce on the strength of this sentence: *"13,378,920 downloads at 2.69★ — that is millions of unhappy users."*

Then I found the endpoint that returns the real vote histogram — `GET /api/plugins/{id}/rating` — instead of the single averaged number the storefront shows.

**Perforce P4's rating is built on seven votes.** `{5:2, 1:4, 2:1}`. Raw mean **2.29**.

The histogram does not just correct the number; it deletes the argument. Seven votes is not "millions of unhappy users." It is **seven people**.

> **An empty seat and a bad rating are both cheap, nearly meaningless signals. Review depth is the real one.**

The corrected case is still good — but for a completely different reason:

- ✅ Proven: the complaints are **specific, current, and unfixed** (10 full-text reviews spanning 2014 → 2026; the newest are 2025-01 and 2026-03).
- ❌ Not proven: how **widespread** the pain is. Seven voters cannot tell you that.

Anyone re-opening this repo should notice that the recommendation rests on **review text**, not review count. That is the honest version of the claim.

---

## 4. The competition, exactly

### 4.1 The incumbent — `Perforce P4 (Helix Core)`, id `69`

| | |
|---|---|
| Vendor | **JetBrains s.r.o.** (first-party) |
| Downloads | **13,378,920** |
| Price | **FREE** |
| Votes | **7** — `{5:2, 1:4, 2:1}`, raw mean **2.29** |
| Full-text reviews | **10** |
| Tags | `Administration Tools`, `VCS`, `VCS Integration` |
| Review span | 2014 → 2026 |

**4 of 7 votes are one-star.** Every review is reproduced verbatim in [Appendix C](#appendix-c--the-raw-reviews-of-perforce-p4-id-69).

Two observations that matter commercially:

- **It is first-party, free, and preinstalled-ish.** That is normally fatal to a paid competitor. Here it is the opposite: JetBrains ships it, does not invest in it, and *cannot easily justify* spending engineering time on a product most of their users never touch. That is precisely how an integration rots for twelve years while 13 million people install it.
- **It holds the `VCS Integration` tag, which is flagged `privileged: true`** — i.e. gated. Only JetBrains' own plugin carries it. **Do not plan on getting it.** Plan for the two non-privileged tags the same plugin carries: `VCS` and `Administration Tools`.

### 4.2 The free rival — `Perforce IDEA Community Integration`, id `7685`

| | |
|---|---|
| Vendor | **Matt Albrecht** (independent) |
| Downloads | 56,989 |
| Price | FREE |
| Votes | **4** — `{5:3, 1:1}` |
| Source | open source, `groboclown/p4ic4idea` |
| Tag | `VCS` only |

**This is the most important competitor, and also the best evidence in this document.**

A single developer got sufficiently annoyed by the official plugin that he built a replacement in his own time, in public, for free. Demand proven by supply. He has run it for years and it still only has 4 votes.

**But read [5.3](#53-the-controlled-ab)** — his own reviewers are the ones who explain, in plain terms, what he did better. That is the product spec, handed over by a competitor's users.

### 4.3 The dead ones

| Plugin | ID | Downloads | Votes |
|---|---|---|---|
| `p4Intellij` | 7620 | 4,296 | **0** |
| `Simple P4 Plugin` | 7420 | 5,319 | **0** |
| `Surround SCM` | 61 | 11,753 | — |
| `CloudBees CD/RO` | 14675 | 4,533 | **0** |

Four plugins, all free, none with a single review. **Nobody has ever attempted to charge money for this integration.**

The store search `perforce` returns **9 results on the entire marketplace. Zero of them are paid.**

---

## 5. The specification — written by its own users

This is the part that makes the project buildable rather than speculative.

`GET /api/plugins/69/comments?size=100` returns the **full verbatim text of every review**. Not a star average — the actual complaints, in the users' own words, with dates and usernames.

**The product spec for this repo was not invented. It was transcribed.**

### 5.1 The complaint set, verbatim → the fix

| # | What users hit | Their own words | What ours does instead |
|---|---|---|---|
| 1 | **It cannot connect, and gives nothing to debug with.** Three reviewers, 2024–2026. | *"could not make it work and could not find any logs to help diagnose why it would fail to connect… I can run commands through the command line from inside goland itself and through p4v normally. **Other editors (vscode) also can interact with perforce** from their plugins under the same circumstances."* | **A "why can't I connect" panel.** Run `p4 info`, print the exact command, raw stderr, and the resolved `P4CONFIG` / `P4HOST` / `P4CLIENT` / `P4PORT`. One click to import a known-good environment. |
| 2 | Same failure, made worse by a debug switch that lies. | *"'Dump Perforce Commands' is ticked — says 'No Valid Perforce Connection Found'… and doesn't give any more information than just that."* | Logs that actually contain the exchange. |
| 3 | **It freezes on real repositories.** Three reviewers. | *"Incredibly slow for big P4 workspaces, **forever frozen during trivial tasks like Perforce Edit**."* · *"Really slow to refresh anything."* · *"Bad. Slow, produces always error message on startup."* | Cache `p4 fstat`, batch status queries, keep **everything** off the EDT, virtualise the changelist tree. **Speed is the headline feature, not a nice-to-have.** |
| 4 | **Submitted changelists go stale or go missing.** 2019 & 2023. | *"Does not keep track of submitted CLs. Not configurable to sort via changelist number."* · *"Does not refresh cache of submitted changelists. This results in **mismatch between Perforce and IDEA and incorrect results when using search in IDEA**."* | A real changelist index, incrementally refreshed, sortable by CL number. |
| 5 | **It never registers as a proper VCS provider**, so the IDE's own UI stays dark. | From a user who had used *both*, on the same day: *"**works like a charm — no lags, better integrated with Local changes** (instead of standalone Perforce tool windows)"* · *"provides standard functionality like **setting Perforce as project VCS provider**, other plugins I've tried don't."* | Register properly as a `Vcs` provider → IntelliJ's native **Local Changes**, commit dialog and diff light up for free. |
| 6 | **Streams are missing** — and this came from a **5-star** reviewer. | *"I wish it had support for **stream view** and maybe a 'git-flow' like feature but with streams… what is actually missing is a way to **add ignored files**."* | Streams tree + ignore rules. |

### 5.2 The most valuable row

**Row 6 is the single most valuable line in this document.**

Rows 1–5 come from angry users. They tell you what to **fix**.

Row 6 comes from somebody who gave the plugin **5 stars with no complaint about anything he had used** — and *still* named two features he wanted. In a marketplace with no public analytics, **"a happy user telling you what he would pay for" is the closest thing to a purchase-intent signal that exists.** It is also the cheapest feature to ship, because nobody is angry about it yet.

### 5.3 The controlled A/B

The best single piece of evidence in the project, and it is hidden in row 5.

**User `hkalina` reviewed both plugins on the same day — 2020-05-05.**

- One star for JetBrains' own: *"Incredibly slow for big P4 workspaces."*
- Warm praise for the free community replacement: *"In comparison with official Perforce plugin **works like a charm — no lags, better integrated with Local changes** (instead of standalone Perforce tool windows)."*

**One developer, both products, same day, and the third-party one won.**

That is a controlled A/B run by a real user, for free, and it is the entire thesis in one line:

> **You can charge for a better version of a forced-use tool, because the free version is beatable.**

### 5.4 Ranked build order

Everything below is v1. **Everything else waits for v2.**

1. **Connect reliably, and explain every failure** (rows 1–2).
   Currently the #1 reason people bounce. Unglamorous. Decisive.
2. **Be fast on a 100 GB workspace** (row 3).
   The only thing the competition was ever praised for, and the hardest to retrofit later.
3. **Streams + ignore + changelist index** (rows 4–6).
   The feature gaps — including the one a *satisfied* user named.

**Explicitly out of scope for v1:** anything about merge/branch automation, code review, CI integration, or AI. Those are different products. This one wins on *"it connects"* and *"it doesn't freeze."*

---

## 6. Naming & listing — decided

**The name is a search keyword, not a brand.** The buyer never sees your brand. They type `perforce` into the IDE's plugin search. The name field is the only ranking asset, and it is the only thing rendered in the results list.

Measured behaviour of the marketplace search:

| You search | Results on the whole marketplace | Who is there |
|---|---|---|
| `perforce` | **9** | `.ignore` 19.9M, `Perforce P4 (Helix Core)` 13.4M, `Perforce IDEA Community Integration` 56,989, `p4Intellij` 4,296, `Simple P4 Plugin` 5,319, … |
| `helix core` | **1** | only the incumbent |
| **`perforce intellij`** | **4** | `.ignore`, `CloudBees CD/RO`, `p4Intellij`, `Branchive` — **the incumbent is not even in its own results** |
| `p4` | **232** | `Package Checker`, `plantuml4idea`, `LSP4IJ`… pure noise |
| `p4v` | 33, auto-corrected to `psv` | **no Perforce plugin at all** |

*(These probes were ordered by download count, matching the default storefront sort. The marketplace ranks largely on downloads.)*

**Three conclusions fall straight out of that table:**

1. **`Perforce` is the word that matters.** It returns a 9-result set across an entire marketplace. Own it.
2. **`perforce intellij` is an open goal.** It is a completely natural query, it returns 4 results, and **the incumbent does not rank for it.** A name containing both words wins a query the competitor is absent from, for free.
3. **Never lead with `P4`.** The queries `p4` (232 results of junk) and `p4v` (auto-corrected away, zero Perforce results) prove the abbreviation is a *liability* in the name. The two worst-performing plugins in the family are exactly the two that led with it: `p4Intellij` **4,296** and `Simple P4 Plugin` **5,319** — against `Perforce IDEA Community Integration`'s **56,989**. Keep `P4` and `Helix` for the description, where aliases work and do not cost you the title.

### Recommended name

> ## `Perforce IntelliJ Integration`

| Alternative | Wins | Cost |
|---|---|---|
| `Perforce IntelliJ Integration Pro` | above, **plus** a premium frame in a shared results list | one wasted word — the store already shows a `PAID` badge |
| `Perforce P4 for IntelliJ` | adjacent to the incumbent's name | collides conceptually with the exact plugin being replaced — reads as a fork, not an upgrade |
| `JetP4` / any invented brand | nothing | **invisible by construction.** Never do this. |

**Put the premium framing in the `vendor` name, not the title.** The store separates *vendor* from *plugin name* — that is why it is `Majera Software` + `JetLab`, and `Anbora Labs` + `Jenkinsfile`.

### The other three indexed fields

- **Tags.** Mirror the incumbent's **non-privileged** tags exactly: **`VCS`** (id 250) and **`Administration Tools`** (id 99). `VCS Integration` (id 288) is `privileged: true` — gated, first-party only.
- **First sentence of the description.** Also indexed, loosely. Spend it on the aliases you gave up in the title, exactly the way the rival does it:
  > *Associate your IDE project with Perforce (`p4` / Helix Core) through the built-in version control.*
- **The pitch.** A user typing `perforce` sees 9 rows. Your name and first sentence must say, in order: `perforce` → `intellij` → **what you get that the others do not.** Per [5.4](#54-ranked-build-order), that is **"it connects"** and **"it doesn't freeze."** Those two claims are the whole listing.

---

## 7. Day-0 gate — 2 hours, before any code

**Do this, and stop if any step fails.** This is the discipline that killed the other seven candidates.

### 1. Is it bundled? *(the decisive question)*

Install **IntelliJ IDEA Community**, open Settings → Plugins, search `perforce`.

- **If `Perforce P4` is preinstalled / bundled** → its 13.4M downloads are *involuntary*, and a large share of your "audience" never chose to have it. That is a red flag, and it downgrades the pick.
- **If it is not bundled** and users must install it deliberately → the 13.4M is real demand and the pick stands.

**Partial evidence already gathered:** the plugin's `compatible-products` list returns `IDEA` but **not** `IDEA_PRO`, which is suggestive that it is *not* an Ultimate-bundled component. **This is not conclusive.** Verify by hand.

> ⚠️ **If it turns out to be bundled, do NOT simply switch to TeamCity.** TeamCity's free plugin is genuinely liked (8 of its 20 votes are five-star, see [3.3](#33-the-full-field)) — and **a satisfied audience is the hardest sell there is.** Re-run the [3.1](#31-the-metric-that-made-this-findable) screen instead and find the next integration whose reviews are **numerous and angry**, not merely absent.

### 2. Read all 10 reviews yourself

[Appendix C](#appendix-c--the-raw-reviews-of-perforce-p4-id-69) summarises them. Read the originals anyway, with the vendor replies. Then read the reviews of the known **failed paid** plugins — `Forgejo` (paid, 584 DL) and `CIclone` (paid, 13,202 DL).

**Reviews are the only public window into *why* a paid plugin died.** Take it before you become the next data point.

### 3. Find 10 real Perforce users in public

Sources: `groboclown/p4ic4idea` GitHub issues · r/gamedev · r/Perforce · the Perforce community forums · Unreal/Unity dev Discords.

Ask exactly one question:

> *"What does your JetBrains Perforce plugin get wrong?"*

**If you cannot find 10 people in a day, you cannot find 10 customers in a year.**

### 4. Confirm the build is small

Wire `p4` CLI → changelist tree → submit/shelve in a scratch plugin.

**If a working skeleton is not up in 3 days, the scope is wrong** — not the idea, the scope. Re-read [5.4](#54-ranked-build-order) and cut until it is.

---

## 8. The 21-day plan

| Days | Do | Output |
|---|---|---|
| **0** | The gate above | **build / re-screen / stop** |
| 1–3 | `p4` CLI bridge + changelist tree in a scratch plugin | a skeleton that lists real changelists |
| 4–10 | The three ranked items in [5.4](#54-ranked-build-order) — connect-and-explain, speed, streams/ignore — validated against the 10 interviews | a build you would use yourself |
| 11–13 | Submit, shelve, reconcile, diff, annotate, conflict resolution | feature-complete v1 |
| 14 | Marketplace listing: 5 screenshots, a 60-second GIF, a demo video | submitted for review |
| 15–18 | Reviews take days. Write the "why" page and the docs | listed |
| 19–21 | **Price it and turn on the trial.** $19/yr first year, $15 renewal — DynamoDB's $30 → $24 → $18 ladder is the proven shape | **you now own the number nobody else could measure** |

**Then the loop:**

Read your own trial → paid rate.

- **Above ~6%** → you have a business. Build plugin #2 for the next neglected forced-use tool, reusing the whole harness.
- **Below** → you have spent three weeks and learned the number that seven candidates could not tell you. That is still the cheapest possible outcome, because the alternative was learning it after a year.

---

## 9. Economics

### 9.1 What is structurally verified

- **JetBrains handles checkout, licensing, trials, VAT, tax, refunds, and payouts. You write zero payment code.**
  *(Direct contrast to a self-hosted product where you are testing your own checkout with your own credit card.)*
- JetBrains takes **15%**; the vendor keeps **85%**. Documented ceiling 25%.
- Minimum payout **$200**, paid annually.
- **91% of paid plugins run on the free Community edition** — you are not selling only to corporate Ultimate seats.
- **The marketplace *is* the distribution.** No followers required, no audience needed. Demand is published and searchable.
  > This is the only channel available where having **zero reach is not disqualifying.**
- The price band for this product shape is proven by the top earners: **$15–30/year** for integrations.

### 9.2 The market distribution

From `poko8725/jetbrains-marketplace-scan`, snapshot 2026-08-03, covering **all 404 paid plugins**:

| | |
|---|---|
| Median downloads | **1,411** |
| p90 downloads | 43,031 |
| Max downloads | 1,548,926 |
| **2026 cohort** (n=138, one third of all paid plugins) | median **171** downloads |
| Annual price median | **$19** (p10 $5 · p25 $10 · p75 $39 · p90 $59 · max $350) |
| Perpetual price median | $9.90 (n=60) |
| Community-edition share | 91% (366 / 404) |

For **$240/year** of revenue you need **~6.3% trial → paid conversion**. At **5%**, **43% of the 2026 cohort clears it.**

**That last line is the actual reason to attempt this.** The bar is not "become Majera." The bar is *"convert one in twenty trials,"* and roughly half of last year's entrants cleared it.

### 9.3 The number nobody can give you

> **Trial → paid conversion is not published anywhere and cannot be derived from public endpoints.**

It is the number the entire decision rests on, and it is the one input that the scan's author — who produced the most thorough public analysis that exists — explicitly says **can only be obtained by shipping one plugin and reading your own rate.**

Everything in this document is a proxy for that number. Nothing replaces it.

### 9.4 The honest range

Using 2026's two winners as the ceiling and the 2026 median as the floor:

| | Downloads | Outcome |
|---|---|---|
| **Floor** | ~171 (2026 median paid plugin) | a few hundred dollars, ever |
| **Ceiling** | 199k–271k (Majera-class, 2026) | 254–491 paid reviewers at $15–18/yr → **tens of thousands of dollars**, cumulatively |
| **Most likely** | the low-to-middle part of that range | |

**Plan for the floor. The ceiling is the reason to try.**

Say this out loud: **this is a lottery ticket with a very good ticket printer.** What is de-risked is the **channel** and the **product shape** — both are unusually well evidenced. What cannot be de-risked is **you**. Which is exactly why the first plugin is an **instrument to measure conversion**, not a bet-the-year attempt.

---

## 10. Risks and open questions

Be honest about these. They are ranked by how much they could change the decision.

| # | Risk | Status |
|---|---|---|
| 1 | **Bundling.** If `Perforce P4` ships preinstalled in IDEA Ultimate, a share of the 13.4M downloads is involuntary and users must be persuaded to disable it. | ⚠️ **Unverified. This is the Day-0 gate, question 1.** |
| 2 | **Conversion rate is unmeasurable from outside.** The entire business case rests on one number that only shipping can produce. | ⚠️ **Permanently unverifiable in advance.** Accepted risk. |
| 3 | **7 votes is thin evidence of scale.** It proves the pain has not been fixed since 2014. It does **not** prove the pain is widespread. | ✅ Understood. Mitigated by the 10 interviews in the gate, not by more API data. |
| 4 | **A mature free rival exists** (`Perforce IDEA Community Integration`, `groboclown/p4ic4idea`, 56,989 DL, years of development). | ✅ Also the *best* evidence — see [5.3](#53-the-controlled-ab). Must be beaten on reliability and speed, not on features. |
| 5 | **No Perforce server for testing.** This is a real practical blocker: developing the plugin needs a working Helix Core instance with a nontrivial workspace. | 🔧 Unresolved. Perforce offers a free tier for small teams (up to 5 users / 20 workspaces) — **confirm this before Day 1.** |
| 6 | **Revenue is skewed, not steady.** The distribution in [9.2](#92-the-market-distribution) is long-tailed; most paid plugins earn almost nothing. | ⚠️ Accepted. See [9.4](#94-the-honest-range). |
| 7 | **JetBrains could fix their own plugin**, or bundle a rewrite via an acqui-hire. | ⚠️ Low likelihood — they have not in twelve years, and Perforce-using IDE customers are a minority of their base. |

---

## 11. How to resume

**If you are a future session or a future you, start here.**

### Read in this order
1. [§5 — the specification](#5-the-specification--written-by-its-own-users). It is the product. Everything else is justification for reading it.
2. [§7 — the Day-0 gate](#7-day-0-gate--2-hours-before-any-code). It is the next action, and it is cheap.
3. [§3 — the evidence](#3-the-evidence). Only if you intend to change the decision.

### The immediate checklist

- [ ] **Run the Day-0 gate.** It is 2 hours and it can kill the project. That is a feature.
- [ ] Confirm the free Perforce server tier covers development (risk #5) — **do this before anything else, it is a hard blocker.**
- [ ] Answer [risk #1](#10-risks-and-open-questions): bundled or not? One IDE install answers it.
- [ ] Find 10 real Perforce users; ask the one question.
- [ ] Only then: `p4` CLI bridge + changelist tree, 3-day timebox.

### Standing constraints

- **The 3-day skeleton timebox is not negotiable.** If it does not fit, cut scope per [5.4](#54-ranked-build-order) — do not extend the box.
- **Do not add features that are not in the six complaints** until a paying user asks. Free users will ask for everything; they will not pay for any of it.
- **Never re-open the seven killed candidates in [3.4](#34-what-was-killed-first--7-candidates)** without new data. They were killed by evidence, not by taste.
- **TeamCity is not the fallback.** [3.3](#33-the-full-field), last line.
- The name is `Perforce IntelliJ Integration`. Do not get creative later; [§6](#6-naming--listing-decided) explains why, and the reasoning is measured, not aesthetic.

### Still genuinely unknown

1. Whether the incumbent is bundled (gate question 1).
2. Trial → paid conversion (unmeasurable in advance).
3. How widespread the complaints are, as opposed to how long-standing.
4. Whether JetBrains' `verified vendor` status is required to charge. **Not yet checked.** Confirm before Day 14.

---

## Appendix A — API recipes

Everything in this document is reproducible. No authentication, no API key, no quota worth worrying about.

### JetBrains Marketplace

```bash
# Search. NOTE: `max` must stay <= ~20 — max=30 returns HTTP 400.
curl -s "https://plugins.jetbrains.com/api/searchPlugins?search=perforce&max=20&orderBy=downloads"

# THE VOTE HISTOGRAM — the metric this whole document is built on.
# `meanRating` in this response is useless (a constant ~4.06 for every plugin).
# The `votes` histogram is real. Sum it to get the true review count.
curl -s "https://plugins.jetbrains.com/api/plugins/69/rating"

# THE FULL TEXT OF EVERY REVIEW — the product specification.
curl -s "https://plugins.jetbrains.com/api/plugins/69/comments?size=100"

# Plugin metadata
curl -s "https://plugins.jetbrains.com/api/plugins/69"
```

### Traps — read before writing your own script

| Trap | Reality |
|---|---|
| `GET /api/plugins/{id}/reviews` | **HTTP 400** `Parameter 'family' is incorrect`. Does not exist. Use `/comments`. |
| `GET /api/plugins/{id}/ratings` | Same 400. Use `/rating`. |
| `searchPlugins` → `rating` field | This one **is** the real mean over the histogram. The `/rating` endpoint's `meanRating` is not. |
| `GET /api/plugins/{id}` → `ratingCount` | **Always 0 / null.** `searchPlugins` also returns 0. **Vote counts come from `/rating` only.** |
| Plugins with **no** votes | `/rating` and `/comments` return **HTTP 404**, not an empty object. Handle it — it does not mean the ID is wrong. |
| The review text field | It is **`comment`**, not `text`. Getting this wrong silently returns empty strings. |
| `searchPlugins` → `total` | Caps at 10,000. |
| `searchPlugins` → `cdate` | Is **not** the creation date. Do not use it as one. |
| Plugin pages (`plugins.jetbrains.com/plugin/69`) | **JS-rendered SPA.** Regex/`curl` scraping yields nothing. Use the API. |
| `compatible-products` | Returns a **flat list of strings**, not objects. |

### VS Code Marketplace

```bash
curl -s -X POST "https://marketplace.visualstudio.com/_apis/public/gallery/extensionquery" \
  -H "Accept: application/json;api-version=7.2-preview.1" \
  -H "Content-Type: application/json" \
  -d '{"filters":[{"criteria":[
        {"filterType":8,"value":"Microsoft.VisualStudio.Code"},
        {"filterType":10,"value":"perforce"}]}],
      "flags":914,"pageSize":20,"sortBy":4,"sortOrder":2}'
```

### On Windows / PowerShell

`curl` is an alias for `Invoke-WebRequest` in Windows PowerShell 5.1. Use `curl.exe`, or Python. Set `$env:PYTHONIOENCODING="utf-8"` — the default console code page will mangle non-ASCII review text (some reviews are in Chinese).

---

## Appendix B — plugin ID reference

**The incumbent and the family (all FREE, none paid):**

| ID | Name | Vendor | Downloads | Votes |
|---|---|---|---|---|
| `69` | **Perforce P4 (Helix Core)** | JetBrains s.r.o. | **13,378,920** | **7** `{5:2, 1:4, 2:1}` |
| `7685` | Perforce IDEA Community Integration | Matt Albrecht | 56,989 | 4 `{5:3, 1:1}` |
| `61` | Surround SCM | Yan Shapochnik | 11,753 | — |
| `7420` | Simple P4 Plugin | Ranjan Darbha | 5,319 | **0** |
| `7620` | p4Intellij | farbluer | 4,296 | **0** |
| `14675` | CloudBees CD/RO | CloudBees | 4,533 | **0** |
| `7495` | `.ignore` *(JetBrains' own; ranks #1 for the query `perforce`)* | JetBrains s.r.o. | 19,922,648 | 125 `{5:72, 4:13, 3:7, 2:2, 1:31}` |

**The proven pattern (the evidence that this works):**

| ID | Name | Vendor | Price | Downloads | Votes |
|---|---|---|---|---|---|
| `13538` | **Bitbucket Integration Pro** | Majera Software | **PAID** | 271,167 | **491** `{5:414, 4:56, 3:10, 2:4, 1:7}` → 4.76 |
| `18689` | **JetLab — Integration for GitLab** | Majera Software | **PAID** | 198,823 | **254** `{5:219, 4:29, 3:4, 2:1, 1:1}` → 4.83 |
| `8015` | Bitbucket Linky *(the free original)* | Daniil Penkin | FREE | 908,341 | 58 `{5:42, 4:8, 3:2, 2:2, 1:4}` → 4.41 |
| `22857` | GitLab *(JetBrains' own)* | JetBrains s.r.o. | FREE | 27,631,823 | 31 `{5:7, 4:10, 3:2, 2:3, 1:9}` → 3.10 |
| `8183` | GitLink | Ben Gibson | FREE | 422,994 | 117 `{5:111, 4:4, 1:2}` → 4.89 |

**The rejected fallback (so nobody re-proposes it):**

| ID | Name | Vendor | Downloads | Votes |
|---|---|---|---|---|
| `1820` | TeamCity | JetBrains s.r.o. | 1,089,127 | 20 `{5:8, 4:3, 3:3, 2:1, 1:5}` → **satisfied audience** |
| `10127` | Jenkinsfile IDEA plugin *(abandoned 2020)* | mrchang | 86,285 | 12 — **100% one-star** |
| `26270` | Jenkinsfile *(the paid replacement)* | Anbora Labs | 157,621 **PAID** | 4 — **100% five-star** |

> **ID hygiene note:** `p4Intellij` is id **7620**; **4,296 is its download count**, not its ID. `Simple P4 Plugin` is id **7420**; **5,319 is its download count.** These were briefly transposed during the original research and produced one wrong measurement. Always confirm an ID against a fresh search result before trusting it.

---

## Appendix C — the raw reviews of `Perforce P4` (id 69)

**Vote histogram `{5:2, 1:4, 2:1}` — 7 votes, raw mean 2.29. 4 of 7 are one-star.**
*(The storefront displays a higher figure — 2.69 — which is consistent with the store smoothing low vote counts. Both are built on seven votes, which is the point.)*

**The ten reviews, spanning 2014 → 2026, newest first by theme:**

**Connection failures — the #1 bounce reason**

- **Éric_Daigneault**, 2025-01 — *"could not make it work and could not find any logs to help diagnose why it would fail to connect… I can run commands through the command line from inside goland itself and through p4v normally. **Other editors (vscode) also can interact with perforce** from their plugins under the same circumstances."*
- **chris.carr.1**, 2024-05 — *"'Dump Perforce Commands' is ticked — says 'No Valid Perforce Connection Found'… doesn't give any more information than just that."*
- **Ice_Ice**, 2026-03 (Chinese) — *"真TM难用，各种丢失文件，同步功能时不时失灵"* — roughly: *"Incredibly hard to use, files get lost, sync intermittently fails."*

**Performance**

- **hkalina**, 2020-05 — *"Incredibly slow for big P4 workspaces, forever frozen during trivial tasks like Perforce Edit."*
- **dibyendu**, 2023-07 — *"Really slow to refresh anything."*
- **Bruno_Meier**, 2024-02 — *"Bad. Slow, produces always error message on startup."*

**Changelist staleness**

- **dibyendu**, 2023-07 — *"Does not keep track of submitted CLs. Not configurable to sort via changelist number."*
- **heidenthal_gti**, 2019-03 — *"Does not refresh cache of submitted changelists. This results in **mismatch between Perforce and IDEA and incorrect results when using search in IDEA**."*

**The five-star reviewer — read this one twice**

- **sythelux**, 2024-05 — *"It works actually better than the native perforce application. It even has support for reminding you on TODOs, reformat your code before submitting etc. I would wish it had support for **stream view** and maybe a 'git-flow' like feature but with streams and what is actually missing is a way to **add ignored files**."*

**Blunt**

- **puneet_sabharwal**, 2023-07 — *"horrible plugin, never works"*

**And from the free rival (id 7685) — the controlled A/B**

- **hkalina**, **2020-05-05** *(the same day as his one-star review above)* — *"In comparison with official Perforce plugin **works like a charm — no lags, better integrated with Local changes** (instead of standalone Perforce tool windows)."*
- **maxota**, 2015 — *"provides standard functionality like **setting Perforce as project VCS provider**, other plugins I've tried don't."*

---

## Appendix D — sources and provenance

**Where this decision came from.** It was not brainstormed. It was selected by screening the two largest IDE marketplaces for a measurable, repeating structural pattern, after seven prior candidate classes were killed by evidence.

**Primary source — live marketplace APIs.** All counts re-verified **2026-09-30**. Reproducible via [Appendix A](#appendix-a--api-recipes).

**Third-party dataset used as a cross-check:**
[`poko8725/jetbrains-marketplace-scan`](https://github.com/poko8725/jetbrains-marketplace-scan) — snapshot 2026-08-03, covering all 404 paid plugins. Used for the price distribution, the 2026 cohort, and the conversion arithmetic in [9.2](#92-the-market-distribution).

Three of that author's own published retractions are worth noting, because they show the dataset is honest:
1. Downloads vs. releases correlate at ρ = **+0.185** — weaker than plugin age (+0.368).
2. An apparent "vendor portfolio effect" turned out to be age.
3. A comparison against the Atlassian Marketplace floor was a unit mismatch.

**JetBrains commercial terms** are from JetBrains' own published vendor documentation (15% / 85%, $200 minimum payout, annual payment).

**Session history.** The full research trail — including the seven rejected candidates, the corrected TeamCity misidentification, and the raw API probes — came from a prior working session on an unrelated product launch. This repo is the consolidated output of that work. Nothing here depends on that session; this document is self-contained.

### A note on what is and is not proven

| Claim | Status |
|---|---|
| The marketplace channel works with zero audience | ✅ **Structural** — no followers required, demand is published |
| A paid integration plugin can sell 200k–270k downloads at $15–18/yr | ✅ **Observed twice**, both 2026, both the same vendor |
| JetBrains' own Perforce plugin has been broken for 12 years | ✅ **10 dated reviews, 2014 → 2026, newest 2025-01 and 2026-03** |
| The pain is **widespread** | ❌ **Not proven.** Seven voters. This is what the 10 interviews are for. |
| People will pay **for this specific plugin** | ❌ **Unknown.** Trial → paid conversion is not publicly derivable. |
| The incumbent is not bundled | ⚠️ **Suggestive only.** Verify in the Day-0 gate. |

---

*Compiled 2026-09-30. Every number in this document is reproducible from a public endpoint in under two minutes — if a future reading finds a figure that no longer matches, trust the endpoint and correct this file.*
