# Contract: the visit time control

## Summary row (composer and public route preview)

```text
1곳 관람 어려움 · 3번                       ← verdict (spec 096 US2)
약 2.4 KM · 이동 약 32분 · 총 약 2시간 47분
10.18(토) 13:00 기준 · 12:41 출발           ← reference line (R6); absent for "지금" today-now
방문 시간 · 10.18(토) 13:00 ›                ← the control: 44dp target, labelLarge, onBackground
```

`VisitTimeRow(label: String, onOpen: () -> Unit)`; label from `visitTimeLabel(visitTime, today, language)`:
"지금" / "NOW", "오늘 13:00" / "TODAY 13:00", "내일 13:00" / "TOMORROW 13:00", "10.18(토) 13:00" / "SAT 10.18 13:00".

## Sheet

`VisitTimeSheet(options, selected: RouteVisitTime?, language, onChoose: (RouteVisitTime?) -> Unit, onDismiss)`

- Title "언제 가세요?" / "WHEN ARE YOU GOING?" (heading).
- Day chips, one row, horizontally scrollable: "지금" first, then each day of `options.days` ("오늘", "내일", then
  "토 10.18" / "SAT 10.18"). The chip of the selected day (or 지금) is selected.
- Time chips for the selected day, wrapping, from `options.timesFor(day)`; a day with no times shows
  "오늘은 더 고를 시간이 없어요" / "No more times today".
- "지금" → `onChoose(null)` and close. A day → selects it (no callback). A time → `onChoose(RouteVisitTime(day,
  time))` and close.
- Selected chips: `activeIndicator` accent; others outlined. Every chip is a 44dp target with `Role.RadioButton`
  semantics and a content description naming the day or time.

## View model events

- Composer: `chooseVisitTime(visitTime: RouteVisitTime?)` stores it for the current draft and re-evaluates.
- Preview: `chooseVisitTime(visitTime: RouteVisitTime?)` re-evaluates the open preview only.
