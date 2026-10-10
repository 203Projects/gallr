# Contract: public route page (`web/`, Vercel Function)

## Routing

The production Vercel project builds from the repository root (Root Directory "."), so the root `vercel.json` rewrites `/route/:id` to `/api/route?id=:id` and `/route/:id/events` to `/api/route?id=:id&events=1`, and the function entry point is the root `api/route.js`. The page itself lives in `web/api/_lib/` and is tested from `web/tests/`. `web/vercel.json` carries no rewrites, so the two configs cannot drift. No existing path may be shadowed (test, E-D11).

## `GET /route/{id}`

Query: `s=share` (shared link; counted as shared), `v={revision}` (cache key only, ignored by rendering), `lang=en` (else `Accept-Language`, Korean default).

Reads, all with the publishable key, each bounded by `AbortSignal.timeout` (3 s reads, 2 s for the event write) so a page that gave up does not keep upstream requests running:
1. `POST /rest/v1/rpc/get_published_route` with `{ "p_id": id }`: the route row with its ordered stops and `author_display_name` (the author's profile name; empty when unset, in which case the page names nobody), or `null` when it is not published or was revoked (E-D19; table selects are owner-only, and the payload never carries the author's account id).
2. Current `exhibition_catalog_v2` rows for the stop ids (hours, dates, address, current names, cover).

Responses:

| Case | Status | Cache-Control | Body |
|---|---|---|---|
| Published, unrevoked | 200 `text/html` | `public, s-maxage=60, stale-while-revalidate=60` | Full page (below) |
| Missing, unpublished or revoked | 404 `text/html` | `s-maxage=60` | "이 동선은 더 이상 볼 수 없어요" + "지금 열린 전시 보기" (DR-D12); neutral Open Graph |
| Read error or >3 s | 503 `text/html` | `no-store` | "잠시 후 다시 시도해 주세요" + retry link (E-D13) |

Page structure (DR-D3, DR-D4, DR-D14, DR-D19, DR-D25, DR-D27):
- `header` with the site wordmark; `main` with `h1` route name and "전시 동선" eyebrow; verdict line; "<author> 님 · N곳 · 약 X KM"; `figure` with the "방문 순서" SVG (`role="img"`, `aria-label` listing order and districts, dashed connectors, numbered circles, district labels, north mark, scale); `ol` of stops; primary action "첫 전시 길찾기" (sticky below 768 px, inline above); `footer` with "gallr 앱 설치" or "gallr 앱에서 열기".
- Stop row: number, title (link-free), venue, district label (city prefixed outside 서울), today's hours, status line from the shared label table, walking estimate from the previous stop, "길찾기" → `https://map.naver.com/v5/search/<address_ko>` when the exhibition is still listed (DR-D28); unavailable stops show the snapshot title, "더 이상 볼 수 없는 전시", no link.
- Verdict line (RO1): "모두 열림" only when every stop's exhibition runs today and its hours say open; else "오늘(목) · 3곳 중 2곳 열림 · 1곳 시간 미확인" naming closed, ended, unavailable and unknown stops; append "· 토요일엔 3곳 중 3곳 열림" only for the first day within 7 days with more stops open than today.
- Primary action points at the first stop open today, else stop 1 (DR-D14).
- Open Graph: `og:title` "<name> · gallr 전시 동선", `og:description` "N곳 · 약 X KM · <first two titles>…", `og:site_name` "gallr", `og:image` first still-published stop cover (DR-D20). The site has no default share image, so the tag is omitted when no stop has a cover; the route-shaped image stays a TODOS.md entry.
- Styles: `/styles/tokens.css`, `/styles/main.css`, site fonts; both themes; max width 640 px from 768 px.

## `POST /route/{id}/events`

Body `{ "event": "route_page_opened" | "route_page_started", "shared": boolean }` (≤ 256 bytes). Sent by the page script on load (opened) and on the primary or any "길찾기" tap (started). The handler ignores known link-preview user agents, then calls `record_route_page_event`. Response 204, `Cache-Control: no-store`, also when the write fails or exceeds its 2 s timeout (logged as `event_failed`). Never sent on the HTML fetch itself (E-D5).

## Tests (`web/tests/`, part of `npm test`)

Handler tests with a stubbed data layer: 200/404/503 cases and headers; verdict line cases (all open, all not yet open, all ended, one unknown, better day within 7 days, none better); district labels including a non-Seoul city and an unavailable stop; the author named from the payload and omitted when blank; Open Graph fields; author-controlled text escaped; event POST validation and scraper exclusion; a single route request per page and no profile read (E-D19); timeout signals on every request and a 204 when the event write times out. Accessibility test against a rendered fixture (DR-D27). Parity test against the shared parity file (see `parity-file.md`). Playwright at 375 px and 1280 px.
