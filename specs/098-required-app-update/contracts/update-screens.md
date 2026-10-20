# Contract: update screens

## Required update (full screen, replaces the tab shell)

| Element | KO | EN |
|---------|----|----|
| Heading (`titleLarge`, `heading()`) | 업데이트가 필요해요 | UPDATE REQUIRED |
| Line (`bodyMedium`, `onSurfaceVariant`) | 지금 쓰는 버전은 더 이상 지원되지 않아요. 업데이트하면 계속 쓸 수 있어요. | This version is no longer supported. Update to keep using gallr. |
| Button (standard black, 52dp, full width) | 업데이트 | UPDATE |
| Fallback line, after a tap that could not open the store (`bodySmall`) | 스토어에서 gallr를 업데이트해 주세요 | Please update gallr from the store |

- Android back does nothing. No other control. The splash overlay may cover it on launch, as it covers everything.
- The button opens the platform's store listing; on Android the in-app immediate update may replace it later (R6).

## Recommended update (Home banner)

| Element | KO | EN |
|---------|----|----|
| Text (`bodyMedium`) | 새 버전이 있어요 | A NEW VERSION IS AVAILABLE |
| Actions (`labelLarge`, 44dp) | 업데이트 · 나중에 | UPDATE · LATER |

- Shown at the top of the Home rails when the requirement is RECOMMENDED and 나중에 was not tapped today.
- Monochrome: Home's card shape (16dp) with a hairline `outlineVariant` border; no accent.
