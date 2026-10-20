# Feature Specification: Required app update

**Feature Branch**: `098-required-app-update`
**Created**: 2026-10-20
**Status**: Draft
**Input**: User description: "Can I force people to update to the latest version with this release? I think this was possible on Android but I'm not sure on iOS."

## Background

Neither app checks a minimum version today, so nothing can make a visitor on an old build update: a bug fixed in a
new release (such as the share sheet that never opened in 1.13) stays with everyone who does not update by choice.
Google Play offers an in-app update flow, but only to apps that ask for it; Apple offers no forcing at all. Both
platforms therefore need the same thing: the app itself compares its version with a minimum that gallr publishes,
and stops at an update screen when it is older. Only builds that carry this check can ever be gated, which is why it
goes into this release: it makes this release the floor for every later one.

## Owner decision (2026-10-20)

Ship the version gate in this release. Staff set the minimum supported version per platform on gallr's own server;
no app release is needed to raise it. A failed read never blocks anyone. No paid or third-party configuration service.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - An old build is stopped at an update screen (Priority: P1)

A visitor opens gallr on a build older than the minimum staff have set for their platform. Instead of the Home tab
they see a full-screen notice, "업데이트가 필요해요" / "UPDATE REQUIRED", a short line saying the version they have is no
longer supported, and one button, "업데이트" / "UPDATE", that opens gallr's listing in the Play Store or the App Store.
Nothing else in the app can be reached until the update is installed. After updating, the app opens normally.

**Why this priority**: This is the owner's question answered: a release that fixes something can be required.

**Independent Test**: Set the minimum for a platform above the installed build's version; opening the app shows the
notice and the button opens the store listing; lowering the minimum and reopening restores the app.

**Acceptance Scenarios**:

1. **Given** the minimum for the platform is newer than the installed version, **When** the app opens, **Then** the
   update notice covers the whole screen, no tab or screen behind it responds, and the button opens the store listing.
2. **Given** the notice is showing, **When** the visitor returns from the store without updating, **Then** the notice
   is still there.
3. **Given** the minimum equals or is older than the installed version, **When** the app opens, **Then** nothing
   changes from today.
4. **Given** the app is open and in use, **When** staff raise the minimum above the installed version and the visitor
   brings the app back to the foreground, **Then** the notice appears within one minute of the app coming back.
5. **Given** the notice is showing in Korean, **When** the device language is English, **Then** every word on it is
   English.
6. **Given** Android, **When** Google Play can update the app in place, **Then** the button may hand over to Play's own
   immediate update flow instead of the listing, and a visitor who cancels that flow is back at the notice.

---

### User Story 2 - A failed read never blocks anyone (Priority: P1)

A visitor opens gallr on a train with no signal, or while gallr's server is down. The app opens exactly as today;
the version check waits until the setting can be read.

**Why this priority**: A gate that fails closed would lock out every visitor during an outage.

**Independent Test**: With the network off, or the setting removed, open the app on a build below the minimum: the
app opens as today. Restore the setting and bring the app to the foreground: the notice appears.

**Acceptance Scenarios**:

1. **Given** the setting cannot be read, **When** the app opens, **Then** no notice shows and nothing is slower than
   today.
2. **Given** the setting is malformed (an unparseable version), **When** the app reads it, **Then** it is treated as
   unreadable: no notice.
3. **Given** the setting was read once and said "update required", **When** the app is reopened offline, **Then** the
   notice shows again (the last successful answer is remembered until a newer read replaces it).

---

### User Story 3 - A recommended update is a gentle nudge (Priority: P2)

Staff also set a recommended version, newer than the minimum. A visitor on a build below it but at or above the
minimum sees a small notice at the top of the Home tab, "새 버전이 있어요" / "A NEW VERSION IS AVAILABLE", with "업데이트"
and "나중에" / "LATER"; "나중에" hides it until the next day. The app stays fully usable.

**Why this priority**: Most releases want a nudge, not a wall; the two share one setting and one check.

**Independent Test**: Set the recommended version above the installed build and the minimum below it: the nudge
shows on Home, "나중에" hides it, and it is back the next day.

**Acceptance Scenarios**:

1. **Given** minimum ≤ installed < recommended, **When** Home shows, **Then** the nudge shows once per day at most.
2. **Given** the visitor tapped "나중에", **When** they reopen the app the same day, **Then** no nudge.
3. **Given** installed ≥ recommended, **When** Home shows, **Then** no nudge.
4. **Given** installed < minimum, **When** the app opens, **Then** the required notice shows, never the nudge.

