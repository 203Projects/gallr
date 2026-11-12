# Mobile analytics privacy and store disclosures

**Status:** Required activation record; production collection remains disabled
**Last reviewed:** 2026-11-12 (spec 103 visit diary and 감상)
**Applies to:** Android package `com.gallr.app` and the matching iOS app

This document is the repository source of truth for the first-party mobile
analytics disclosures defined by `specs/072-mobile-product-analytics`. It does
not enable collection, authorize a store submission, or replace the answers
currently saved in Google Play Console or App Store Connect.

## Product boundary

Usage analytics is optional and off by default. The app must require both the
release kill switch and the user's persisted preference before it creates an
event or makes an analytics request. Turning the preference off immediately
clears the unsent device queue and prevents future analytics requests.

The system is used only to understand and improve Gallr's product features. It
must never be used for advertising, paid promotion, commercial targeting,
billing, entitlements, unique-user or session measurement, or decisions about
an individual. No analytics data is sold or provided to advertisers or data
brokers. Supabase processes the data only as Gallr's infrastructure service
provider.

## Analytics data inventory and retention

| Stage | Data | Eligibility and cleanup window |
| --- | --- | --- |
| Device retry queue | Up to 200 typed, unsent events | Eligible for delivery for 7 days; expired entries are removed on the next queue access; opt-out pauses collection immediately and clears or exposes a retry |
| Retry receipt | One random event UUID with no event attributes | 7-day deduplication window; expired rows are removed by the following hourly cleanup |
| Abuse-rate source | Keyed digest derived from the request's network source; never joined to analytics facts | 24-hour rate-limit window; expired rows are removed by the following hourly cleanup; the database never stores the raw network address |
| Product report | Daily counts across allowlisted dimensions | Rolling 24-calendar-month reporting window; out-of-window rows are removed by hourly cleanup |

An event may contain its date, mobile platform, major app version, discovery
surface, public exhibition ID where relevant, completed product action or a
successfully opened platform share sheet,
recommendation result count/rank range, or coarse route mode, stop count,
distance range, and duration range. The recorder does not retain raw behavioral
event rows after aggregation.

The event model cannot contain a name, email address, account ID, reusable
device or installation ID, search text, precise location, URL, contact value,
thought, diary feeling, tag or private note, report, guest data, complete saved
or visited list, recommendation profile, score or reason, route origin,
coordinates, geometry, venue sequence, or route ID.

## Public privacy links

- Privacy Policy: `https://gallrmap.com/privacy/`
- Privacy Choices: `https://gallrmap.com/privacy/#choices`
- Privacy contact: `privacy@gallrmap.com`

The deployed policy must contain the bilingual optional-analytics section,
default-off choice, exclusions, and the qualified 7-day, 24-hour, and 24-month
windows plus cleanup behavior before either store declaration is submitted.

## Google Play Data safety delta

Update the global Data safety form for every non-internal-testing release that
can collect these events. Preserve accurate declarations for account, gallery
alerts, push addresses, and other existing app behavior; the table below is the
analytics-specific delta.

| Play data type | Collected | Shared | Required | Purpose |
| --- | --- | --- | --- | --- |
| App activity → App interactions | Yes | No | Optional | Analytics |
| App activity → Other actions | Yes | No | Optional | Analytics |
| Device or other IDs | Yes, conservatively covering the short-lived anti-abuse source digest and existing push/install identifiers | No | Optional | Fraud prevention, security and compliance for the digest; preserve existing app-functionality/developer-communications purposes for push identifiers |

Form-level answers:

- Data is encrypted in transit: **Yes**. The mobile endpoint is HTTPS only.
- Data is shared with third parties: **No** for mobile analytics. Supabase acts
  as a service provider under Gallr's instructions.
- Collection is optional: **Yes**. Every user can leave it off or turn it off
  without losing app functionality.
- Search history, precise/coarse analytics location, advertising, marketing,
  and personalization purposes: **No** for mobile analytics.
- The per-event retry UUID is not a device identifier: it is random for one
  retry identity and is never reused for a person, account, installation, or
  session.
- Turning analytics off deletes unsent device data. Previously accepted daily
  counts cannot be deleted per person because they contain no account or
  reusable device identity; do not claim otherwise in the form.

