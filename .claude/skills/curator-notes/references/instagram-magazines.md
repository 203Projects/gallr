# The editorial reference for curator notes and carousels

The owner named these magazines and posts on 2026-10-13 as the reference for how a gallr curator's
note reads and how its Instagram carousel is built. This file records what they have in common,
what each is good at, and what gallr does not take. The binding rules are in
`supabase/functions/draft-curator-notes/prompt/system.md` (writing) and `DESIGN.md`, "Exported
content" (slides). Study the form; never reuse a headline, a sentence or an image from them.

## What they share

- **A cover line, not a summary.** Two lines over a full-bleed picture, 15 to 25 Korean characters,
  with a small tag above it (`EXHIBITION`, `ARTIST`, `#EXHIBITION`) and sometimes one line under it.
  It works by holding one thing back: a question, an unnamed detail, a number without its reason.
- **The payoff comes at once.** The slide after the cover sets the scene in two or three lines and
  the next one gives the answer. Nothing is withheld past the third slide.
- **One idea per slide, in large type.** Two to four lines over or beside a picture. The long text
  lives in the caption, not on the slides.
- **Numbered points.** Single-exhibition posts gather three "view points" and give each a slide
  with a two-line headline.
- **The facts once, in a block.** Name, venue, dates, sometimes admission, on their own lines.
- **A closing turn.** The last text slide steps back: what the work adds up to, often as a question.
- **Credits.** Every picture carries its photographer or rights holder in small type.

## The magazines

| Account | What it is | Take | Leave |
|---|---|---|---|
| `@seoulauction_blue` | Auction-house magazine: exhibitions, artists, fairs | Tag plus two-line cover; the "view point" slides; one detail explained across slides; caption with the facts first | Emoji bullets |
| `@handlmag` | Long-form artist and design reviews | The plain declarative register gallr already uses; a bold lead over a short paragraph with one phrase stressed; sections that move intro, who, turning point, closing question; work titles as captions | Length: sixteen slides is an essay, a note is three points |
| `@nant.magazine` | Art and design magazine | Tag, cover line and a one-line deck; a "look for this" label pinned to the work it describes | Video covers |
| `@artinmuseum` | Art news since 2011 | A fact stated flatly as the cover line; three slides, one line each; reported register | News pegs that date quickly |
| `@why__art` | An art historian's viewing guide | One concept per post, explained through one work; a question as the way in | Theory the packet cannot support |
| `@artlamp_org` | Art, design and space | The facts block on every slide of a roundup; a two-line reason to go | Season and weather framing |
| `@d_careeer` | Space and exhibition curation | One exhibition per slide with name, venue and dates only; a theme that sorts a roundup | Mood hooks and the conversational register |
| `@b.framemag` | Exhibition picks | "Who this is for" and "do not miss" as practical extras; admission and address in the facts | Sticker labels, emoji, exclamation |

## The posts

Roundups (several exhibitions, one slide each): Dd9CD_lEx8X, Dd75SxeARZS, DebVEzpE8td,
DeRFmnik9Oy, DeHLvVak-Mh, DdNtLgAE6T7, DbNHDoXEyiR, Da9r2Gbk3GR.
One exhibition or two: DcXeQRPAZNC (three view points), Dahdz5Ikh4o (three slides, one line each),
DcBBeYrBuUQ (one line over video), Dd0PN1ZE9_P (two shows compared).
One artist: Ddp3sIHAa3q (a withheld detail paid off on the third slide), DbkzZBkCQkm (sections
with a bold lead), DbzXELJn9zU (pictures with work titles), DYEnuaUiSYz (a method as two verbs).
A fair section: Dc0X9cInxpn (one gallery per pair of slides with a "look for this" label).
Each is at `https://www.instagram.com/p/<id>/`.

## What gallr does differently, and why

- **Register.** The note keeps the declarative -다 register in Korean. Half of the references use
  it; the other half speak to the reader in 해요체, which does not suit a note that also sits on
  the exhibition page.
- **No mood, season or urgency hooks.** They belong to a roundup posted on a given day. A note
  stays up for the whole run and every statement in it comes from the packet.
- **Pictures.** Each insight slide shows one of the exhibition's gallery images, and the images the
  insights do not use get full-bleed slides of their own between them, ten slides at most. An
  exhibition with only its cover shows a slice of it on each insight slide instead.
- **No emoji, stickers or calls to follow.** DESIGN.md keeps exported content monochrome and plain.
- **Roundups are an Admin export, not a kind of note.** Eight of the seventeen reference posts are
  roundups. Admin builds one from approved notes (Notes → Roundup carousel): the editor writes the
  cover line and each exhibition's slide carries its note's hook, which is one more reason the
  hook must make sense alone.
