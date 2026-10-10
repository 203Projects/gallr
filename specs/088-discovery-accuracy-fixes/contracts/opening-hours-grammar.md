# Contract: opening-hours reading

`parseOpeningHours(text: String?): WeeklyOpeningHours` is pure and total. These golden cases come
from the live catalogue on 2026-10-02 plus Korean forms. Each becomes a `commonTest` case. Days are
written Mon…Sun; `—` means closed.

| # | Input (verbatim, `\n` = line break) | Result | Completeness |
|---|---|---|---|
| 1 | `10am - 6pm\nTuesday - Saturday` | Tue–Sat 10:00–18:00; Sun, Mon — | COMPLETE |
| 2 | `11am - 6pm\nTuesday - Sunday ` | Tue–Sun 11:00–18:00; Mon — | COMPLETE |
| 3 | `10am - 6pm\nMonday - Sunday (Wed & Sat ~9pm)` | Mon–Sun 10:00–18:00 (parenthetical ignored) | COMPLETE |
| 4 | `10am - 6pm Monday - Saturday\n10am - 5pm Sunday and National holidays` | Mon–Sat 10:00–18:00; Sun 10:00–17:00 | COMPLETE |
| 5 | `10am - 8pm Tuesday - Thursday\n10am - 9pm Friday\n10am - 7pm Saturday - Sunday` | Tue–Thu 10:00–20:00; Fri 10:00–21:00; Sat–Sun 10:00–19:00; Mon — | COMPLETE |
| 6 | `Tue, Thu, Fri 10:00–18:00 · Wed, Sat 10:00–21:00 · Closed Monday` | Tue, Thu, Fri 10:00–18:00; Wed, Sat 10:00–21:00; Sun, Mon — | COMPLETE |
| 7 | `Tuesday–Sunday 10:00–18:00 · Closed Monday` | Tue–Sun 10:00–18:00; Mon — | COMPLETE |
| 8 | `12pm - 7pm` | every day 12:00–19:00 | PARTIAL |
| 9 | `12pm - 7pm\n` | every day 12:00–19:00 | PARTIAL |
| 10 | `10:30am - 6:30pm\nTuesday - Sunday` | Tue–Sun 10:30–18:30 | COMPLETE |
| 11 | `1pm - 7pm\nMonday - Sunday\nClsoed on 9/25 Friday ` | Mon–Sun 13:00–19:00 (dated note ignored) | COMPLETE |
| 12 | `10am - 6pm Tuesday - Sunday \n(until 9pm on Wednesday, Saturday) ` | Tue–Sun 10:00–18:00 | COMPLETE |
| 13 | `화-일 10:00-18:00, 월요일 휴관` | Tue–Sun 10:00–18:00; Mon — | COMPLETE |
| 14 | `11:00~19:00 (월 휴무)` | every day 11:00–19:00 except Mon — | COMPLETE |
| 15 | `` (blank) or `null` | empty | UNKNOWN |
| 16 | `By appointment only` | empty | UNKNOWN |
| 17 | `10pm - 2am\nFriday - Saturday` | empty (overnight not supported) | UNKNOWN |
| 18 | `9am - 6pm\nMonday - Friday` | Mon–Fri 09:00–18:00; Sat, Sun — | COMPLETE |
| 19 | `Friday - Monday 12pm - 6pm` | Fri, Sat, Sun, Mon 12:00–18:00 (wraps) | COMPLETE |
| 20 | `11am - 7pm Tuesday - Friday\n10am - 6pm Saturday - Sunday\nClosed on Mondays and Public Holidays` | Tue–Fri 11:00–19:00; Sat–Sun 10:00–18:00; Mon — (plural day after `Closed on`; holidays ignored) | COMPLETE |
| 21 | `2pm - 5:30pm\nMonday - Saturday` | Mon–Sat 14:00–17:30 | COMPLETE |
| 22 | `평일 10:00-18:00` | Mon–Fri 10:00–18:00; Sat, Sun — (`평일` is Mon–Fri, not Sunday) | COMPLETE |
| 23 | `평일 10:00-18:00, 주말 11:00-17:00` | Mon–Fri 10:00–18:00; Sat–Sun 11:00–17:00 | COMPLETE |
| 24 | `Weekdays 10am-6pm, weekends 11am-5pm` | Mon–Fri 10:00–18:00; Sat–Sun 11:00–17:00 | COMPLETE |
| 25 | `Mon-Fri 10:00-18:00, Sat 11:00-17:00` | Mon–Fri 10:00–18:00; Sat 11:00–17:00; Sun — | COMPLETE |
| 26 | `Tue-Sat 10:00-12:00, 13:00-18:00` | Tue–Sat 10:00–18:00 (span of both ranges) | PARTIAL |
| 27 | `화-일 10:00-18:00 휴관일: 월요일` | Tue–Sun 10:00–18:00; Mon — | COMPLETE |
| 28 | `휴관일: 월, 화
수-일 10:00-18:00` | Wed–Sun 10:00–18:00; Mon, Tue — | COMPLETE |
| 29 | `화-일 10:00-18:00, 매주 월요일 휴관일` | Tue–Sun 10:00–18:00; Mon — | COMPLETE |
| 30 | `화-토 11:00-18:00 그 외 휴관` | Tue–Sat 11:00–18:00; Sun, Mon — | COMPLETE |

Rules exercised:
- Case 14: a parenthetical is ignored for times, but a closed marker inside it still applies
  (`휴무` / `휴관` / `closed`).
- Cases 3 and 12: parenthetical evening extensions are ignored; the regular closing time applies.
- Days are not inferred from holiday words (case 4) or dated notes (case 11).
- Cases 22–24: `평일`/`weekdays` read as Mon–Fri and `주말`/`weekends` as Sat–Sun; a bare Korean day
  character counts only when it is not part of another word (`평일`, `휴관일`, `일부`).
- Cases 23–25: a comma starts a new group only when hours precede it and a day follows it;
  `Mon, Wed, Fri 10-18` stays one group.
- Case 26: several time ranges on the same days read as the span from the first opening to the last
  closing and the reading is PARTIAL, so it never claims a closure.
- Cases 27–30: closed days may follow the closed word (`휴관일: 월요일`), the list stops at the end of
  its line, and `그 외 휴관` adds nothing beyond the listed days.

Fixture coverage requirement (SC-003): all but at most one of the fixture exhibitions with non-blank hours
(68 in the 2026-10-03 snapshot) read as `COMPLETE` or `PARTIAL`, each equal to a hand-checked expected value
stored next to the fixture.
