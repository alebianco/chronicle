---
id: COMMERCIAL_VIABILITY_REPORT
title: Chronicle — Market Research & Commercial-Viability Report
type: research
created_date: '2026-09-01'
---

# Chronicle — Market Research & Commercial-Viability Report

*Research date: 2026-07-12. Method: deep-research workflow (5 search angles, 24 sources fetched, 112 claims extracted, 25 top claims put through 3-vote adversarial verification — 14 confirmed 3-0, 1 refuted 0-3, 10 unverified due to infrastructure limits and flagged as such) + full read of the three prior artifacts, `RESEARCH_FINDINGS.md`, `PRODUCT_BACKLOG.md`, `docs/`, and a live repo audit. Claim tags: **[V]** = verified against a primary source this session (3-0 adversarial vote or direct repo inspection); **[S]** = single-source or secondary-source web finding, not adversarially verified; **[I]** = inference/estimate.*

---

## 0. Executive summary — the verdict up front

**Keep Chronicle free and open-source. Do not open a partita IVA for it. Do not build a paid tier now.** Add a zero-obligation donation link (GitHub Sponsors/Ko-fi) treated as occasional income, and — this is the load-bearing recommendation — **spend the effort you would have spent on monetization on making the repo agentic-first instead** (§7), because for a solo developer with a day job, agent throughput is worth more than any plausible revenue this niche can produce.

The reasoning compresses to four numbers:

1. **The niche is real but tiny on Android.** The only free, open-source Audiobookshelf Android client with public numbers (Lissen) has ~8.6k lifetime downloads [S]. Chronicle's paid analogue succeeds on iOS (Prologue: one-time $9.99/$17.99 IAP, 4.9★ from ~5.5k ratings, actively shipped through June 2026 [V]) — but on iOS, without a free competing fork, and with Audiobookshelf support Chronicle doesn't have.
2. **A free, more-active fork of this exact app already exists.** fabiogermann's "Chronicle Epilogue" (214 commits ahead, releases through June 2026, own site and subreddit) ships the premium features free [V, from RESEARCH_FINDINGS]. Any paid Chronicle must answer "why pay?" against a free sibling with the same GPLv3 code.
3. **Italy taxes the marginal euro at ~35–45% and the developer is barred from the flat-tax regime.** With RAL > €45,000, the €35,000 employment-income ceiling for regime forfettario (confirmed for 2026 [V]) excludes him. Any structured revenue means ordinary partita IVA: marginal IRPEF 33–43% + addizionali, INPS Gestione Separata ~24%, and ~€1,000–2,000/yr of accounting — fixed costs that exceed plausible revenue at niche scale (§3b table: at €3,000/yr gross Play revenue, net-in-pocket ≈ **€0–300**).
4. **The compliance asymmetry is decisive.** Free + occasional donations ≈ zero paperwork. Even modest paid revenue triggers P.IVA, INPS, invoicing to Google Ireland under reverse charge, and an annual commercialista bill. At every adoption scenario short of a breakout (≥ €8–10k/yr gross, sustained), the paperwork eats the profit.

**Revisit trigger:** only if Chronicle Unabridged reaches ~5,000+ MAU **and** has a differentiator Plex can't copy and the free fork doesn't have (the R4 enrichment layer + client-built Continue Listening), reconsider a Prologue-style one-time unlock (€6–8) on a **new** Play listing — and only if the developer is willing to open and maintain an ordinary partita IVA. Kill-criteria and the conditional plan are in §5–6.

---

## 1. Inputs reconciled — what the three artifacts and repo docs contributed

The three prior artifacts turned out to be **design/branding/condensation artifacts, not market research** — an important correction to this brief's framing:

| Artifact | What it is | What this report takes from it | What it revises |
|---|---|---|---|
| `266e071b…` "Chronicle — Redesign Mockups" | Three high-fidelity screen mocks (player/home/library) executing the §3.1 design brief; chapter-segmented progress bar as signature | Confirms the product has a coherent, differentiated design direction worth shipping (R3) — a prerequisite for *any* paid ambition (nobody pays for the current "engineer's UI") | Nothing to revise; contains no market claims |
| `119503b7…` "Chronicle — Research & Backlog" | Condensed dossier of RESEARCH_FINDINGS + PRODUCT_BACKLOG (verdict: "Invest"; 5 releases, 30 items) | The Trust→Comfort→Delight→Differentiation logic and the D1–D8 decisions are adopted wholesale; D1 (sideload first) and D4 (premium gate disabled) are load-bearing for the commercial verdict | Its "Invest" verdict is about *effort*, not *revenue* — this report makes the revenue question explicit and answers it negatively |
| `48ef2a60…` "Chronicle Unabridged — Branding Proposals" | Icon/wordmark system ("Unabridged." with amber full stop; Spine icon recommended) | Brand is ready if a Play listing ever happens; notes branding must stay All-Rights-Reserved while code is GPLv3 (Epilogue precedent) | The wordmark's own footnote — "stays true only as long as D4 holds (nothing paywalled)" — is a real constraint: the name *Unabridged* ("the complete, uncut edition") semantically fights a freemium gate. A paid tier would undermine the brand story. **New observation this report adds.** |
| `RESEARCH_FINDINGS.md` (in repo) | Ownership/modernization/feature-gap study | Fork-harvest table, Plex API gaps, the collaborate-vs-harvest gate, SDK-36 analysis, §3.1 design brief | Its SDK-36-by-2026-08-31 claim is **confirmed** [V]: verifiers found Google's published API-36 step-up (new apps/updates by Aug 31 2026, extension to Nov 1 2026); a claim that it was "extrapolated, not documented" was refuted 0-3 |
| `PRODUCT_BACKLOG.md` (in repo) | PM prioritization, north-star "zero interventions" | D1–D8 decisions; R0–R4 sequencing; the backlog items that a paid tier would map onto (§5) | D4 ("premium gate disabled") is *reaffirmed* by this report's §3b analysis — it is not just a household choice, it is the fiscally rational one |
| `docs/09` + `docs/tasks/*` | 26-item debt roadmap, ~15–22 weeks | The §7 agent-hostility audit and the effort baseline that any revenue must amortize | Two facts are stale (see §7): ProGuard is no longer near-empty (170 lines, in-flight [V]) and CI already runs unit tests + an emulator job [V] — but the emulator job is likely a silent no-op because `DebugAndroidTest` tasks are disabled in `app/build.gradle.kts:139` [V] |

