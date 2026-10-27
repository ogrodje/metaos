---
target: people
total_score: 25
max_score: 40
na_heuristics: 
p0_count: 0
p1_count: 0
target_identity: "file:/Users/oto/Projects/metaos/fe/src/views/PeopleView.vue"
target_fingerprint: "sha256:c10ee67939d15c45117ffbe6b2e1d8c0053a435f9b6c0c32259e4faad3b33e38"
target_path: /Users/oto/Projects/metaos/fe/src/views/PeopleView.vue
timestamp: 2026-10-27T14-15-23Z
slug: fe-src-views-peopleview-vue
---
Method: dual-agent (A: design review · B: detector + browser overlay)

Scope: one critique of four pages: Home (fe/src/views/HomeView.vue), Episodes (EpisodesView.vue), Ideas (IdeasView.vue) and People (PeopleView.vue), on http://localhost:5173. This same report is saved for each page, each with its own score.

## Design Health Score

| # | Heuristic | Home | Episodes | Ideas | People |
|---|---|---|---|---|---|
| 1 | Visibility of System Status | 2: skeleton rows for up to 20 s, then it quietly switches to the guest page | 2: the count stays at 74 while you filter | 2: no count of matches | 2: no count of matches; jumping to a letter doesn't move focus |
| 2 | Match System / Real World | 3: concerns in plain words; the public copy talks about MCP | 3: seasons and shows | 3: readiness groups match how the team thinks | 2: guests described by codes; "producer 64" |
| 3 | User Control and Freedom | 3 | 3: no Clear all; "Show all" can't be folded back | 3 | 3 |
| 4 | Consistency and Standards | 2: "All" vs "All episodes"; releases square for the team, 16:9 for the public | 3: listen icons don't line up | 2: a native select among chips; "Any show" here vs "Every show" elsewhere | 2: "Show/Fold" vs "Show all N" |
| 5 | Error Prevention | 3: the attention rules prevent misses | 3 | 2: two "Andraž" chips that look the same | 3 |
| 6 | Recognition Rather Than Recall | 3: absolute dates only on hover | 2: listen icons have no labels | 3 | 3 |
| 7 | Flexibility and Efficiency | 2: stage counts don't filter anything | 2: no status filter or sort; 13 tab stops before the first row | 2: no sort | 3 |
| 8 | Aesthetic and Minimalist Design | 3: calm; Recent activity has no limit | 2: two rows of chips; grey tiles | 3 | 3 |
| 9 | Error Recovery | 3: LoadError says what to do | 2: "No episodes match." with no way out | 2: the same, and an error for users not yet approved | 2: the same |
| 10 | Help and Documentation | 2 | 2 | 3: the intro explains how an idea becomes an episode | 2 |
| **Total** | | **26/40, Acceptable** | **24/40, Acceptable** | **25/40, Acceptable** | **25/40, Acceptable** |

## Is the design specific to MetaOS?

**Design review:** the pages are competent and restrained, but they could belong to almost any product. It's a tidy admin in the Linear/Vercel style (Inter, a grey ramp, hairlines and pill chips), filled with podcast data. What makes it feel like MetaOS is the data and logic: episode codes in monospace, the calendar tile on recording sessions, the readiness groups on Ideas, the attention rules worded as findings, and a Slovenian A–Z on People (Č, Š, Ž, skipping "dr."). Ogrodje's own look (black, pink braces, faces) appears only in the thumbnails and a 2 px pink underline. Nothing on the pages signals audio either: no durations, no play button, no clear Listen.

**Automated scan of the source files:** 23 .vue files, 0 findings. That doesn't mean they're clean. For .vue files the detector only matches text patterns, so contrast and other checks that need a rendered page never ran. It missed the hard-coded #C9C9C9 that the overlay then caught.

**Overlay on the running pages:** 9 findings.
- Home: 6 (four low-contrast stage counts, one lone last word in a title, one flat type hierarchy).
- Episodes: none.
- Ideas: 1 (a lone last word in a description).
- People: 2 (low-contrast A–Z letters, a lone last word in a roles line).

Where the two reviews agree: the stage bar's zero counts, flagged by both on their own, at about 1.7:1 contrast. What only the scan caught: the lone last words, and that the stage labels are cut off at desktop width ("In producti…", "Early acce…"). False positives: the flat type hierarchy finding measured the wrong element (the 16.4 px came from an h3 wrapper in SessionCard.vue:27, and no visible text is that size; the conclusion roughly holds anyway, as session names are 14 px under 18 px section headings). The People A–Z letters are hidden from screen readers and mean "nobody under this letter", so they probably fall under WCAG's exception for inactive content.

## Overall impression

The team's logic is strong: what needs attention is worked out properly, filters live in the address, and search ignores diacritics. The look doesn't carry that through. Home opens with eight equal-weight counters that lead nowhere, and the Episodes page, the team's main list, doesn't sort anything by stage. The biggest opportunity: make stage the way the team moves around. Home's stages should lead into Episodes filtered by stage, and Episodes should group what's in progress by stage.

## What's working

1. The attention rules (attention.ts, ConcernRow.vue): concerns ranked (missed recording dates, then the nearest recording, then long in production), worded as findings, the only thing on Home in the accent colour. When nothing is wrong, the page says which rules it checked.
2. Lists you can share: filters and view live in the address, public pages render on the server, search ignores case and diacritics, and one FilterBar and ChipGroup serve every list.
3. Accessibility basics done properly: skip link, focus moves to the new page with its name announced (App.vue:91–97), a visible 2 px focus ring, aria-pressed chips, a search palette built as a real combobox, LoadingRows only after 300 ms, LoadError messages that say what to do.

## Priority issues