---

### User Story 4 - Staff raise the minimum without a release (Priority: P2)

After shipping a fix, a staff member sets the minimum supported version for Android and iOS to the fixed version.
Visitors on older builds see the notice the next time they open or return to the app. Lowering the version lifts the
gate just as quickly. Each platform has its own values, since store review lands at different times.

**Why this priority**: The gate is only useful if staff can move it on the day a fix ships.

**Independent Test**: Change the value for one platform; the other platform is unaffected; the change takes effect
on the next app open or return to the foreground without an app release.

**Acceptance Scenarios**:

1. **Given** staff set Android's minimum to 1.14.0 and leave iOS at 1.13.0, **When** a 1.13.1 Android visitor and a
   1.13.1 iOS visitor open the app, **Then** only the Android visitor sees the notice.
2. **Given** a value is being changed, **When** it is saved, **Then** it is recorded who changed it and when.
3. **Given** a staff member enters a version that is not three numbers, **When** they save, **Then** the save is
   refused with a message and the previous value stays.

### Edge Cases

- **Store listing cannot open** (no store app, no browser): the notice stays; the button does nothing harmful and
  reads a short line saying to update from the store.
- **Version formats**: versions compare numerically by major, minor, patch (1.13.1 < 1.14.0 < 1.14.10); a build
  with a suffix (debug, beta) compares by its numbers only.
- **Minimum set above the latest store version by mistake**: every visitor would be stopped; the Admin field warns
  when the value is newer than the last released version staff recorded, and the value can be lowered at once.
- **Clock changes** do not matter: the "once a day" nudge uses the device's calendar date.
- **Check timing**: the check never delays the first screen; the notice replaces the screen when the answer
  arrives, within a second on a normal connection.
- **Very old builds** (before this feature): cannot be reached by any value; out of scope, stated as such.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The app MUST read, at launch and whenever it returns to the foreground after at least one minute away,
  the minimum supported version and the recommended version for its platform from gallr's own server.
- **FR-002**: When the installed version is older than the minimum, the app MUST show the required-update notice over
  everything, with one button to the platform's store listing, and MUST keep showing it until the installed version
  is at or above the minimum.
- **FR-003**: On Android the button MAY start Google Play's immediate in-app update when available; cancelling it
  returns to the notice.
- **FR-004**: When the installed version is at or above the minimum but below the recommended version, the app MUST
  show a dismissable nudge on the Home tab at most once per calendar day.
- **FR-005**: When the setting cannot be read or parsed, the app MUST behave as if no versions were set, except that
  the last successfully read answer is kept and applied until a newer read replaces it.
- **FR-006**: Versions MUST compare numerically by major, minor and patch; any suffix is ignored.
- **FR-007**: The setting MUST be per platform (Android, iOS) and editable by staff without an app release, with the
  editor and time of the last change recorded; a value that is not three numbers MUST be refused.
- **FR-008**: The notice and nudge MUST be bilingual (KO/EN) and follow DESIGN.md: monochrome, the one accent only on
  the primary button.
- **FR-009**: The check MUST NOT delay the first screen or send anything beyond the platform and installed version.
- **FR-010**: The feature MUST use gallr's own server only; no paid or third-party remote-configuration service.

### Key Entities

- **App version policy**: per platform, the minimum supported version, the recommended version, who changed it and
  when, and optionally the last released version for the Admin warning.
- **Installed version**: the running app's version, read from the build.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: With the minimum set above a build's version, 100% of launches of that build on both platforms stop at
  the notice; with it at or below, 0% do.
- **SC-002**: A change to the setting reaches an open app within one minute of it returning to the foreground, and a
  fresh launch immediately, with no app release.
- **SC-003**: With the server unreachable, the app opens as fast as today (no added wait before the first screen) and
  shows no notice unless a previous read required one.
- **SC-004**: The nudge appears at most once per calendar day per device and never when an update is required.
- **SC-005**: Staff can change the minimum for one platform in under a minute, and the change is attributed.

## Assumptions

- The setting lives beside gallr's other server-side configuration and is readable by the app without an account,
  like the catalogue; writes need staff access.
- Staff edit it in Admin; until an Admin screen exists, editing the setting directly is acceptable for staff.
- "Returns to the foreground" means the app was in the background for at least one minute, so switching apps briefly
  does not re-read the setting.
- The Android in-app update flow is optional; the store listing is the baseline on both platforms.
- The first release carrying this feature sets both versions to that release's own version (no one is gated by it),
  so the gate is in place for the next release.