---

## 2. Market sizing — how thin the serviceable market really is

Anchor: Chronicle is a **self-hosted-first Android client for Plex audiobook libraries**. Not "the audiobook market."

### Concentric segments

| Ring | Definition | Size estimate | Basis |
|---|---|---|---|
| **TAM-ish context** | Plex registered users | ~25M registered (2025, TechCrunch-attributed) [S]; "42M+ MAU" circulates but from a low-quality aggregator — treat as unreliable [S] | expandedramblings.com, techcrunch.com |
| **Ring 1 — the true core** | Plex users with *audiobook* libraries on Android | **~50k–250k people worldwide [I]** — audiobooks are an unsupported, convention-driven hack on Plex (Audnexus/seanap tagging; no audiobook library type; on-deck absent for music [V, RESEARCH_FINDINGS]). Upstream Chronicle's Play listing, the historical sole client, has order-10k–100k installs; Prologue's ~5.5k *ratings* (iOS, richer market) implies low-hundreds-of-thousands of *users* at the very top |
| **Ring 2 — self-hosted audiobook users broadly** | Audiobookshelf + Jellyfin + Plex audiobook households | ABS: ~13.5k GitHub stars, ~1k forks, v2.35.1 May 2026, iOS TestFlight beta **saturated at Apple's 10k-tester cap** [S] — the strongest demand signal in the whole study, and it points at *Audiobookshelf*, not Plex. Order 100k–500k households [I] |
| **Ring 3 — own-your-files Android listeners** | DRM-free/local-file players | Large: Smart AudioBook Player 5M+ installs, 4.82★ from ~197k ratings, one-time unlock, active June 2026 [V]. Proves Android users **do pay small one-time fees at scale** — but for *local playback*, not server clients |
| **Ring 4 — commercial mainstream** | Audible, Libby, Spotify, Storytel | Context/pricing anchors only. Not addressable: DRM ecosystems |

### The two market-moving dynamics (2025–2026)

- **Plex's price shock and paywall wave** [S, multiple contemporaneous sources]: Apr 29 2025 — Plex Pass $4.99→$6.99/mo, $39.99→$69.99/yr (+75%), lifetime $119.99→$249.99; free remote streaming ended (Remote Watch Pass $1.99→$2.99/mo); lifetime to **$749.99 on Jul 1 2026**; Nov 2025 remote-streaming enforcement. Community polls show Jellyfin leading new installs ~2:1 [S, unreliable source]; Jellyfin at ~50k GitHub stars / 360M+ Docker pulls [S]. **Implication: Chronicle's Plex-only core ring is shrinking or at best static, while the Audiobookshelf ring grows.** This independently validates the backlog's R4 multi-backend bet — Prologue's v4.0 (Jan 2026) was literally a ground-up rebuild to add Audiobookshelf [V].
- **A tangential but relevant datum:** a user report says Plex's remote-streaming paywall did **not** bite third-party audio apps (Prologue kept streaming remotely without payment) [S, single comment]. Fragile — Plex could close it any day, and that risk lands on Chronicle too (§6).

### SOM — what Chronicle Unabridged can actually capture

A new fork, new applicationId, no Play listing, competing against (a) upstream's existing listing, (b) the free Epilogue fork with its own site/subreddit, (c) the free official ABS app + free Lissen on the ABS side. Realistic 24-month captures [I]:

- **Conservative:** 500–2,000 installs, 100–500 MAU (Lissen-like trajectory: ~8.6k lifetime downloads, ~540/mo [S])
- **Base:** 3,000–10,000 installs, 500–2,000 MAU (upstream-Chronicle-like, helped by reliability + design wins)
- **Optimistic:** 20,000–50,000 installs, 4,000–10,000 MAU (requires the R3 design + R4 differentiators shipped, ABS backend, and the Epilogue community effectively conceding)