Before submission, save the Play Console preview and the reviewer/date in the
release evidence. Google Play's form represents the sum of data practices
across distributed versions, so do not remove an existing declaration merely
because this analytics path is optional.

## App Store privacy baseline and analytics delta

Update App Store Connect before submitting an app build that can collect mobile
analytics.

| App Store data type | Purpose | Linked to user | Used for tracking |
| --- | --- | --- | --- |
| Contact Info → Name | App Functionality (profile) | Yes | No |
| Contact Info → Email Address | App Functionality (authentication) | Yes | No |
| User Content → Photos or Videos | App Functionality (profile avatar) | Yes | No |
| User Content → Other User Content | App Functionality (profile bio, thoughts, diary entries and reports) | Yes | No |
| Identifiers → User ID | App Functionality (account and sync) | Yes | No |
| Identifiers → Device ID | App Functionality (gallery-alert delivery plus rate limiting and abuse prevention) | Yes for the existing persistent gallery-alert installation/push identifiers; the analytics digest itself is not joined to an account or analytics facts | No |
| Usage Data → Product Interaction | App Functionality for account-synced saves, visits, and follows; Analytics for optional daily aggregates | Yes globally because some app-functionality interactions sync to an account; the analytics facts themselves remain unlinked | No |

Do not add Analytics-purpose declarations for User ID, precise/coarse location,
Search History, Product Personalization, advertising, or marketing. The Device
ID / App Functionality declaration above exists primarily for the persistent
gallery-alert installation and APNs identifiers and conservatively covers the
IP-derived abuse-control digest. The per-event retry UUID is not a Device ID.
The recommendation profile and location inputs stay on device and are not
analytics inputs. Reconcile the existing push and gallery-alert behavior
against the complete archive privacy report rather than overwriting its current
declarations.

The app-level `PrivacyInfo.xcprivacy` should declare:

- `NSPrivacyTracking = false`;
- Name, Email Address, Photos or Videos, Other User Content, and User ID for App
  Functionality, linked and not used for tracking;
- Device ID for App Functionality, not used for tracking, covering existing
  gallery-alert installation/push identifiers and the short-lived keyed source
  digest. The app-level declaration is conservatively linked because the
  existing alert identifier persists on a device; the analytics digest remains
  separate from accounts and analytics facts;
- Product Interaction for App Functionality and Analytics, conservatively
  linked at the app level because bookmarks, visits, and follows may sync to an
  account. Mobile analytics aggregates remain identity-free and are never
  joined back to those account records; and
- only required-reason API entries actually reported by the archived app and
  its bundled SDKs.

Do not add `NSUserTrackingUsageDescription`: Gallr does not track users or
request App Tracking Transparency authorization. Save the App Store Connect
privacy-answer preview and Xcode archive privacy report with the release
evidence.

## Visit diary and 감상 (spec 103, release 1.16)

Spec 103 adds a private visit diary (visit date, feeling, up to three tags and a
private note per exhibition), publishing a diary note as a 감상 after staff
review, reports on 감상, and hiding one person's 감상 on the device. It adds **no
new data type** to either store form:

- Diary entries are user content linked to the account and used only for app
  functionality: kept on the device, synced to the account as private fields
  when signed in, and read back only by their owner. Feelings and tags tune
  내 취향 on the device only. Reports are user content linked to the reporter
  and used to moderate 감상. All of these fall under the existing **Other User
  Content** row above, whose purpose text widens to "profile bio, thoughts,
  diary entries and reports".
- Hidden people stay on the device and are never sent, so they are not
  collected data.
- Usage analytics is unchanged: no event carries a feeling, tag, note or report
  (spec 103 FR-029), so the analytics delta in this document still applies as
  written.
- `iosApp/iosApp/PrivacyInfo.xcprivacy` needs no change: its
  `NSPrivacyCollectedDataTypeOtherUserContent` entry already declares linked,
  app-functionality data, not used for tracking, and has no free-text purpose.

The public privacy page (`web/privacy.html`) describes the diary, public notes,
reports, hidden people and account deletion from spec 103 onward; it must be
deployed before the 1.16 submission.

App Store Review Guideline 1.2 (user-generated content) maps to these places in
1.16:

