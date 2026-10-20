# Feature Specification: Always-visible tab bar

**Feature Branch**: `094-persistent-tab-bar`
**Created**: 2026-10-15
**Status**: Draft
**Input**: User description: "Always-visible bottom tab bar. Today the bottom navigation (홈/목록/지도/MY) shows only on the four tab screens; every other screen replaces the whole tab screen and hides the bar, so people lose their bearings while adding routes or doing other actions. Owner decisions (2026-10-15): the bar stays visible on every screen; each tab remembers its own open screens; the bar steps aside only while the keyboard is open."

## Owner decisions (2026-10-15)

1. **Always visible.** The tab bar (홈 / 목록 / 지도 / MY) stays on screen everywhere in the app, so a visitor can always see where they are and reach any tab in one tap.
2. **Each tab remembers.** Every tab keeps its own trail of open screens. Switching away and back returns to where the visitor was; tapping the tab they are already on returns that tab to its top screen.
3. **One exception: typing.** While the keyboard is open the bar steps aside, so text fields and a screen's own action buttons are not squeezed. The full-screen route map keeps the bar.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Reach any tab from any screen (Priority: P1)

A visitor building a route opens an exhibition from the composer, then an exhibition's gallery, and wants to check the map. Today the tab bar is gone on all of these screens, so they must back out step by step. With this feature the tab bar is on every screen, the tab the screens belong to is highlighted, and one tap on 지도 shows the map.

**Why this priority**: This is the owner's request: losing the tab bar while adding routes or doing other actions makes people lose their bearings. Everything else builds on the bar being there.

**Independent Test**: Open each screen that today hides the bar (exhibition detail, gallery detail, event detail, editor detail, For You, a collection list, the route composer, a public route preview, the editor selector, settings) and confirm the bar is visible, one tab is highlighted, and each tab can be reached in one tap.

**Acceptance Scenarios**:

