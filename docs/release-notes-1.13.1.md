# gallr 1.13.1 — Release Candidate Notes

**Version:** 1.13.1

**Android versionCode:** 42

**iOS build:** 32

**Candidate date:** 2026-10-14

**Reader source for release artifacts:** `canonical-v2`

**Previous store release:** 1.10.1 (Android 36, iOS 30).

**Replaces:** the 1.13.0 store submissions (Android 41, iOS 31), which were in review and never published. They are
withdrawn before 1.13.1 is submitted, the same way the unreleased 1.8.0 submissions gave way to 1.8.1. Readers meet
everything in [`release-notes-1.13.0.md`](release-notes-1.13.0.md) for the first time in this release.

---

## Store “What’s New”

Unchanged from 1.13.0, because no reader received that version.

### Korean (`ko-KR`)

```text
홈이 새로워졌습니다.
• 이미지 중심의 새 홈: 내 취향 전시, 상황별 컬렉션, 무료 관람과 가족 전시
• 나만의 전시 동선을 만들고 링크로 공유할 수 있습니다
• 전시 페이지에서 전시 전경 사진을 넘겨 볼 수 있습니다
• 지도 핀에 전시 포스터가 보입니다
```

### English (`en-US`)

```text
A new Home.
• An image-led Home with picks for your taste, collections for the day you are having, free and family exhibitions
• Build your own exhibition route and share it with a link
• Swipe through installation views on the exhibition page
• Map pins now show the exhibition's cover
```

---

## Candidate scope

Everything in the 1.13.0 candidate, plus:

- **Mobile:** Korean typed into the List tab search field stays in order. Before, the caret jumped to the start after
  the first syllable (#345).
- **Admin, already in production since 2026-10-14 15:43 KST:** the Notes review beside the list (#343) and picture
  slides in note carousels (#344). Migration `20261014110000` is applied to staging and production.

## Verification evidence

Recorded for this candidate's tree (`develop` at `1da877ed` plus the version change):

- Shared Kotlin: 646 Android host tests and 644 iOS simulator tests pass. Compose app: 599 Android host tests and
  595 iOS simulator tests pass. ktlint, Android lint and the debug build pass.
- The Debug iOS app built for the simulator, launched against production, and opened the Home and List tabs.
- CI on #345 and on the promotion pull request #346 passed, including the iOS simulator tests and the uncached iOS
  link.

Not yet verified: typing Korean with a real keyboard on a device or simulator. The automated test enters whole
syllables, not the keyboard's syllable composition. Do it on both platforms before submission.

## Release order

1. Merge this version change to `develop`, then promote `develop` to `main`. No migration or Admin change is pending.
2. Build the signed Android App Bundle (versionCode 42) with the Play-registered upload key and the signed iOS archive
   (build 32), both with the `canonical-v2` reader.
3. Withdraw the 1.13.0 submissions: remove iOS 1.13.0 (31) from App Review, and replace the Android 1.13.0 (41)
   production release that is in review.
4. Upload both builds to their testing tracks, run the device pass, then submit 1.13.1 for review on both stores.

## Release boundary and remaining gates

- [x] Version source, Android version, iOS version, changelog and candidate notes are synchronized.
- [x] Staging and production migrations applied and verified (`20261014110000`, owner-approved on 2026-10-14).
- [ ] Release pull request to `main` merged.
- [ ] Signed Android App Bundle built and published to Internal testing.
- [ ] Signed iOS archive exported and uploaded to App Store Connect.
- [ ] 1.13.0 submissions withdrawn on both stores.
- [ ] Device pass on both platforms, including Korean search on the List tab.
- [ ] Review submission on both stores.
- [ ] Publish the approved release in Play Console and release the approved version in App Store Connect.

Each unchecked store step is a separate owner approval. No tag or GitHub Release has been created.
