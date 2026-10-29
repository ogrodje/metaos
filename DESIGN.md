---
name: Ogrodje MetaOS
description: The team's workspace for making Ogrodje's podcast, and a window into it for everyone else
colors:
  ink: "#141414"
  ink-hover: "#2B2B2B"
  secondary: "#525252"
  muted: "#6B6B6B"
  hairline-strong: "#A3A3A3"
  faint: "#C4C4C4"
  hairline: "#E6E6E6"
  rule: "#EEEEEE"
  hover: "#F5F5F5"
  surface: "#FAFAFA"
  paper: "#FFFFFF"
  accent: "#EC4870"
  accent-text: "#C02A55"
  accent-hover: "#A3244A"
  mark: "#FCE1E9"
  accent-wash: "#FDF0F3"
  success: "#1F7A4D"
  warning: "#8A5A00"
typography:
  page:
    fontFamily: "Inter Variable, Inter, -apple-system, BlinkMacSystemFont, Segoe UI, Roboto, sans-serif"
    fontSize: "28px"
    fontWeight: 600
    lineHeight: "34px"
    letterSpacing: "-0.02em"
  section:
    fontFamily: "Inter Variable, Inter, sans-serif"
    fontSize: "18px"
    fontWeight: 600
    lineHeight: "26px"
    letterSpacing: "-0.01em"
  lead:
    fontFamily: "Inter Variable, Inter, sans-serif"
    fontSize: "16px"
    fontWeight: 600
    lineHeight: "24px"
  body:
    fontFamily: "Inter Variable, Inter, sans-serif"
    fontSize: "14px"
    fontWeight: 400
    lineHeight: "22px"
  small:
    fontFamily: "Inter Variable, Inter, sans-serif"
    fontSize: "13px"
    fontWeight: 400
    lineHeight: "20px"
  caption:
    fontFamily: "Inter Variable, Inter, sans-serif"
    fontSize: "12px"
    fontWeight: 400
    lineHeight: "18px"
  micro:
    fontFamily: "Inter Variable, Inter, sans-serif"
    fontSize: "11px"
    fontWeight: 500
    lineHeight: "16px"
  code:
    fontFamily: "ui-monospace, SFMono-Regular, Menlo, Consolas, monospace"
    fontSize: "12px"
    fontWeight: 400
    lineHeight: "18px"
rounded:
  xs: "4px"
  sm: "6px"
  md: "8px"
  lg: "12px"
  full: "999px"
spacing:
  "1": "4px"
  "2": "8px"
  "3": "12px"
  "4": "16px"
  "6": "24px"
  "8": "32px"
  "12": "48px"
  "14": "56px"
components:
  button:
    backgroundColor: "{colors.paper}"
    textColor: "{colors.ink}"
    typography: "{typography.small}"
    rounded: "{rounded.sm}"
    padding: "0 14px"
    height: "44px"
  button-hover:
    backgroundColor: "{colors.paper}"
    textColor: "{colors.ink}"
  button-primary:
    backgroundColor: "{colors.ink}"
    textColor: "{colors.paper}"
    typography: "{typography.small}"
    rounded: "{rounded.sm}"
    padding: "0 14px"
    height: "44px"
  button-primary-hover:
    backgroundColor: "{colors.ink-hover}"
    textColor: "{colors.paper}"
  button-danger:
    backgroundColor: "{colors.accent-text}"
    textColor: "{colors.paper}"
    rounded: "{rounded.sm}"
  button-danger-hover:
    backgroundColor: "{colors.accent-hover}"
    textColor: "{colors.paper}"
  button-small:
    rounded: "{rounded.sm}"
    padding: "0 10px"
    height: "32px"
  button-disabled:
    backgroundColor: "{colors.hover}"
    textColor: "{colors.muted}"
  text-button:
    textColor: "{colors.accent-text}"
    typography: "{typography.small}"
  input:
    backgroundColor: "{colors.paper}"
    textColor: "{colors.ink}"
    typography: "{typography.body}"
    rounded: "{rounded.sm}"
    padding: "0 10px"
    height: "44px"
  input-small:
    typography: "{typography.small}"
    padding: "0 8px"
    height: "32px"
  chip:
    backgroundColor: "{colors.paper}"
    textColor: "{colors.secondary}"
    typography: "{typography.small}"
    rounded: "{rounded.full}"
    padding: "3px 10px"
  chip-pressed:
    backgroundColor: "{colors.ink}"
    textColor: "{colors.paper}"