1. **Given** the visitor opened an exhibition from Home, **When** the detail screen shows, **Then** the tab bar is visible at the bottom with 홈 highlighted.
2. **Given** the visitor is on the route composer opened from MY, **When** they look at the bottom of the screen, **Then** the composer's 저장 / 공유 bar sits directly above the tab bar, MY is highlighted and nothing in the composer is hidden behind either bar.
3. **Given** any screen with the bar visible, **When** the visitor taps another tab, **Then** that tab shows immediately.
4. **Given** a scrolling screen with the bar visible, **When** the visitor scrolls to the end, **Then** the last item is fully visible above the bar (and above the screen's own action bar, when it has one).
5. **Given** the full-screen route map is open, **When** it shows, **Then** the map fills the space above the tab bar and its close and fit-route controls stay reachable.

---

### User Story 2 - Each tab keeps its place (Priority: P1)

A visitor is halfway through a route in the composer under MY, switches to 목록 to look something up, opens an exhibition there, then goes back to MY. They land in the composer exactly as they left it. Back on 목록 they find the exhibition they had opened.

**Why this priority**: Without it, a visible tab bar becomes a trap: one tap would throw away whatever the visitor was doing. The owner chose this behaviour explicitly.

**Independent Test**: Open a screen two levels deep in one tab, switch to each other tab and open something there, then return to the first tab and confirm the same screen, scroll position and unsaved input are back.

**Acceptance Scenarios**:

1. **Given** the visitor opened exhibition A from Home and scrolled halfway down, **When** they tap 지도 and then 홈, **Then** exhibition A shows again at the same scroll position.
2. **Given** the visitor typed a route name in the composer without saving, **When** they switch tabs and come back, **Then** the composer shows with the typed name still there.
3. **Given** the visitor is two screens deep in 목록, **When** they tap 목록 again, **Then** 목록 returns to its top screen and the screens that were open in it are closed.
4. **Given** the visitor is already on a tab's top screen, **When** they tap that tab, **Then** nothing changes.
5. **Given** a screen opened from a tab, **When** it shows, **Then** it belongs to that tab: the bar highlights that tab and switching away and back returns to it.

---

### User Story 3 - Back steps through the current tab only (Priority: P2)

On Android the visitor presses system back; on either platform they tap a screen's on-screen back arrow. Back closes the screen on top of the current tab and shows the one under it, never jumping to another tab's screens.

**Why this priority**: With every tab keeping its own trail, back must stay predictable. It refines User Story 2 rather than adding new value, so it comes after.

**Independent Test**: Build a trail of three screens in one tab and a different trail in another, then press back repeatedly in each and confirm the order.

**Acceptance Scenarios**:

1. **Given** Home → exhibition A → gallery G is open in 홈, **When** the visitor presses back, **Then** exhibition A shows; pressing back again shows the Home top screen.
2. **Given** the visitor is at a tab's top screen, **When** they press Android system back, **Then** the app behaves as it does today at that tab.
3. **Given** a sheet, dialog or the full-screen route map is open, **When** the visitor presses back, **Then** only that sheet, dialog or map closes.
4. **Given** a screen asked the visitor to sign in (for example saving a route), **When** they finish or cancel signing in, **Then** they return to that screen, in the same tab.

---

### User Story 4 - The bar steps aside while typing (Priority: P2)

A visitor taps the route name field in the composer, or the search field when adding a stop. The keyboard opens and the tab bar steps aside so the field and the screen's own buttons stay in view. When the keyboard closes the bar returns.

**Why this priority**: The owner named this as the only exception. Without it the keyboard, the screen's action bar and the tab bar would stack and squeeze the content on small phones.

**Independent Test**: On every screen with a text field, open the keyboard and confirm the bar is hidden and the field is visible, then close the keyboard and confirm the bar is back.

**Acceptance Scenarios**:

1. **Given** the composer is open, **When** the visitor focuses the route name field, **Then** the tab bar is hidden while the keyboard is open and the field stays visible.
2. **Given** the keyboard is open, **When** the visitor dismisses it, **Then** the tab bar reappears in the same place without the screen jumping.
3. **Given** the visitor is signing in under MY, **When** the email or password field has focus, **Then** the tab bar is hidden and both fields and the sign-in button can be reached.

---

### Edge Cases

- **Opening a screen that is already open.** Opening the exhibition shown on top of the current tab again does not stack a second copy.
- **Long trails.** Moving between exhibitions and galleries can build a long trail; back still walks it in order.
- **Cross-tab actions.** An action whose purpose is MY's own top screen (the route sheet's 모두 보기 to 내 동선, adding past visits) switches to MY and returns MY to its top screen, as tapping MY again would. Sign-in is not one of them: it opens on top of the screen that asked (FR-008).
- **One route draft.** There is one route being composed; opening the composer while it is already open in another tab switches to that tab instead of opening a second copy.
- **Screens that today switch tab silently.** For You and collection lists open in 홈 today; they keep opening in 홈 when started from Home, and in the current tab otherwise.
- **Sheets and dialogs.** Bottom sheets (adding a stop, reporting a route, overlapping exhibitions on the map, the sign-up nudge) and dialogs may cover the bar while open, as today.
- **Picture cropping.** The full-screen crop tool used when choosing a profile or route picture is a modal step and may cover the bar while open.
- **Splash.** The launch splash covers everything, as today.
- **Rotation and returning from background.** The current tab and each tab's open screens survive a configuration change and the app returning from the background.
- **Process restart.** If the system ends the app in the background, it may reopen at the top of the tab that was showing; in-progress route drafts are kept as today.
- **Large text and screen readers.** The bar keeps its current sizes and labels; a screen reader reads the screen's content before the bar, and the highlighted tab is announced as selected.
- **Removed content.** If an exhibition or route on a remembered screen is gone when the visitor returns, that screen shows its existing not-found state.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The tab bar MUST be visible on every screen of the app except while the keyboard is open, while a modal sheet, dialog or the crop tool covers it, and during the launch splash.
- **FR-002**: Every screen MUST belong to one tab, and the bar MUST highlight that tab while the screen shows.
- **FR-003**: A screen opened from a tab's screen MUST open in that tab, except actions whose purpose is another tab (FR-008).
- **FR-004**: Each tab MUST keep its own ordered trail of open screens while the visitor uses other tabs.
- **FR-005**: Switching to a tab MUST show the screen on top of that tab's trail, with its scroll position and unsaved input as they were.
- **FR-006**: Tapping the tab already selected MUST return it to its top screen and close the other screens in its trail; at its top screen, it changes nothing.
- **FR-007**: Back (Android system back and on-screen back controls) MUST close the top screen of the current tab's trail only; an open sheet, dialog or full-screen map closes first. At a tab's top screen, Android back MUST behave as it does today.
- **FR-008**: Sign-in requested by a screen MUST open on top of that screen in the same tab; after signing in or cancelling, the visitor MUST return to that screen. Requests for MY's own top screen (내 동선 from the route sheet, adding past visits) MUST switch to MY and return it to its top screen. *(Amended 2026-10-15 during planning, research R5: switching to MY for sign-in would discard a composer open in MY's own trail.)*
- **FR-009**: Opening the screen already on top of the current tab MUST NOT add a duplicate to the trail.
- **FR-010**: Screens with their own bottom action bar (route composer 저장 / 공유, public route preview 내 동선으로 복사) MUST show it directly above the tab bar, and no content MUST be hidden behind either bar.
- **FR-011**: While the keyboard is open the tab bar MUST step aside; the focused field and the screen's own action bar MUST stay visible. When the keyboard closes the bar MUST return.
- **FR-012**: The full-screen route map MUST keep the tab bar visible and fill the space above it, with its controls reachable.
- **FR-013**: The current tab and every tab's trail MUST survive rotation and the app returning from the background.
- **FR-014**: The bar's look, labels (홈 / 목록 / 지도 / MY, both languages) and accessibility semantics MUST stay as they are today.
- **FR-015**: Existing screen-view analytics MUST keep reporting the screen being viewed, now also when it is reached by switching tabs.

