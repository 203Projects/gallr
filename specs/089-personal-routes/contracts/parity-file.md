# Contract: opening-hours and status-label parity file

One checked-in JSON file, `specs/089-personal-routes/contracts/opening-hours-parity.json`, is the single source of expected answers for both readers (E-D10, re-review Section 2).

## Shape

```json
{
  "version": 1,
  "hours": [
    {
      "id": "golden-01",
      "input": "10am - 6pm\nTuesday - Saturday",
      "completeness": "COMPLETE",
      "week": {
        "MON": null,
        "TUE": ["10:00", "18:00"],
        "WED": ["10:00", "18:00"],
        "THU": ["10:00", "18:00"],
        "FRI": ["10:00", "18:00"],
        "SAT": ["10:00", "18:00"],
        "SUN": null
      }
    }
  ],
  "statusLabels": {
    "ClosedOnPlannedDay": { "ko": "! 휴관일", "en": "! CLOSED THAT DAY" },
    "ArrivesAfterClose": { "ko": "! {arrival} 도착 · {closes} 마감", "en": "! ARRIVES {arrival} · CLOSES {closes}" },
    "VisitCutShort": { "ko": "! 관람 {minutes}분밖에 없어요", "en": "! ONLY {minutes} MIN TO VISIT" },
    "NotYetOpen": { "ko": "! {date} 개막", "en": "! OPENS {date}" },
    "Ended": { "ko": "! 종료된 전시", "en": "! EXHIBITION ENDED" },
    "Unavailable": { "ko": "! 더 이상 볼 수 없는 전시", "en": "! NO LONGER LISTED" },
    "HoursUnknown": { "ko": "운영 시간 미확인", "en": "HOURS UNCONFIRMED" }
  }
}
```

`week` uses `null` for closed days; `completeness` is `COMPLETE`, `PARTIAL` or `UNKNOWN` with an empty week for unknown. The `hours` array holds the 21 golden cases of `specs/088-discovery-accuracy-fixes/contracts/opening-hours-grammar.md` (ids `golden-01`…`golden-21`) and the 68 hand-checked catalogue strings from `PublishedCatalogueHoursExpectations` (ids `catalogue-<exhibition id>`). English label wording is fixed here and may be refined in DESIGN.md before implementation, changing both readers together.

## Placeholders

`{arrival}` and `{closes}` are 24-hour `HH:MM`; `{minutes}` is a whole number; `{date}` is `M.D` (for example `10.24`). When the arrival falls after midnight of the planned day, `{arrival}` is "자정 이후" / "AFTER MIDNIGHT" instead of a clock time, because the app's timeline does not run into the next day.

## Consumers

- Kotlin: `shared/src/androidHostTest` asserts `parseOpeningHours` and the catalogue table match the file; `composeApp/src/androidHostTest` asserts the composer's status-label table matches it. Both fail when either side changes alone; the file is a declared Gradle test input.
- Web: a Node test asserts the JavaScript port and the page's label table match the same file.
