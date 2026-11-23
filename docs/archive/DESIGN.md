# NOVA Cinema — native TV design system

## Direction

A private screening room: the selected film supplies the light and identity; controls sit quietly over it. Image-led, legible at sofa distance, responsive to a remote. Avoid decorative branding in the hero, large saturated UI accents, heavy blur and bouncing panels.

The supplied Plex references govern composition, not branding: `0.9.png` supplies hero-to-row rhythm; `1.png` supplies left-aligned film information, circular cast and right-side technical capsules; `3.png` supplies compact navigation, gold poster focus and a corner clock; `6.png` supplies the expanded navigation grouping. No Plex assets are bundled. The live installed Plex 2026.20.0 was subsequently studied over ADB; see PLEX_UI_STUDY.md. NOVA uses its own soft-black/gold identity.

Compared directions: type-led catalogue, grid-led file browser, and image-led screening room. Use the last because selecting a film must reveal its identity before opening it. Font candidates were system Roboto, Noto Sans and a downloaded display family; keep Android sans-serif/Roboto with platform Arabic fallback for stable metrics, complete glyph coverage and no font download dependency.

## Canonical tokens

`CinemaTokens.java` owns native values; units are dp for layout and sp for type. 1920×1080 and 3840×2160 at the corresponding TV density share the same logical composition. Keep 16:9, horizontal gutters 64dp (home/details) or 48dp (utility pages), 24dp top/bottom safe area. Narrow action areas wrap into a separate row instead of shrinking labels.

| Role | Value |
| --- | --- |
| Canvas | #171717 |
| Surface / drawer | #D9292929 / #F0202020 |
| Capsule | #D9202020 |
| Text / secondary | #F5F5F5 / #B8B8B8 |
| Divider | #665A5140 |
| Focus / on focus | #F2CC62 / #171717 |
| Pressed / primary gold | #D4AF37 |
| Success / error | #A9D8BB / #FFB4AB |
| Type | caption 12, label 14, body 16, row heading 20, page heading 28, fallback film title 36sp |
| Spacing | 0, 4, 8, 12, 16, 24, 32, 48, 64dp |
| Radius | poster 8, dialog list item 12, surface 16, control 24dp; portraits circular |
| Control / nav target | minimum 48dp / 40dp TV remote row |
| Icons | monochrome platform vector paths, 20dp; 2-unit outline where applicable |
| Depth | no backdrop blur; drawer and dialogs use dark scrims, at most 8dp elevation |
| Motion | press 80ms, focus 120ms, panel 180ms, exit 120ms; decelerate(1.5), no idle movement |

Primary and secondary text on opaque canvas meet 4.5:1; gold focus uses soft-black text. Translucent surfaces keep a dark composite backing; inspect readability over bright artwork. Gold focused controls use soft-black text and icons. Gold marks focus and important actions; ordinary text stays off-white or gray. Press uses native RippleDrawable plus short compression and release, without delaying activation. Remove animations and the app Transitions switch disable spatial motion while retaining focus and pressed fill. No web runtime or interaction-kit CSS is used; native ripple/focus/key input implements the applicable interaction baseline.

## Components and alignment

- Home: hero begins on the 64dp content keyline, row labels and poster edges use that same keyline. Hero has a reserved 100dp image/title region; 3-line summary. Rows are horizontal RecyclerViews; 4–9 posters and small/medium/large preferences remain functional. Hidden/empty rows reserve no space.
- Rail: physical left, compact icon-only capsule; expands to 196dp with labels. Settings, Home, Search, Watchlist, actual Libraries, then Network & Files. Collapsed rail shows only five core icons; library shortcuts appear when expanded. Closed width 40dp, expanded width 196dp; rows 28dp closed / 32dp expanded with 4dp gaps. Library long-press retains Open/Refresh/Remove. No empty library destinations.
- Clock: top-right inside safe insets, time above day/month/year, western digits, translucent capsule. Only the clock updates each minute.
- Details: same 64dp keyline; reserved logo/title area, metadata, separate source-labelled rating badges, summary, director, actions, circular cast. No rating appears when unavailable. IMDb uses a gold word label and /10 metric, TMDb a teal word label and /10 metric. No copied trademark logos. Rotten Tomatoes is absent until an authorized provider supplies a verified critic/audience metric. Primary Watch/Resume followed by Restart, watchlist, watched state and verified official trailer. Technical capsules use measured metadata only and occupy the trailing side when width permits; otherwise their own row. Unknown codec/HDR/audio facts are omitted, never inferred from filenames. Subtitle caption describes availability, not a claimed active track outside playback.
- Utility pages: one 48dp keyline, 28sp title, 16sp row labels, consistent 48dp controls and 8dp row gaps. Lists wrap long labels. API keys remain masked; import uses an actual Android document chooser.
- Dialogs: rounded dark surface, visible focus, English labels, dimmed background, Back/Cancel escape. Keep the library-only removal consequence explicit.