### Key Entities

- **Tab**: one of 홈, 목록, 지도, MY. Has a top screen and a trail.
- **Trail**: the ordered screens opened in one tab, top screen first; the last one is what shows. Kept per tab while the app runs.
- **Screen**: anything the visitor navigates to (an exhibition, a gallery, an event, an editor, For You, a collection, the route composer, a public route preview, the editor selector, settings), with the input and scroll position it holds.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: On every screen listed in User Story 1, any tab is reachable in one tap (today: up to as many back steps as screens open).
- **SC-002**: After switching away and back, the visitor sees the same screen, scroll position and unsaved input in 100% of the tested trails (two screens deep in each of the four tabs).
- **SC-003**: On every scrolling screen, the last item can be read in full above the tab bar and the screen's own action bar, at the default and the largest supported text size.
- **SC-004**: On every screen with a text field, the focused field and the screen's own action button are visible while typing on the smallest supported phone.
- **SC-005**: Back walks the current tab's trail in reverse order and never shows a screen from another tab in 100% of the tested trails.
- **SC-006**: No change in the steps needed to complete existing flows (save a route, copy a public route, sign in from a route, save an exhibition).

## Assumptions

- The bar keeps today's design, height and labels; a visual restyle is a separate feature.
- "Today" for Android back at a tab's top screen means the behaviour the app has now, which this feature does not change.
- Re-tapping the selected tab at its top screen does nothing; scrolling that screen to its beginning is not part of this feature.
- After the system ends the app in the background, opening it again may start at the top of the last tab; the composer draft survives as it does today.
- The route planner screen is not reachable from the app today (nothing opens it); it gets the same treatment so it works if it is reached again, and making it reachable is out of scope.
- Out of scope: new tabs, a restyled bar, deep links, the web site and the Admin and Gallery workspaces.