---

# Design System: Ogrodje MetaOS

## Overview

**Creative North Star: "The Production Desk"**

MetaOS is where Ogrodje's team sees what's being recorded, made and released, and does something about it; anyone else
gets a window into the same desk. It's a tool, used often and for short tasks, so the interface stays out of the way:
greys, one typeface, hairlines rather than boxes, and the logo's pink only where something needs a look or is chosen.
Ogrodje's own brand, black, pink braces and faces, lives in the episodes' thumbnails and covers, which carry the colour
of the page; the interface around them is neutral so they can.

It's dense the way a desk is: lists of rows, a side panel of facts, counts beside every heading, nothing that only
decorates. Space separates sections (56 px) more than lines do; inside a section, rows sit close under a hairline each.
The signed-out side isn't a listener's site (ogrodje.si is): it leads with the latest releases and offers sign-in in a
line.

**Key Characteristics:**
- A grey ramp and one accent; colour comes from the thumbnails, not the chrome.
- One type family (Inter) on a scale of seven sizes; monospace only for codes like S05E07.
- Rows under hairlines, not cards; a card only where a thing stands alone (a person, a session, a release).
- Every list says how many, what's shown of how many, and what to do when nothing is.
- Built for the keyboard and screen readers as much as for the mouse.

## Colors

A neutral ramp from near-black ink to white, and the logo's pink in two strengths, for marks and for text.