## Language and content

English is the default for this development revision, including a one-time migration of existing Cinema installs. Arabic remains selectable. Numbers use western digits. User source names and original filenames are preserved. TMDB enrichment requests the selected UI language; English artwork remains preferred. No fabricated ratings, media capabilities or compatibility percentages.

## Scope and verification

Cinema surfaces adopt the tokens directly. Retained upstream Leanback screens inherit the shared dark theme where compatible; their internal layout remains upstream and must be recorded separately if not fully redesigned. Native playback engine, SMB and subtitle storage are preserved. Test actual DPAD flow, screenshots at native 1080p, and 4K only when available. Browser-only layout/motion probes do not validate native Android Views; use Android hierarchy bounds, actual key events and screen captures instead. Report untested states explicitly.

Intentional exceptions: posters/cast are repeated media collections, not generic decorative cards; glass is requested only for overlays; metadata separators are compact film information. No animated wallpaper, decorative eyebrow or emoji navigation.

Legacy exceptions: native NOVA presenters retain their established card geometry,
while `MediaLib/res/values/colors.xml` maps both historical blue/black theme names
to canvas #171717, surfaces #202020/#292929 and focus/pressed #6B5826 (their text
stays light). Newly changed Cinema controls use gold focus with dark text.
Existing actor placeholders use initials at 30sp and circular 96dp portraits.
Film synopsis uses label 14sp; director uses caption 12sp. Technical capsules
are capped at 180dp and wrap to two lines. A bounded 32-entry session cache avoids
repeating successful native metadata probes. Last-row bottom clearance allows
its heading to align with the home row keyline rather than exposing a clipped
previous row. The four row choices are retained even when content overlaps.

## Provider architecture revision

The metadata provider is TMDb using NOVA's existing configured resource key, not
Jellyfin Server. Identifier-first matching recognizes explicit TMDb/IMDb tags in
filenames/folders before title search, and fails closed on conflicting ids.
Metadata JSON is cached atomically for 24 hours, with up to 30-day stale fallback
on transport/server failures, a 2MiB response limit and 128-file bound. Successful
images use the existing Picasso HTTP disk cache and NOVA's persisted scraper
artwork. English poster/logo preference is independent of metadata language;
quality and width break ties. Balanced backdrops use w1280; High allows original.
Episode enrichment keys TV id + season + episode; no episode/show identity mixup.
IMDb personal-use data is downloaded only through Rating sources and stored on
the device. It is not embedded in APK or source archives. Attribution is visible
on that screen. No OMDb/Jellyfin key is copied and no RT score is inferred from
TMDb, IMDb, or a Plex screenshot.

Optional OMDb uses an independent encrypted key and renders only explicit,
identity-checked RT critics percentages. IMDb uses the official personal-use
dataset with a 128-id persistent selected-rating cache. Source text accents are
IMDb gold, TMDb #90D6CE and RT status #F36B60 (fresh) / #8FD16A (rotten); primary
text remains neutral. Source labels do not copy licensed logo assets. Disabled
controls use #858585. Gold outlines on watched/watchlist indicate stored state;
the currently focused action has a filled light-gold surface and dark text.

## User revision v4

This revision supersedes the earlier four-row and western-digit defaults.
Home uses Continue Watching → Recently Added Movies → Recently Added TV Shows;
the combined recent row is removed. Existing installs migrate once to this order,
then retain visibility/order customization. Empty rows remain hidden. A positive
resume bookmark is eligible even if an independently stored watched flag is set.
Vertical DPAD navigation scrolls the target group into view before restoring its
poster focus. Movie and series data remain separate original-provider queries.

Cinema display numbers use Arabic-Indic ٠١٢٣٤٥٦٧٨٩; underlying ids, filenames,
requests and stored values stay intact. Date is RTL day/month/year (day at right),
while English metadata numbers are isolated to avoid bidi reordering. Icons have
transparent idle backgrounds, without circular tiles. Focus uses a 6dp rounded
gold target. Drawer alpha is 0xB3; surface/capsule alpha is 0x99.
Subtitles sits beside Trailer with an anchored remote-focusable dropdown for
the current-video folder and Subsource. Technical capsules wait for native
metadata, show exact pixel dimensions and reported audio, and do not infer
Main 10 from an ambiguous profile or HDR from filenames.