| Requirement | Where it is in the app |
| --- | --- |
| Filter objectionable material | Every 감상 is reviewed by staff in Admin → 감상 before it is public, and any edit returns it to review |
| Report offensive content with a timely response | 신고 on every other person's 감상 card, with five fixed reasons; staff decide each report within 48 hours |
| Block abusive users | 이 사용자의 감상 숨기기 on the card, with undo, and Settings → 숨긴 사용자 to reverse it |
| Published contact information | Settings → 의견 보내기 (`hello@gallrmap.com`) |

### Owner steps for the 1.16 submission

- [ ] **App Store Connect → App Privacy:** keep the declared types unchanged and
  check that User Content → Other User Content is declared as App Functionality,
  linked to the user and not used for tracking. App Store Connect has no
  free-text purpose field, so the wider purpose is recorded in this document
  only.
- [ ] **Google Play Console → Data safety:** confirm that "Other user-generated
  content" is declared as collected, linked to the user, not shared, with the
  App functionality purpose (it covers 감상 today and now also diary entries and
  reports). Add no new data type. Save the preview with the release evidence.
- [ ] **Privacy page:** the updated `web/privacy.html` is live at
  `https://gallrmap.com/privacy/` before submission, with its effective date
  set to the day it goes live.
- [ ] **App Review note for 1.16** (App Store Connect → App Review Information →
  Notes), naming the four places in the table above, for example:

  > gallr 1.16 lets signed-in visitors publish a note from their private visit
  > diary on an exhibition page. Every published note, and every edit of one, is
  > reviewed by our staff before anyone else can see it. Readers can report a
  > note from the ⋯ menu on its card (신고, five reasons); staff decide every
  > report within 48 hours. Readers can hide all notes from one person with
  > 이 사용자의 감상 숨기기 on the same menu, and reverse it in Settings →
  > 숨긴 사용자. Contact: Settings → 의견 보내기 (hello@gallrmap.com).

- [ ] **Review account:** if the reviewer needs to see a published note, make
  sure one approved 감상 exists on a running exhibition in production and say
  which in the note.

## In-app preference copy

Root Settings row:

- Korean: `사용 분석` with value `꺼짐` / `켜짐`
- English: `Usage analytics` with value `Off` / `On`

Choices:

- Korean: `공유하지 않음` / `사용 분석 공유`
- English: `Don't share` / `Share usage analytics`

Explanation:

> Choose whether to share limited usage information that isn't linked to your
> account. This includes screens and exhibitions shown; completed saves,
> visits, follows, and map handoffs; when sharing options open; recommendation
> result counts and rank ranges; and coarse route size, distance and time
> ranges. It never includes your account, searches, precise
> location, saved or visited lists, recommendation profile, or route. Usage
> analytics is off by default, and every feature works without it.

> 계정과 연결되지 않은 제한된 사용 정보를 공유할지 선택하세요. 화면과 전시를 본
> 기록, 저장·방문·팔로우·지도 연결이 완료된 기록, 공유 화면을 연 기록, 추천 결과
> 수와 순위 구간, 이동 경로의 대략적인 정류장 수·거리·시간 구간만 포함합니다. 계정
> 정보, 검색어, 정확한 위치, 저장·방문 목록,
> 추천 취향 정보나 실제 이동 경로는 전송하지 않습니다. 사용 분석은 기본적으로 꺼져
> 있으며, 공유하지 않아도 모든 기능을 사용할 수 있습니다.

Policy link:

- Korean: `개인정보 처리방침 보기`
- English: `Read Privacy Policy`

## Activation checklist

- [ ] The updated privacy page is deployed at both public URLs above.
- [ ] Android and iOS expose the bilingual preference, default it to off, and
  prove that disabling clears the queue before returning success.
- [ ] Android's merged release manifest keeps Firebase Analytics disabled and
  contains no advertising-ID permission introduced by this work.
- [ ] Google Play Data safety answers are updated and their preview is reviewed.
- [ ] App Store Connect privacy answers are updated, `PrivacyInfo.xcprivacy` is
  included in the app target, and the archive privacy report is reviewed.
- [ ] Database tests prove the seven-day deduplication, 24-hour rate-limit, and
  24-calendar-month reporting windows plus hourly cleanup.
- [ ] Disabled Android and iOS builds make no analytics request.
- [ ] Staging verifies opt-in, offline retry, opt-out purge, and redacted failure
  behavior without account, location, recommendation-profile, or route leakage.
- [ ] Production enablement has separate operator approval; passing tests or
  store review alone does not authorize changing the release or server kill
  switch.
