// Feature entries for the Feature Showcase section.
// `headlineKo` is rendered as the primary feature h3 (KO-only per spec).
// `headline` (EN) is kept as reference data — features.html does not render it.
// `descriptionKo` renders as primary body text; `description` (EN) renders inside .bi-en.
// Mockup data is Korean-only — the in-app card renders in Korean.
module.exports = [
  {
    id: "discovery",
    headlineKo: "내 근처 전시 찾기",
    headline: "Find exhibitions near you",
    descriptionKo:
      "지금 진행 중이거나 오픈 예정인 전시를 한눈에 확인하세요. 추천 전시, 에디터 픽, 그리고 이번 주 오픈·종료 전시를 큐레이션합니다.",
    description:
      "Browse ongoing and upcoming exhibitions in your city with filters — Featured, Editor's picks, Opening This Week, and Closing This Week.",
    mockup: {
      titleKo: "리움: 소장품 특별전",
      venueKo: "리움미술관",
      dateRangeKo: "2026년 1월 15일 — 4월 28일",
    },
  },
  {
    id: "bookmarking",
    headlineKo: "관심 전시 저장하기",
    headline: "Save what interests you",
    descriptionKo:
      "마음에 드는 전시를 저장해 나만의 리스트를 만들어보세요. 저장한 전시는 오프라인에서도 언제든 확인할 수 있어요.",
    description:
      "Bookmark any exhibition to build your personal shortlist. Your saved exhibitions are available offline, so you always have your list at hand.",
    mockup: {
      titleKo: "추상 기하학의 세계",
      venueKo: "아모레퍼시픽미술관",
      dateRangeKo: "2026년 3월 3일 — 6월 12일",
    },
  },
  {
    id: "filtering",
    headlineKo: "원하는 기준으로 필터링",
    headline: "Filter by what matters",
    descriptionKo:
      "지역, 추천, 에디터 픽, 일정별로 전시를 필터링하고 나에게 필요한 전시만 골라보세요.",
    description:
      "Narrow your view by region, featured picks, editor's picks, or timing — opening this week, closing this week. See only what's relevant to you.",
    mockup: {
      titleKo: "사진, 지금",
      venueKo: "국제갤러리",
      dateRangeKo: "2026년 3월 20일 오픈",
    },
  },
];