## User revision v5

Supersedes v4 digit and drawer-opacity choices: display 0123456789 throughout
Cinema; preserve RTL date day/month/year. Closed navigation uses centered 20dp
icons, symmetric 6dp horizontal padding and zero drawable-to-text gap. Expanded
rows restore left icons and 12dp label gap. Drawer alpha is 0xEE (less transparent).

Do not display the temporary database/localized title before provider enrichment:
reserve the title area, then reveal the resolved original title/logo. If enrichment
fails, reveal the database title once as the fallback. Crew focus draws a gold
circular outline on the 96dp portrait, leaving text outside the outline.

Runtime keeps file milliseconds distinct from provider minutes. Native duration
overrides the catalogue value; display hours/minutes/seconds without rounding up
or treating a millisecond count as minutes. Provider-only fallback is approximate.

Append a permanent `other videos` row below library rows. Its two actual actions,
Choose a video and Network & Files, use the same computed poster width/height,
rounded dark-gold surfaces, centered vector icons and bottom captions. Vertical
DPAD movement enters/exits this row. Hold root focus during initial library load
so the manual row does not take initial focus before real content arrives.

Subsource searches on entry. An unambiguous IMDb identity (and season for TV)
can open ranked Arabic subtitles immediately. Ambiguous identities still require
title selection; manual downloads require selection and confirmation. The 2026-11-08 subtitle automation revision adds user-authorized automatic Arabic fallback; see docs/archive/SUBTITLE-AUTO-PREVIEW.md.
All links opening original NOVA settings now live under System.

## User revision v6

Remove the subtitle-count capsule from film/episode details. The first item in
the Subtitles dropdown is Embedded subtitles, followed by the actual video-folder
browser and Subsource. Embedded choices include Off, Automatic and tracks read
from the file. Left/Right/Back dismiss the dropdown and restore its anchor focus.
Preserve the original in-player subtitle controls (user clarified this scope).

Technical actions read native file metadata: interactive audio on the left,
pixel dimensions/video format on the right. Audio selection enumerates actual
tracks/languages and saves a per-file playback choice; decoder support is checked
by the player, not guessed from a metadata-retriever support flag. Explicit
embedded choices override automatic Arabic selection until Automatic or an
external subtitle is selected. No codec engine replacement.

Film action buttons use 24dp circular focus/state outlines at 48dp square size.
Portrait focus uses a 3dp light-gold circular stroke and 1.06 scale. Portrait
containers allow overflow so scaling cannot flatten the circle at the top.

`CinemaSeriesActivity` uses the same soft-black/gold tokens, background/logo,
synopsis and metadata hierarchy as film details. Real season tabs filter the
original episode catalogue. Landscape episode cards use 225×145dp, stills from
the exact provider season/episode and visible episode labels. Watch/Resume uses
the original NOVA player; a card opens the cinematic episode details. Preserve
season/episode focus on return, and refresh resume state from the provider.

## User revision v7 — in-video subtitle appearance

The user explicitly means subtitle glyph colour over the video, not menu text.
`Subtitle appearance` is a native 620dp panel capped at 490dp height, with colour
preview, Auto contrast, White/Yellow/Black/More colours, compact Size/Position
rows and an outline state. All controls fit the tested 1080p TV viewport; normal
remote navigation and Back/Done remain native. Manual palettes remain available.

Automatic contrast is opt-in and persisted. Sample only the app's video surface,
96×54 pixels, at most once per 650ms and one copy in flight. Analyse the actual
subtitle bounds when available; a lower-video band is the no-cue fallback. Black
letterbox areas outside the video keep white text. Relative luminance and two
stable samples choose black/white, with 0.14/0.22 hysteresis and immediate response
to very bright/dark samples. Always use the opposite outline in automatic mode.
Update the colour preview only on real colour changes, not on every sample.

Automatic colour is separate from the saved manual colour. Switching off restores
the manual colour and subtitle foreground spans; automatic mode overrides colour
spans while retaining bold/italic styling. Sampling stops when the activity is
paused and its worker closes on destruction. No frame is stored or transmitted.
Bitmap/PGS subtitles are excluded; failed/unsupported copies fall back to outlined
white. No video decoder, SMB or subtitle-timing engine is replaced.

## Pending user review — matched subtitle panels