### Primary
- **Logo Pink** (#EC4870): marks, not text (3.7:1 on white): the current tab's underline, the dot of an episode being
  made, a stage that needs work.
- **Deep Pink** (#C02A55): the accent as text (5.7:1): links that act, Show all, errors and what needs attention, focus
  rings, the danger button. Hover goes deeper still (#A3244A).
- **Search Blush** (#FCE1E9): behind the words a search found, and text selection.
- **Chosen Wash** (#FDF0F3): behind what's chosen in a list, e.g. the palette's highlighted result.

### Neutral
- **Ink** (#141414): text and the filled button; **Ink Hover** (#2B2B2B) when pointed at.
- **Secondary** (#525252): text that supports, e.g. a tab that isn't current.
- **Muted** (#6B6B6B): counts, meta lines, labels, placeholders; the lightest grey text may be (5.3:1).
- **Strong Hairline** (#A3A3A3): a control's edge while it's pointed at or open.
- **Faint** (#C4C4C4): what mustn't read as text: a link's underline, a letter with nothing under it, an upload's dashed
  edge.
- **Hairline** (#E6E6E6): the edges of controls and boxes, the header's line.
- **Rule** (#EEEEEE): the line between rows.
- **Hover** (#F5F5F5): what's under the pointer, and the fill of what's unavailable.
- **Surface** (#FAFAFA): a quieter panel inside a box: a comment's header, code, a place where something can go.

### Status
- **Success** (#1F7A4D) and **Warning** (#8A5A00), for words only: an upload that went through, one that looks wrong.
  What went wrong is Deep Pink.

### Named Rules
**The Thumbnails Carry the Colour Rule.** The interface is grey and pink; Ogrodje's branding, faces and colours come in
through episodes' pictures, never through the chrome around them.

**The Pink Means Look Rule.** Pink marks what needs attention, what's chosen or what acts. Anything else pink, a
decoration, a heading, a background, dilutes it.

## Typography

**Body Font:** Inter Variable (with Inter, the system's sans-serif)
**Mono Font:** ui-monospace (SF Mono, Menlo, Consolas), for codes only

**Character:** One quiet, legible family at small sizes, set close: hierarchy comes from size steps and weight (400,
500, 600), never from colour or capitals.

### Hierarchy
Each a token in `base.scss` (`--text-<role>`):
- **Page** (600, 28 px, 34 px): a page's `h1`, the episode's or person's name.
- **Section** (600, 18 px, 26 px): a section's heading (`.heading h2`), e.g. Needs attention, Season 5.
- **Lead** (600, 16 px, 24 px): a card's or dialog's title, Markdown headings in summaries.
- **Body** (400, 14 px, 22 px): the page's text, row titles (500), a part's heading (`.subheading`, 600).
- **Small** (400, 13 px, 20 px): most of the interface: buttons, chips, meta lines, counts, notes.
- **Caption** (400, 12 px, 18 px): a line about something: who, when, how big; table headers (600).
- **Micro** (500, 11 px, 16 px): keys, tags, a size beside a file.

Running text keeps a measure of its own: `MarkdownText` at 680 px.

### Named Rules
**The Seven Sizes Rule.** A size that isn't one of the seven tokens doesn't ship: 12.5 or 15 px is a scale that drifted.

**The Codes Are Mono Rule.** Monospace is for what's typed and matched, S05E07 or a file's name in Dropbox, never for a
"technical" look.

## Layout

Every page is in one frame, 1200 px wide (`--content-width`) with a 24 px gutter (16 px on phones), the header's and
footer's too. A page is a column of sections 56 px apart (48 on phones); a thing's page (an episode's, a person's, a
session's) is a workspace: its name (and picture) leading the main column, and a 336 px fact panel at the right, beside
them from the top, which comes after the name on narrower screens (under 960 px). Lists are rows under a
`--rule` line each, 10–12 px above and below; grids of releases are four across, three, two, then a list on phones.
Spacing is in steps of 4 px. Breakpoints: 640 px (phones), 800, 960 (the workspace stacks), 1100.

## Elevation & Depth

Flat. The page is white, sections are separated by space and hairlines, and boxes are outlined, not lifted. Only what
floats over the page has a shadow, and it's soft and offset downward, with a hairline ring so it holds its edge on
white.

### Shadow Vocabulary
- **Menu** (`box-shadow: 0 0 0 1px rgba(0, 0, 0, .08), 0 18px 40px -12px rgba(0, 0, 0, .28)`, `--shadow-menu`): the
  user menu and other popovers.
- **Dialog** (`box-shadow: 0 0 0 1px rgba(0, 0, 0, .08), 0 24px 60px -12px rgba(0, 0, 0, .35)`, `--shadow-dialog`):
  Search and the upload dialogs, further from the page.

### Named Rules
**The Flat Page Rule.** Nothing on the page casts a shadow; a shadow means it floats and will go away.

## Shapes

Gently rounded, never pill-shaped except chips. Four radii (`--radius-*`): keys and tags 4 px, controls (buttons,
fields) 6 px, containers (boxes, panels, menu items) 8 px, and what floats or stands alone (dialogs, menus, person and
session cards) 12 px. Pictures follow their own rules (`_pictures.scss`): people round; things 6 px up to 56 px across
and 8 px above, with a hairline inside; a missing one the same grey (#F1F1F1). Dashed edges only where the team uploads
something.

## Components

### Buttons
Quiet and outlined; the filled one is the one thing a form is for.
- **Shape:** 6 px corners, 44 px tall (32 px for `.small` in a list's rows, 40 for fingers).
- **Default** (`.button`): white, Ink text, Hairline edge, Strong Hairline when pointed at.
- **Primary** (`.button.primary`): Ink fill, white text; Ink Hover when pointed at. One per form or dialog.
- **Danger** (`.button.danger`): Deep Pink fill, only once Remove has been asked.
- **Quiet** (`.button.quiet`): Secondary text, beside the one that matters, e.g. Cancel or Remove before it's asked.
- **Unavailable** (`[aria-disabled='true']`): Hover fill, Muted text, still focusable, so it says why rather than
  vanishing.
- **Text button** (`.text-button`): Deep Pink, 500, underlined when pointed at: Show all 17, Read all of it, Rename, Try
  again, Clear filters.

### Chips
- **Style:** pill (999 px), white, Hairline edge, Secondary text with a Muted count (`ChipGroup`).
- **Pressed:** Ink fill, white text, the count at 70% white. One chosen in a group; a filter that's on is a pressed chip
  with × (`FilterChip`).

### Inputs / Fields
- **Style** (`.input`): white, Hairline edge, 6 px corners, 44 px tall, Body text; `.small` 32 px and Small text in a
  list's filters (`SelectFilter`).
- **Focus:** the 2 px Deep Pink ring in place of the edge, so the field doesn't grow.
- **Read-only:** Surface fill and Secondary text while it's being saved.
- The filter bar's search box (`FilterBar`) is the same, 36 px, with its icon inside.

### Lists and rows
The workhorse. A row is a line under a Rule: code (mono, Muted) in a column of its own, title (Body, 500), what it's
about in a line under it (Small, Muted, names Secondary, parts apart by a dot), status (`StatusLabel`: a dot, filled
pink while it's being made, hollow before), dates and links at the right, in columns that line up from row to row and
from list to list. A section's heading says how many (`.count`, a grey tag after it); a filtered list says how many of
how many; an empty one says what was looked for (`NoMatch`). People are lines too, the team's with larger pictures:
no list is a set of boxed cards.

### List pages
Episodes, Ideas, People and Transcriptions are one page: the name with how many and a line about what's listed
(`PageHeader`), what else the page does as one button at the name's end (Transcribe an episode, which opens its form
under the name); the filters (`FilterBar`); then a section a group, a Section heading each, and its rows. A group is a
section, never a table's row group or a column header: a row says what its dates are where it's not obvious.

### Navigation
One row: the logo, 40 px tall, the pages as tabs, Secondary text, 500, each after its icon in Muted, the current one
Ink, underlined 2 px in Logo Pink, its icon Deep Pink; Search and you at the right. On narrower screens the tabs go to a
row of their own under them, and scroll sideways; on phones without their icons.

### Fact panel
A thing's facts at the right of its page (`FactPanel`): groups under a Rule, label and value a line each (Small, Muted
labels), counts as lines that filter (`StageBar`, a person's roles), with the thing's title appearing at its top once
the page's has scrolled away. An episode's panel holds the team's activity too, first, so it stays in view however
far the page is read: it scrolls on its own when it's taller than the screen.

### An episode's page
The public's page with the team's on top, quietly, never a second page. The episode leads: title, thumbnail large,
who's on it and where to listen, the summary. What's the team's adds to it: on the thumbnail, which titles it's tested
with (a dark chip, as on a video); in the panel, what needs attention (Deep Pink lines after a Logo Pink dot) and the
activity (a comment box closed to a line, the latest entries newest first, a comment's first lines in a Surface box,
what's new marked in Logo Pink); a Muted lock beside a fact only the team sees; and at the end, Behind the episode, its
transcript, pictures, and video and audio folded to a line each between Hairlines, what's missing in Deep Pink.

## Do's and Don'ts

### Do:
- **Do** take every colour, size, radius and shadow from `base.scss`'s tokens; add a token, with what it's for, before a
  second component needs a new value.
- **Do** use `.button`, `.button.primary`, `.text-button` and `.input` rather than styling a button or field in a
  component.
- **Do** say what something is under its name (`.title .meta`: S05E07 · its show · Published), not above it.
- **Do** give every list a count, a filtered count, a loading state (`LoadingRows`), an error with Try again
  (`LoadError`) and an empty state.
- **Do** keep text at 4.5:1 or more: Muted (#6B6B6B) is the lightest grey for words, placeholders too.

### Don't:
- **Don't** bring Ogrodje's branding (black, pink braces, faces) into the interface; it's in the pictures.
- **Don't** put a label or eyebrow above a heading, e.g. "Recording session" over a session's name.
- **Don't** box what can be a section: a form or a list sits on the page under its heading, not in a bordered card.
- **Don't** use a size off the scale (12.5, 13.5, 15 px), a radius off it (6.4, 10 px) or a grey off the ramp.
- **Don't** shadow anything that doesn't float, or make a big number with a small label stand in for a fact.