1. [P1] Home leads with the stage bar, and it goes nowhere (Home). Eight equal-weight counters sit above "Needs attention". Every count links to /episodes unfiltered (StageBar.vue:15), and Episodes has no status filter. The zero counts are #C9C9C9, about 1.7:1 contrast, on working links (StageBar.vue:71). At desktop width the labels are cut off. Fix: put "Needs attention" first; add ?status= to Episodes (lists.ts, useQueryState) and point each stage at it; show zeros in --muted; let labels wrap or draw one slim pipeline. Command: /impeccable layout fe/src/views/HomeView.vue
2. [P1] "In the works" is one flat list, with On hold and Cancelled mixed in (Episodes). Every status except Published (EpisodesView.vue:64), newest first. Fix: group in the stage bar's order, with the stage as a small heading and its count; fold On hold and Cancelled at the end. Command: /impeccable layout fe/src/views/EpisodesView.vue
3. [P2] When the sign-in check times out, the page quietly becomes the guest page (all four). With the "on the team last time" hint, a page shows skeleton rows for up to 20 s (stores/auth.ts:27, :136), then swaps to the guest layout with no message, and the hint stays set. Triggered in this run by the dev server's HTTP/1.1 limit of 6 connections per host, as every MetaOS tab keeps one open for /events; in production a slow Keycloak looks the same. Fix: after about 3 s show "Checking your sign-in…"; on timeout keep the team's layout and say "Couldn't confirm your sign-in · Try again". Command: /impeccable harden fe/src/stores/auth.ts
4. [P2] Filtering doesn't say what it did (Episodes, Ideas, People). Header and chip counts don't change as you filter, screen readers hear nothing, and the empty states are a bare "No episodes match." (EpisodesView.vue:146, IdeasView.vue:132). Fix: "12 of 74" in an aria-live="polite" region; "No episodes match "vres" · Clear filters". Command: /impeccable harden fe/src/views/EpisodesView.vue
5. [P2] The public side doesn't serve listeners (Home and Episodes, signed out). A visitor's Home opens with "Sign in to use MetaOS", which talks about MCP (HomeView.vue:155). On a phone the releases start below the fold, the cards have no Listen link, and the listen icons are hidden in the phone list (EpisodeRow.vue:214). Fix: releases first, each with Listen, and sign-in as a single line, or decide the public side isn't for listeners. Command: /impeccable layout fe/src/views/HomeView.vue
6. [P2] Ideas: recommender chips that look the same, and controls that don't fit (Ideas). First names only (IdeasView.vue:73), so two Andražes are two identical chips; show and recommender chip groups have no limit; grouping is a native select among chips; the placeholder is cut off at desktop width (:117); group headers aren't headings; loadIdeas checks sign-in, not approval (:36). Fix: a surname initial when first names collide; recommender as a select or avatar menu; "Find ideas"; <th scope="rowgroup">; check auth.team before loading. Command: /impeccable polish fe/src/views/IdeasView.vue
7. [P2] People shows codes, tallies and a very long page (People). Guests described by episode codes; team cards list lowercase tallies including "guest 2"; the A–Z index is hidden on phones (PeopleView.vue:298), leaving 67 guests on a page about 7,000 px tall. Fix: "Producer · 64 episodes" without the guest count; the latest episode's title for guests; a sticky strip of letters on phones. Command: /impeccable adapt fe/src/views/PeopleView.vue

## Persona red flags

- Alex (power user, keyboard first): ⌘K, Ctrl+K and / work well. Every chip is its own tab stop (13 before the first episode row), no way to move between rows with the keyboard, no sort, stage counts can't be used as filters, and every "No doc" concern means opening the episode.
- Sam (screen reader, keyboard only, 200% zoom): filter results aren't announced; season headings read "Season 5 7"; SessionCard's h3 contains the file-icon links; the search palette's group labels are role="presentation" (SearchPalette.vue:249); a concern's absolute date is only in a hover title (ConcernRow.vue:23); at 200% Ideas' fixed columns leave about 180 px for the title.
- Casey (public visitor, phone, one hand): the first screen is a sign-in card about MCP; no Listen in the phone lists; chips about 28 px tall; People about 7,000 px long with no index. Pages rendered on the server and cached do help on a slow connection.

## Minor observations

- Missing pictures are mostly a data problem: in production 27 of 75 released episodes have no thumbnail (locally 64 of 74). Setting them (set_episode_image) fixes more than any design change. EpisodeCard.vue:8's comment says "its code on its show's colour", but the code and CLAUDE.md say grey; settle on one.
- The lone last words depend on the data; text-wrap: pretty fixes them (used only in UsageView.vue:427 so far).
- The disabled A–Z letters are #CDCDCD: hidden from screen readers, but still visual noise.
- Home says "All" (HomeView.vue:204) and "All episodes" (:233) for the same link.
- Home has no limit on Recent activity.
- A visitor's Home shows 6 releases in 4 columns, leaving a row of 2.
- A folded season's "Show all 17" sits at the far right of its heading, nothing marks where the list continues, and it can't be folded again.
- graphql() in the browser has no timeout, so a request that never answers shows skeleton rows forever.

## Questions to consider

1. If Home is the team's cockpit, why does it open on counts rather than "what do I do today?", and why can't a flagged item be fixed where it's flagged?
2. Should stage, not season, organise Episodes for the team, with seasons only for released episodes?
3. Is the public side meant to be a listener's site? If so, where is Listen? If not, what is it for, next to ogrodje.si?
4. What would MetaOS look like in Ogrodje's own visual language instead of neutral grey?

## Run notes

The detector saw the team view on all four pages, signed in. The design review saw the signed-out pages at desktop and phone widths through headless Chrome; in Chrome its tabs hit the connection limit from issue 3, so it judged the signed-in Home and Ideas from the code and the local data.