The subtitle appearance panel and the additional colour palette inherit the
actual expanded subtitle-menu width and height at opening, rather than using the
previous fixed 620dp width. Their frame, rounded corners and dark surface match.
Preset colours use two columns; the full palette uses four equal columns.
Compact 32dp remote controls preserve readable labels and consistent spacing.
The Done action stays pinned below the scrollable content. Preview builds may be
installed for device verification; per the user's explicit request, ask whether
there are further edits before preparing the next final release.

Measured reference at 1080p: [696,100]–[1224,980], 528×880 pixels.
`PLAYER_PANEL` #202020 is opaque so background menu labels cannot bleed through
the active panel. Full-width primary rows and a pinned Done action align with
the 12dp body inset; square full-palette cells retain equal column widths.

## Pending performance preview

Native player cards commit expanded/collapsed geometry once, retaining the same
final dimensions. Avoid per-frame width/height layout animation. Horizontal
translation and alpha last 120ms; cancel an old transition on a new target and
ignore repeated centering on the same target. Restore focus once on expansion,
without overriding a later remote key at animation completion. Preserve the
user's final-release review gate. Measurements and runtime checks are recorded
in docs/archive/PLAYER-MENU-PERF-PREVIEW.md.


## Arabic subtitle automation — current preview, 2026-11-08

Latest user instruction supersedes the earlier external-first/manual-only download policy. Explicit per-file choices (including Off) are respected. In Automatic mode, Arabic embedded tracks win, then existing Arabic external/bound subtitles are reused. If no Arabic track exists and Subsource is enabled with a saved key, playback continues while a worker searches the verified IMDb identity/season, ranks Arabic releases and downloads the best non-uncertain match. Unknown identity, wrong episode/year, ambiguous archive and insufficient release evidence are not guessed. Service epochs reject stale work after media/manual changes. Storage checks cancellation before binding, preserves existing files and falls back to private storage for unwritable sources. Saved subtitles are pinned above other Arabic results in Subsource, including a filename fallback for older bindings without provider IDs. English UI and existing design tokens remain unchanged. This preview awaits user review before a new final delivery.


## Watchlist poster collection — pending preview

Watchlist inherits the cinema canvas, artwork and compact rail. At 960dp wide it has six poster columns on the64dp keyline,16dp column gaps and24dp row gaps; adaptive columns use a136dp cell budget. Posters keep2:3 ratio,8dp corners, a2dp gold focus outline and a two-line14sp title below in48dp. A small real item count shares the heading area. The grid begins at88dp with8dp focus clearance; there is no giant empty hero. Selected artwork supplies a subdued1280px backdrop, with canvas scrims for readability. These collection-specific geometry values extend the existing grid roles rather than introducing another button style. Initial loading uses noninteractive poster skeletons; empty and error states have actual recovery actions. Native D-pad, long-press/Menu actions, details return focus and reloading after removal must be checked on device. A debug-only nonpersisted layout_check intent may show18 real library items for multi-row testing, explicitly headed layout check; it never mutates the user's saved watchlist. Native press/focus feedback inherits existing tokens; no idle motion or web runtime.


## Trailer action discovery — pending preview

Keep Trailer visible as a text-and-play-icon pill in film/episode details rather than an icon alone, immediately before Subtitles. Use the existing14sp/48dp action geometry and24dp control radius. Episode focus hint identifies the parent-series trailer. Series pages place Watch Trailer in the horizontal Watch/Resume action group, using the existing action component. Render only a verified official YouTube trailer for the same provider identity; English is the shared fallback when selected-language data lacks a trailer. Do not add fake controls or search links for missing trailers. Continue Watching retains the same details entry points. Device evidence: docs/archive/TRAILER-PREVIEW.md.


## Final v9 approval — 2026-11-08

The user approved final packaging after the subtitle automation, Watchlist and trailer iterations. Earlier pending/preview sections describe their development history; these changes are now accepted in final v9. For a future iteration, retain the user's review gate again. Current handoff: HANDOFF.md; final report: docs/FINAL-V9.md.


## Fresh-state resilience — hotfix preview

Optional IMDb/OMDb/RT information must never gate the details page or playback. Missing metrics remain absent; do not synthesize values or require a private dataset. Empty home distinguishes no configured sources from saved folders awaiting indexing/matching, and Home/Right can always reach the manual file cards when no media row exists. Existing tokens and English defaults are preserved. See docs/archive/FRESH-START-FIX.md for clean-state evidence and limits.


## Final v10 acceptance — 2026-11-08

User confirmed the clean-state repair works and approved final v10. Optional rating providers cannot block details/playback; retain the no-IMDb/no-OMDb regression checks. Current source/delivery entry point is HANDOFF.md and docs/FINAL-V10.md. Historical v9 is not the corrected release.