Paying conversion for a niche utility one-time unlock: 2–5% of MAU [I, industry rule-of-thumb; Prologue's ratings-to-user ratio is consistent with this]. That yields **€100–700/yr gross (conservative), €400–2,500 (base), €3,000–12,000 (optimistic)** at a €5–8 price point. Hold these against §3b.

---

## 3. Competitive landscape

### Competitor matrix

*Chronicle column [V] from repo/issues; competitor cells [V] where marked (3-0 adversarial verification), otherwise [S] from listings/sites fetched 2026-07-12 or [I] carried from RESEARCH_FINDINGS §3.*

| App | Platform | Source/server model | Price & monetization | OSS? Licence | Scale signal | Activity | How it monetizes — and does it work? |
|---|---|---|---|---|---|---|---|
| **Chronicle (upstream)** | Android | Plex only | Free + one `premium` IAP (offline + speed) [V] | GPLv3 | 241 GH stars; Play installs unknown (order 10k–100k [I]) | Semi-active (last commit Nov 2025) | IAP presumably beer money for one dev; listing owned by upstream (#124) |
| **Chronicle Epilogue** (fabiogermann) | Android | Plex only | **Free — premium gate disabled** [V] | GPLv3 (branding ARR) | 21 stars, own site/subreddit | Active, v0.62.0 Jun 2026 | Doesn't. Deliberately free. **This is the free alternative any paid Chronicle must beat** |
| **Prologue** | iOS/watchOS/CarPlay | Plex + Audiobookshelf (v4.0, Jan 2026) | Free + **one-time IAP**: Premium $9.99, Family $17.99, Watch $4.99, tips $0.99–5.99 **[V 3-0]** | Closed | 4.9★, ~5.5k US ratings **[V]** | Very active: v4.0 rebuild Jan 2026, v4.2.2 Jun 2026 **[V]** | **Yes — the existence proof.** Solo dev → registered company (Prologue Audio Pty Ltd). One-time unlock users explicitly praise. Works because: iOS wallet-share, no free fork, design bar, dual backend |
| **plappa** | iOS | Audiobookshelf + Jellyfin | Free core + one-time (~$5) or subscription IAP for downloads/customization [S] | Source on GitHub (LeoKlaus/plappa) | small; solo dev | Active [S] | Freemium in the exact niche, on iOS — modest, sustainable-as-hobby [I] |
| **Plexamp** | Android/iOS/desktop | Plex music (audiobook-weak) | Requires Plex Pass [S] | Closed | Large (Plex flagship) | Active | Monetizes *Plex*, not itself. Audiobook UX remains poor — the gap Chronicle lives in |
| **Audiobookshelf official app** | Android/iOS | ABS | Free | GPLv3 | Server: ~13.5k stars; iOS beta full at 10k TestFlight cap [S] | Active, "beta" for years | Doesn't (donations to project). Its beta quality is the opening third-party clients exploit; ABS **explicitly allows closed-source and paid third-party clients** [S] |
| **Lissen** | Android | ABS | **Free** | MIT, on F-Droid | ~8.6k downloads, ~540/mo [S] | Active | Doesn't. **The realistic install-scale mirror for a niche self-hosted Android client** |
| **Booklore / ShelfPlayer** | Android / iOS | ABS | Free / free | OSS | tiny | varies | Don't monetize; hobby tier |
| **Smart AudioBook Player** | Android | Local files | Free 30-day full trial → one-time unlock (~$2–3) **[V 3-0 on scale; unlock mechanic S]** | Closed | **5M+ installs, 4.82★/~197k ratings [V]** | Active (Jun 2026) [V] | **Yes, at scale** — but for local playback, a 100× bigger ring than server clients |
| **Voice** | Android | Local files | Free | GPLv3, F-Droid | ~100k+ installs [I] | Active | Doesn't; the F-Droid free-forever archetype |
| **Listen Audiobook Player** | Android | Local files | Paid upfront ~$1–2 [I] | Closed | ~100k [I] | Slow | Modest one-time; predates modern freemium norms |
| **BookPlayer** | iOS | Local + cloud sync | Free + tip jar / Pro | GPLv3 | popular in niche | Active | Tip-jar + Pro on GPL code — precedent that GPL + IAP coexists on an app store |
| **Audible** | all | DRM store | $14.95/mo credits | Closed | dominant | — | Anchor: normalizes ~$10–15/mo for *content*, $0 for the player |
| **Libby** | all | Library lending | Free (library-funded) | Closed | huge | — | Anchor: the "free, delightful, bookish" UX bar |
| **Spotify / Storytel** | all | Streaming subs | $11–20/mo tiers | Closed | huge | — | Anchor: content bundles; irrelevant to BYO-files users except as churn source |

### The §2 question answered directly

**Does a paid self-hosted audiobook client exist and succeed anywhere? Yes — Prologue, on iOS [V].** One-time unlock, solo-dev-grown-into-company, high ratings, aggressive release cadence. **On Android specifically: no verified example.** The Android niche is served entirely by free OSS (Epilogue, Lissen, ABS app, Voice), and the largest *paid* Android audiobook success (Smart AudioBook Player) is a local-file player addressing a ring ~20–100× larger.

**What do the successful free ones do instead?** Two patterns: (a) the *project-level* donation model where even flagship Jellyfin needed only ~$600/mo and **publicly asked people to stop donating**, redirecting money to client developers as the ecosystem's under-funded tier [V-quality primary, opencollective.com/jellyfin]; (b) the *patronage/acquisition* model — Immich's team went full-time only when FUTO bought the project, after which they added **$25/individual, $100/server "product keys" that unlock nothing** (pure support purchases under AGPL) [S, immich.app]. Typical niche-OSS donation income is **$0–500/month** [S]; Caleb Porzio's $100k/yr GitHub Sponsors is a developer-tooling outlier requiring an audience Chronicle can't reach [S].

---

## 4. Monetization models — evaluation and verdicts

Scored against: realistic revenue at §2 scale, self-hoster willingness-to-pay, GPLv3 + Play + Plex ToS legality, platform-fee drag, maintenance burden, community reputation, and the free-Epilogue test. §3b's tax lens is folded into every verdict.

| # | Model | Realistic gross/yr at base adoption | Legal/licence | Community | Net after §3b | **Verdict** |
|---|---|---|---|---|---|---|
| 1 | **Free + OSS, no monetization** | €0 | Clean | Best | €0, **zero overhead** | **PURSUE (default)** — maximizes the actual goals: household daily driver, agentic-dev playground, reputation |
| 2 | **Free + donations** (GitHub Sponsors/Ko-fi) | €50–500 [I, niche-OSS benchmark] | Clean if genuinely liberal donations; recurring project-tied donations risk reclassification as self-employment income (§3b) [S] | Fine (norm in Jellyfin ecosystem [S]) | ~€30–350 if kept occasional; zero fixed cost | **PURSUE (capped)** — add the links, never attach rewards/tiers that promise anything, keep < ~€2–3k/yr and treat as redditi diversi; above that, commercialista |
| 3 | **Freemium one-time unlock** (Prologue clone) | €400–2,500 base; €3–12k optimistic | GPLv3 allows selling [V — FSF: "charge as much as you wish"; GPLv3 §4]; but buyers may lawfully redistribute the APK gratis, and **anyone can rebuild from source without the flag** — the gate is honor-system on GPL code [V]. Play 15% tier applies (first $1M) [I, well-known] | Risky next to free Epilogue; "Unabridged" branding contradicts a gate (§1) | **Negative to ~€1k** at conservative/base (P.IVA fixed costs ≥ revenue); ~€3–6k net only in the optimistic tail | **CONDITIONAL** — only viable after the §5 triggers fire; not now |
| 4 | **Paid upfront on Play** | Lower than #3 (install friction) | Same GPL caveats, worse: zero trial funnel | Worse | Worse | **REJECT** |
| 5 | **Open-core / paid companion service** (hosted sync, enrichment API, cross-device continue-listening) | Could exceed client revenue *in theory*; requires running a service | Client stays GPLv3 (fine); service code can be proprietary (yours) | Mixed | Server costs + VAT-on-subscriptions + **ongoing service liability for a solo dev with a day job** — the §3b compliance burden plus an SLA | **REJECT for solo/day-job reality** — this is the only model with real upside, and it is exactly the one the developer has no hours to operate. Revisit only if the project ever has >1 maintainer |
| 6 | **Relicense / fair-source** | — | **Impossible.** Chronicle is a fork; upstream (and 67 forks' contributors) hold copyright; GPLv3 code cannot be relicensed without every contributor's consent [V by licence mechanics]. Only from-scratch code could be dual-licensed | Hostile | — | **REJECT (legally foreclosed)** |
| 7 | **Ads** | €10–100 [I] | Play-compatible but GPL-community-toxic; ad SDKs are proprietary blobs in a GPL app (licence friction) and telemetry poison for the homelab audience | Fatal | Trivial | **REJECT** |
| 8 | **B2B / white-label** (NAS vendor bundle, Plex-adjacent) | Lottery ticket; a NAS vendor licensing deal would be €5–50k one-off [I] | GPL fine (they get the code anyway — what they'd pay for is branding/support/integration, which GPL doesn't oblige you to give) | Neutral | One-off contract income = occasional self-employment, workable | **REJECT as strategy, ACCEPT if it knocks** — not worth pursuit effort; if Synology/UGREEN ever emails, take the call |

**Ranked shortlist:** 1 (free OSS) → 2 (donations, capped, zero-obligation) → 3 (freemium unlock, dormant behind §5 triggers). Everything else rejected.

---

## 5. §3b — The Italian tax & legal reality (decisive)

*Decision-useful analysis, not tax advice; the two or three load-bearing figures below should be confirmed with a commercialista before any paid launch. Sources: verified web findings this session + INPS circolare 8/2026 [S/V as tagged].*

### 5.1 The developer's marginal position

- **IRPEF 2026 brackets [V]:** 23% to €28k · **33%** €28,001–50,000 (cut from 35% by Legge di Bilancio 2026) · 43% above €50k. Addizionali regionali (~1.2–3.3%) + comunali (0–0.9%) on top → call it **+2.5%** typical.
- With RAL > €45,000, taxable employment income (after ~9–10% employee social contributions) sits around €40–42k → **every incremental euro of app income is taxed at 33% + addizionali ≈ 35.5%, and at 43% + addizionali ≈ 45.5% beyond ~€50k taxable** [I from verified brackets]. A headline "€10,000 revenue" is a €5,500–6,500 headline after income tax alone — before INPS and fees.

### 5.2 Which legal vehicle is even available

| Vehicle | Available? | Why / key numbers |
|---|---|---|
| **Prestazione occasionale** | **No, for recurring app revenue.** | The €5,000/yr figure is only the *INPS Gestione Separata exemption* threshold, not a P.IVA licence [S]. P.IVA becomes mandatory when activity is **habitual and continuous** — monthly Play payouts are the definition of habitual [S]. Usable only for genuine one-offs (e.g., a single white-label contract) |
| **Regime forfettario** | **Barred.** | Access requires prior-year employment income ≤ **€35,000** (raised from €30k for 2025, **confirmed for 2026** by the Ddl Bilancio 2026; reverts to €30k from 2027 absent renewal) [V]. RAL > €45k → excluded both years. (For completeness: had it been available — €85k revenue cap, ATECO 62 coefficiente 67%, 15% or 5% substitute tax — it would have changed this report's conclusion. It isn't.) |
| **Ordinary partita IVA (regime semplificato)** | Yes — the only structured option. | Full marginal IRPEF + addizionali on profit; **INPS Gestione Separata at the *reduced* ~24% rate** (because already covered by the employee pension scheme; the 26.07% full rate — 25% IVS + 0.72% + 0.35% ISCRO, set by INPS circolare n. 8 of 3 Feb 2026 — applies only to those without other coverage) [S, corroborated by multiple tax publications]; contributions are deductible. Plus: annual VAT return, fattura elettronica, VIES registration and **reverse-charge self-invoicing on the Google Ireland leg** (Google is merchant of record for consumer VAT [V], but the developer-to-Google service fee flow still creates B2B obligations), and a commercialista at **€1,000–2,000/yr** [I, market rate] |
| **No vehicle (donations as liberalità / redditi diversi)** | Yes, at small scale. | Genuine, spontaneous, no-consideration donations to an individual are not business income; but Italian guidance treats **recurring donations tied to an ongoing project as fiscally relevant**, and reward-tiers are a VAT-relevant "pre-purchase" [S, fiscomania/crowdfunding analyses]. Keep it: no tiers, no promised perks, modest totals, declare as redditi diversi if non-trivial. Reclassification risk grows with recurrence and amount |

**Employer/CCNL gate [I, flag]:** Italian employment contracts + the duty of loyalty (art. 2105 c.c.) commonly restrict secondary self-employment; many CCNLs require notice or authorization, and IP clauses may reach side projects built with any employer overlap. **Check the contract and, if opening a P.IVA, inform the employer in writing.** For the free/OSS path this is near-zero risk (hobby OSS is normal); for a revenue path it is a real gating item that costs nothing to check and everything to ignore.

### 5.3 The net-in-pocket table (freemium unlock on Play, ordinary P.IVA)

Assumptions: Play fee 15% (under $1M tier); Gestione Separata 24% (reduced, employee-covered), deductible; marginal IRPEF+addizionali 35.5% on the first ~€8k of extra taxable income, 45.5% beyond (taxable crosses €50k); commercialista + P.IVA fixed costs €1,200/yr [I]. Google handles consumer VAT as merchant of record [V].

| Scenario | Gross Play revenue | −15% Play fee | −INPS GS 24% | Taxable | −IRPEF marg. | **Net in pocket** | Net % | −€1,200 fixed costs → **truly net** |
|---|---|---|---|---|---|---|---|---|
| Conservative | €1,000 | €850 | €204 | €646 | €229 | €417 | 42% | **−€783 (loss)** |
| Base | €3,000 | €2,550 | €612 | €1,938 | €688 | €1,250 | 42% | **€50** |
| Base+ | €6,000 | €5,100 | €1,224 | €3,876 | €1,376 | €2,500 | 42% | **€1,300** |
| Optimistic | €12,000 | €10,200 | €2,448 | €7,752 | €2,868 | €4,884 | 41% | **€3,684** |
| Breakout | €25,000 | €21,250 | €5,100 | €16,150 | €6,700 [I, blended 41.5%] | €9,450 | 38% | **€8,250** |

**Readings:**
- **Below ~€3,000/yr gross, the paid path loses money.** The commercialista alone out-earns the app.
- Even at the optimistic €12k gross — which §2 says requires the top of the adoption range — the developer nets **~€3.7k/yr ≈ €300/month** for carrying a P.IVA, VAT filings, Play merchant obligations, refund handling, and the reputational cost of paywalling a GPL fork against a free sibling.
- The donation path at even €500/yr with **zero fixed costs and zero paperwork** is competitive with the base paid scenario. That asymmetry, not the gross numbers, is the decision.
- Break-even for "meaningfully worth it" (say, net ≥ €5k/yr ≈ a good month of RAL): requires **~€16–18k/yr sustained gross** → ~3,000+ paid unlocks/yr at €6 → needs ~60–150k MAU-scale funnel [I]. That is Prologue-on-iOS territory, on the platform where the niche pays, without a free fork. Not this market.

---

## 6. Core recommendation

**Recommendation.** Chronicle stays **free and GPLv3**, monetized by nothing except an optional, no-strings donation link. The premium/IAP plumbing stays dormant (D4 reaffirmed). The developer's scarce resource is hours, not euros: every hour goes to the R0–R4 backlog executed agent-first (§7), which is what actually compounds — a reliable daily driver for the household, a portfolio-grade OSS project, and an agentic-development testbed. The realistic financial picture (net −€800 to +€3.7k/yr across scenarios, §3b) does not clear the bar of the paperwork it would create, the employer-notification risk it would trigger, and the brand/community cost of paywalling a fork whose free sibling is more active.

**The strategic tension (fabiogermann) resolves the same way.** Commercially, Epilogue is a kill-shot for a paid Chronicle ("why pay? the free fork is more active and has the same fixes"). But for a free Chronicle it is an asset: D2's harvest strategy converts their 214 commits into your reliability roadmap at GPL-zero cost. Collaborate-vs-harvest-vs-differentiate: **harvest now (D2 stands), differentiate at R4** (enrichment layer + client-built Continue Listening + ABS backend — none of which Epilogue has), and keep collaboration open as the cheap upside (upstreaming small fixes costs nothing and buys goodwill).

**Licensing reality.** GPLv3 permits selling at any price [V] but guarantees free redistribution and source access — a paid GPL app on Play is legal (BookPlayer precedent) yet structurally leaky, and the VLC-2011-style "Play ToS as further restriction" debate is unresolved tail risk [V-adjacent]. Relicensing is foreclosed: upstream + contributors own the copyright. Branding (Unabridged name/icon) is the only asset that can be ARR — protect it as Epilogue does.

**Conditions & triggers to revisit revenue (all four, not any):**
1. **Traction:** ≥ ~5,000 MAU sustained (instrument via opt-in, privacy-respecting counter — this niche audits telemetry).
2. **Differentiation shipped:** R4 enrichment + Continue Listening + ABS adapter live and demonstrably ahead of Epilogue and the free ABS clients.
3. **Fiscal appetite:** developer explicitly willing to open/maintain ordinary P.IVA and has cleared the employer/CCNL check.
4. **Precedent holds:** Prologue-style one-time unlock still performing on iOS; no Plex API/ToS rug-pull in the interim.

**Kill-criteria (any one keeps it free):** MAU plateau < 2k after R3 ships · Epilogue adopts the same differentiators first · Plex restricts third-party audio clients · employer refuses authorization · the €35k forfettario window is irrelevant but if RAL ever drops below the threshold, re-run §3b (forfettario at 15%/67% coefficiente roughly **doubles** net-in-pocket and moves base-case net to ~€1.5–2k — still modest, but it changes the slope).

---

## 7. If revenue is ever pursued — the dormant plan (conditional)

Kept deliberately short; activates only on §6's four triggers.

- **Mechanics:** Prologue-clone freemium. Free: full Plex playback, streaming, progress sync. Paid one-time unlock **€5.99–7.99** (Prologue-anchored, Android-discounted): offline downloads (mirrors upstream's original gate), stats/streaks, enrichment facets, themes/widgets. Play Billing (Google MoR for VAT [V]); no subscriptions — the niche hates them and praises Prologue's one-time model [V]. F-Droid build stays fully unlocked (freeAsInBeer spirit; the honor-system reality of GPL makes this honest rather than naive).
- **Revenue-relevant roadmap items** (vs pure hygiene): R1 items 6–9 (progress/downloads/resiliency — the *trust* that justifies paying) → R2 13–14 (Continue Listening, chapter-aware UX) → R3 21–22 (the design that survives a screenshot next to Prologue) → R4 27–28 (ABS adapter + enrichment = the "why this one" answer). Pure hygiene (KSP, dispatchers, tests) is monetization-neutral but agent-critical (§8).
- **Distribution:** new Play listing (upstream owns the old one [V]) under `dev.alebianco.chronicle.unabridged`; ship **targetSdk 36 before Aug 31 2026** (the deadline is documented, refutation-tested [V]) or launch after with API-36 already in place; F-Droid in parallel for the community.
- **Go-to-market:** smallest viable paid = offline-downloads unlock only, one price, no tiers; announce in r/PleX, r/audiobooks, r/selfhosted with the OSS story front and center; measure conversion for two quarters before adding any second paid feature.

---

## 8. Risks and the honest bear case

1. **The market may be a puddle.** Ring 1 could be 30–50k people globally [I]; Lissen's 8.6k downloads is the observed scale of "niche self-hosted Android client" [S]. Optimistic scenarios assume capturing a third of a shrinking pond.
2. **Self-hosters' willingness-to-pay is real but rationed** — they pay for *servers* (Plex Pass), *causes* (Immich keys), and *iOS polish* (Prologue); Android + OSS + fork ≠ any of those three. Jellyfin's "please stop donating" and client devs as the acknowledged under-funded tier [S] is the ecosystem telling you the client layer doesn't fund itself.
3. **Plex platform risk, sharpened by 2025–2026:** Plex is visibly pivoting to ad-supported streaming and monetizing remote access [S]; the unofficial endpoints Chronicle lives on (`/:/timeline`, scrobble, websockets) are guaranteed to nobody [V, RESEARCH_FINDINGS], and the "third-party audio apps still stream free" loophole [S] is one policy commit from closing. A Plex audiobook-UX investment (unlikely) or an API/ToS clampdown (plausible) both hurt.
4. **The free fork checkmate:** every commercial scenario in this report dies first at "Epilogue exists, is free, and ships faster." Differentiation is a treadmill against a maintainer with more momentum.
5. **GPL leakage:** a paid GPLv3 app's unlock is socially, not technically, enforced. In a homelab audience that compiles things for fun, expect the paid features rebuilt-free within weeks (Epilogue already shipped exactly that once [V]).
6. **Solo-maintainer burnout with a day job:** ~15–22 weeks of debt (docs/09) plus R1–R4 plus, under a paid model, *customer obligations* (refunds, Play policy strikes, angry reviews at 2am). Money converts a hobby into a liability with a 42% tax rate.
7. **Opportunity cost:** the same hours invested in the agentic-first capability (§9) transfer to every future project and to the developer's actual career; hours invested in P.IVA administration transfer to nothing.
8. **Steel-manning "keep it free":** the household gets the product either way; the developer's stated north-star is *zero interventions*, not revenue; the free path maximizes fork-harvest goodwill, keeps the Unabridged brand honest, adds zero fiscal surface, and leaves the option value intact — §6's triggers can fire in 2027 just as well, with a stronger product.

---

## 9. Agentic-first codebase — prioritized, concrete changes

**Fresh audit (this session, 2026-07-12), correcting the brief where stale [V]:**
- No `.claude/`, no `CLAUDE.md`/`AGENTS.md`, no `.mcp.json` at root. Two Copilot instruction files exist — and `copilot-instructions.md` **lies to agents**: it claims "Dagger 2 (KSP)" and "KSP is used (Moshi, Room, Dagger)" while the build uses KAPT (`kotlin-kapt` at `app/build.gradle.kts:5`; `kapt(...)` at :98/:102/:114/:126).
- Exactly **2 unit-test files**; 7 androidTest files exist **but `DebugAndroidTest` tasks are force-disabled** (`app/build.gradle.kts:139`) — while `.github/workflows/ci.yml` runs a full emulator matrix job (`instrumented-test`, reactivecircus runner, AVD cache): **CI likely runs a silent no-op and reports green.** This is the single most agent-hostile fact in the repo: it teaches an agent that "instrumented tests pass" means nothing.
- CI is otherwise better than the brief assumed: ktlintCheck, assembleDebug + APK artifact, `testDebugUnitTest` + results artifact all run [V]. No coverage gate.
- `proguard-rules.pro` is **no longer near-empty**: 170 lines, in-flight uncommitted (with `test_release_build.sh`) [V].
- `NOTES.md`'s `signFreeAsInBeerReleaseBundle` flow is **stale** — no product flavors exist in `app/build.gradle.kts` anymore [V].
- Room `2.7.0-alpha12` in production; targetSdk/compileSdk 34; single `:app` module [V].

### The checklist (do in order; effort in ideal solo-dev days)

**P0 — create the verification loop (≈3–4 days; delivers ~80% of the gain together with P0.4)**

- [ ] **1. `CLAUDE.md` at repo root** (~0.5d). Outline: *Project snapshot* (Plex-only Android audiobook player, single `:app`, minSdk 27 / target 34→36, MVVM + Dagger 2 **via KAPT** + Room **2.7.0-alpha12 (alpha — never bump casually)** + Media3 1.3.0 + LiveData/DataBinding); *Verify loop* — the one command block agents must run before claiming done: `./gradlew ktlintFormat ktlintCheck testDebugUnitTest assembleDebug lintDebug`; *Module map* (the "Files to Know" list from copilot-instructions, corrected); *Gotchas* (KAPT not KSP; alpha Room migrations; `DebugAndroidTest` status; Plex unofficial endpoints; no 401 re-auth; `data/sources/MediaSource.kt` is dead scaffolding with `TODO()`s); *Definition of done* (green verify loop + test for any repository/ViewModel touched + docs updated per the docs/ rule); *Never touch without human sign-off* (signing config, `ChronicleBillingManager`/IAP, licence headers, branding assets, Play metadata). Add `AGENTS.md` → one-line pointer to `CLAUDE.md`. **Fold both Copilot files into it and shrink them to pointers** — one source of truth.
- [ ] **2. Fix the lying docs (H3-family, ~0.5d):** correct the KSP claim, delete or rewrite stale `NOTES.md` (no flavor exists), align targetSdk statements. An agent fed wrong context wastes its whole run.
- [ ] **3. Resolve the instrumented-test contradiction (~1d):** either delete `app/build.gradle.kts:139` and make `connectedDebugAndroidTest` actually run (locally via Gradle Managed Devices: add a `managedDevices { pixel2api34(...) }` block so agents can run `./gradlew pixel2api34DebugAndroidTest` headlessly), or delete the CI emulator job and state "instrumented tests are dead" in CLAUDE.md. **Never leave green-but-no-op.**
- [ ] **4. Make unit tests + ktlint required checks** on the repo (branch protection) so agent PRs cannot merge red (~0.5d incl. JaCoCo wiring: `jacocoTestReport` + publish HTML/XML as CI artifact agents can read back).
- [ ] **5. `verify.sh` at root** (~0.5d) wrapping the loop with fail-fast ordering (ktlint → unit → assemble → lint) and a `--quick` flag (ktlint + unit only). Agents get one entry point; humans get the same one.

**P1 — enablers that raise agent success rate (≈2–3 weeks, mostly existing docs/tasks plans)**

- [ ] **6. KAPT → KSP (C2**, plan exists in `docs/tasks/C2`, 3–5d): the single biggest iteration-speed lever; every agent build pays the KAPT tax today. Also flips Moshi to codegen (R8-friendlier — protects the new ProGuard work).
- [ ] **7. Dispatcher injection (H5, 3–4d) + GlobalScope removal (C4, 1–2d) + InternalCoroutinesApi (C5, 2–3d):** deterministic coroutines are what make agent-written tests non-flaky. Sequence before the test push, exactly as docs/09 suggests.
- [ ] **8. Plex API fixture pack (~3d, highest-leverage novel item):** record real Plex responses (login PIN flow, `/api/v2/resources`, library sections, album+tracks with `includeChapters`, timeline/scrobble) into `app/src/test/resources/plex-fixtures/`, plus a `MockWebServer`-backed `FakePlexServer` test rule. This makes the entire sync/progress/download layer — the app's whole risk surface — testable hermetically, no live server, no credentials. Every R1 reliability port (progress overhaul, 401 re-auth, connection tiering) then lands with agent-verifiable tests.
- [ ] **9. Test push on the R1 path (H1, ongoing):** priority order for agents — `PlexProgressReporter`-equivalent logic, repositories (`IBookRepository`/`ITrackRepository`), DAOs on in-memory Room, `CachedFileManager`, then ViewModels via Robolectric where a Looper is needed. Coverage gate: start at the current baseline, ratchet +2%/PR on touched files (JaCoCo `violationRules`), don't set a fantasy 70% bar that blocks everything.
- [ ] **10. Decide LocalMediaSource (C6, 1d):** delete the `TODO()` landmines (backlog says the seam gets rebuilt properly in R1 item 12 anyway).

**P2 — ongoing conventions (≈2–3 days once, then habit)**

- [ ] **11. Agent-sized task decomposition:** convert `PRODUCT_BACKLOG.md` items into GitHub issues with acceptance criteria copied verbatim (the backlog already has them — they're agent-ready), labels `agent-ok` / `needs-human` (signing, billing, branding, Play), and a PR template embedding the Definition of Done checklist.
- [ ] **12. MCP config (`.mcp.json`):** GitHub MCP (issue/PR flow); **mobile-mcp or adb-mcp** for on-device smoke checks when a device/emulator is attached; skip a "Plex MCP" — the fixture pack (item 8) is strictly better (hermetic, deterministic, CI-safe).
- [ ] **13. Docs-sync rule in CLAUDE.md:** any completed backlog item updates its row in `PRODUCT_BACKLOG.md` and, if it changes architecture, the mapped `docs/0X` file — the existing `docs.copilot-instructions.md` path rules fold in here. This is what keeps the context store from rotting as agents work.
- [ ] **14. Worktree convention:** agents work in `.worktree/<branch>` per the user's global convention; never on `develop`.

**The 80/20:** items **1, 3/4, 6, and 8** — a truthful CLAUDE.md, a CI that cannot lie, KSP-speed builds, and a fake Plex server. With those four, an agent can take any R1 backlog item from issue to verified PR without a human in the loop except review.

**How this changes the commercial calculus (one paragraph):** agentic throughput cuts the effective cost of the ~15–22-week debt and the R0–R4 roadmap by a large factor for a solo evening-hours developer — it is the difference between the roadmap happening and not happening. But note the direction of the effect: it lowers the *cost* side of both paths equally, and the free path's cost was the only thing revenue was supposed to offset. Cheap maintenance makes "keep it free" *more* affordable, not less — it removes the last economic argument for needing revenue at all, while keeping the §6 option alive: if agents get the product to R4-with-differentiators at a fraction of the projected effort, the traction trigger gets its fair test for free.

---

## 10. Open questions

1. **Plex audiobook-user count** remains the softest number in the model (Ring 1 is an inference band, not a measurement). An opt-in anonymous ping or a r/PleX survey would bound it cheaply.
2. **Epilogue's intentions:** if fabiogermann ever lists on Play or adds ABS support, both the harvest strategy and any future paid option need re-gating (standing risk from PRODUCT_BACKLOG).
3. **Donation reclassification threshold in practice:** no bright-line euro figure exists for when recurring OSS donations become habitual self-employment income — one commercialista consult (~€150–300) settles the safe ceiling; worth doing before adding the Sponsors link if amounts ever exceed pocket change.
4. **Employer/CCNL stance** on side OSS and on a hypothetical P.IVA — costs one email to HR to check the contract clause; do it before any trigger fires, not after.
5. **INPS Gestione Separata exact 2026 numbers** (26.07% full / ~24% reduced) came from secondary corroboration of circolare 8/2026 [S] — confirm the reduced-rate figure with the commercialista if a P.IVA is ever opened.
6. **Unverified-but-directional claims** (flagged [S] throughout): Plex ~25M registered users; Jellyfin 2:1 new-install polls; ABS 13.5k stars / 10k-full TestFlight; Lissen 8.6k downloads; SABP unlock mechanic; plappa pricing. None are load-bearing for the verdict (which rests on the tax asymmetry [V], Prologue [V], SABP scale [V], GPL/VAT/SDK facts [V], and the Epilogue fork [V]), but re-verify before quoting externally.

---

## 11. Sources

**Adversarially verified (3-0), primary:** apps.apple.com (Prologue id1459223267) · prologue.audio/releases · play.google.com (Smart AudioBook Player) · developer.android.com/google/play/requirements/target-sdk + Play Console Help 11926878 (SDK 35/36 deadlines; the "Aug-2026 undocumented" counter-claim refuted 0-3) · gnu.org/philosophy/selling + gpl-3.0 §4 (selling GPL software) · support.google.com/googleplay/android-developer/answer/138000 + Developer Distribution Agreement (Google as EU VAT merchant of record).

**Search-phase findings, not adversarially verified [S]:** inps.it (circolare n. 8, 3 Feb 2026 — Gestione Separata 2026) · flextax.it, informazionefiscale.it, consulenzaagricola.it, scastudio.com (forfettario €35k 2026 confirmation; €85k cap; ATECO 62 coefficiente 67%) · soluzionetasse.com, cafinforma.it, sgravi.com, laleggepertutti.it (IRPEF 2026: 23/33/43) · fiscomania.com (prestazione occasionale; crowdfunding/donation taxation) · regime-forfettario.it (donations inside a business, art. 88 TUIR) · opencollective.com/jellyfin ("We're good, seriously!") · jellyfin.org direct-donations policy · immich.app (FUTO acquisition; product keys) · calebporzio.com (Sponsors outlier) · reo.dev (OSS donation benchmarks) · github.com/advplyr/audiobookshelf + audiobookshelf.org/docs/faq/app (third-party client policy) · plappa.me · Play listing org.grakovne.lissen + AppBrain · techcrunch.com (Plex users/price hike 2026-06-03) · nascompares.com, selfhostlife.com, tech-insider.org, expandedramblings.com (Plex 2025–2026 pricing fallout; Jellyfin adoption — quality ranges blog→unreliable, used only for direction).

**Repo-internal [V]:** `RESEARCH_FINDINGS.md` (2026-07-05, incl. §11 source register) · `PRODUCT_BACKLOG.md` · `docs/09-project-analysis-and-tasks.md` + `docs/tasks/*` · `app/build.gradle.kts` · `.github/workflows/ci.yml` · `.github/copilot-instructions.md` · `NOTES.md` · `gradle/libs.versions.toml` · the three claude.ai artifacts (mockups `266e071b…`, dossier `119503b7…`, branding `48ef2a60…`).

---

## 12. TL;DR — one page for a busy solo developer

**Verdict: keep it free. Don't open a P.IVA for this. Point the effort at agents, not invoices.**

- **The niche pays on iOS, not on Android.** Prologue (your iOS twin) earns real money with a one-time $9.99 unlock — 4.9★, active, solo-dev-turned-company. On Android, every self-hosted audiobook client is free OSS, the observed scale is ~10k downloads (Lissen), and *your own free sibling fork (Epilogue) is more active than you*.
- **Your tax position kills the economics.** RAL > €45k bars you from forfettario (€35k employment-income ceiling, confirmed for 2026). Ordinary P.IVA means ~24% INPS + 33–43% marginal IRPEF + ~€1,200/yr accountant. Run the table: €1k gross → **you lose €783**; €3k gross → **you net ~€50**; even €12k gross (optimistic beyond reason for this niche) → **~€3.7k net ≈ €300/month** for real merchant obligations. Donations with zero paperwork beat the base case.
- **Legal facts, settled:** selling GPL software is allowed (FSF says so explicitly) but anyone may redistribute it free — the unlock is honor-system. You cannot relicense a fork. Google handles EU consumer VAT on Play. SDK-36 by Aug 31 2026 is real, documented — but only binds if you ever choose Play.
- **Do now:** ① add GitHub Sponsors/Ko-fi, no tiers, no promises, treat as occasional income; ② check your CCNL's side-activity clause (one email); ③ execute the agentic-first P0: truthful `CLAUDE.md`, fix the CI emulator job that silently runs disabled tests (`app/build.gradle.kts:139`), make unit tests a required check, then KSP migration and a recorded fake-Plex-server fixture pack. Those four things let agents carry the R1 reliability roadmap end-to-end.
- **Revisit revenue only when ALL of:** ~5k+ MAU, R4 differentiators shipped (enrichment, Continue Listening, ABS backend), you're genuinely willing to run a P.IVA, and employer sign-off is in hand. Then: €5.99–7.99 one-time unlock, new Play listing, offline-downloads gate, F-Droid stays free. Until then, *Unabridged* means what it says: the complete edition, for everyone.
