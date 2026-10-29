# Product

<!-- impeccable:product-schema 1 -->

## Platform

web

## Users

- **Ogrodje's team**: hosts, co-hosts, producers, designers and consultants who plan, record, produce and release the
  podcast's episodes. They come to MetaOS often, for short tasks (what needs attention, what's recorded next, an
  episode's files, pictures and transcripts), on desktops mostly, and also on phones. Many of them work on the
  episodes through AI assistants over MCP too, and the pages show what those assistants did.
- **Everyone else**: guests, listeners, partners and search engines, signed out. They see what the team made, as a
  window into the team; they're not its audience.

## Product Purpose

MetaOS runs Ogrodje's production: episodes from an idea through recording, production and release, with their people,
recording sessions, files (Google Drive, Koofr, Dropbox), pictures, video and audio, transcripts, discussions and
history, for the team in one place, and for assistants and agents through the same data over MCP and GraphQL.
Success is the team knowing what to do next without asking around, and nothing about an episode living only in
someone's head or inbox.

## Positioning

It's Ogrodje's own production desk, not a podcast host or a listeners' site: [ogrodje.si](https://ogrodje.si) is for
listeners. Its public pages are the same pages the team works on, with the team's parts left out.

## Operating Context

- An episode goes through stages: idea, planned, scheduled, recorded, in production, ready, early access (Patreon),
  published; or on hold, cancelled.
- The team's material for an episode: its titles, A/B tested with thumbnails; artwork in Dropbox; recordings and photos
  in Koofr; preparation docs in Google Drive; the full video and audio and bonuses in MetaOS's bucket; transcripts by
  Google's Speech-to-Text or Soniox; summaries and chapters, partly written by agents; comments and the audit trail.
- Episodes are in Slovenian, but for Borderless, in English; the interface is in English.

## Capabilities and Constraints

- Public (signed out): released episodes and the people on them; everything else needs an approved user (realm role
  `metaos-user`).
- Transcripts are the team's working material (for summaries, chapters and show notes), never public. Editing them
  (correcting words, naming speakers) is not decided yet; showing them well comes first.
- Pages anyone may see render on the server and are cached for a minute; the team's parts load in the browser once
  the user is known to be on the team.

## Brand Commitments

- The interface is neutral: greys, Inter, and the logo's pink as its one accent. Ogrodje's own brand (black, the pink
  braces, faces) lives in the episodes' thumbnails and covers, not in the interface.
- The signed-out side is a window into the team, not a listeners' site: no "Listen now" push, releases first, signing
  in one line.

## Evidence on Hand

Real data in the local and production databases: about 75 episodes, their people with avatars, thumbnails, covers,
transcripts of a few (S05E05–S05E08), agents' chapters and summaries as comments. No testimonials or listener numbers
to show, and none to invent.

## Product Principles

1. One page per thing, for everyone: the public sees it without the team's parts, the team sees all of it, at the same
   address.
2. Say what needs doing: what's late, missing or wrong is said where it's about, in words.
3. Everything has a history: who changed what, by hand or through an assistant, is shown, not hidden.
4. The pictures carry the colour; the interface stays out of their way.

## Accessibility & Inclusion

Keyboard and screen readers as much as the mouse: focus moves on navigation, live regions say what changed, text at
4.5:1 or more.
