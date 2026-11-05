// The words that imply each reviewed art term, copied from the app (shared/.../taste/ArtTermVocabulary.kt) in
// vocabulary order. artTermSuggestions.test.ts checks this table against
// specs/101-taste-listening/contracts/art-term-words.json, which the Kotlin host test ArtTermWordsParityHostTest keeps
// equal to the app, so a change on either side fails a test. Korean words match as substrings, English words as
// whole words regardless of case; a term with no words is staff-only and is never suggested.
export interface ArtTermWords {
  id: string;
  korean: readonly string[];
  english: readonly string[];
}

export const ART_TERM_WORDS: readonly ArtTermWords[] = [
  { id: "medium:painting", korean: ["회화", "페인팅", "유화", "아크릴", "캔버스"], english: ["painting", "paintings", "canvas", "canvases", "oil on", "acrylic"] },
  { id: "medium:sculpture", korean: ["조각"], english: ["sculpture", "sculptures", "sculptural"] },
  { id: "medium:photography", korean: ["사진전", "사진 작", "사진작", "사진가", "사진 시리즈", "사진 연작", "포토그래피"], english: ["photograph", "photographs", "photography", "photographic"] },
  { id: "medium:installation", korean: ["설치 작", "설치작", "설치미술", "설치 미술", "인스톨레이션"], english: ["installation", "installations"] },
  { id: "medium:video", korean: ["비디오", "영상 작", "영상작", "영상 설치", "무빙 이미지", "무빙이미지", "싱글 채널", "싱글채널", "필름"], english: ["video", "videos", "moving image", "moving-image", "single-channel", "film", "films"] },
  { id: "medium:digital", korean: ["디지털", "미디어 아트", "미디어아트", "뉴미디어", "뉴 미디어", "인공지능", "알고리즘", "생성형"], english: ["digital", "media art", "new media", "artificial intelligence", "generative", "algorithm", "algorithmic"] },
  { id: "medium:performance", korean: ["퍼포먼스", "행위 예술", "행위예술"], english: ["performance", "performances", "performative"] },
  { id: "medium:drawing", korean: ["드로잉", "소묘"], english: ["drawing", "drawings"] },
  { id: "medium:printmaking", korean: ["판화", "실크스크린", "리소그래프", "에칭"], english: ["printmaking", "woodcut", "etching", "lithograph", "lithographs", "silkscreen", "screenprint", "screen print"] },
  { id: "medium:craft", korean: ["공예", "도자", "도예", "세라믹", "텍스타일", "직조"], english: ["craft", "crafts", "ceramic", "ceramics", "textile", "textiles", "weaving"] },
  { id: "medium:ceramics", korean: ["도예", "도자기", "세라믹", "백자", "분청", "고려청자"], english: ["ceramic", "ceramics", "porcelain", "stoneware"] },
  { id: "medium:textile", korean: ["섬유", "직물", "패브릭", "텍스타일", "직조"], english: ["textile", "textiles", "fabric", "fabrics", "weaving", "woven"] },
  { id: "medium:sound", korean: ["사운드"], english: ["sound art", "sound installation", "sound installations", "sound work", "sound works"] },
  { id: "medium:media-art", korean: ["미디어아트", "미디어 아트"], english: ["media art"] },
  { id: "medium:architecture-design", korean: ["건축"], english: ["architecture", "architectural"] },
  { id: "style:abstract", korean: ["추상"], english: ["abstract", "abstraction"] },
  { id: "style:figurative", korean: ["구상 회화", "구상회화", "구상적", "인물화", "형상적"], english: ["figurative", "figuration"] },
  { id: "style:minimalist", korean: ["미니멀"], english: ["minimal", "minimalist", "minimalism"] },
  { id: "style:conceptual", korean: ["개념미술", "개념 미술", "개념적"], english: ["conceptual", "conceptualism"] },
  { id: "style:documentary", korean: ["다큐멘터리", "다큐"], english: ["documentary"] },
  { id: "style:experimental", korean: ["실험적", "실험 영화", "실험영화", "실험"], english: ["experimental"] },
  { id: "style:dansaekhwa", korean: ["단색화"], english: ["dansaekhwa"] },
  { id: "style:minhwa", korean: ["민화"], english: ["minhwa"] },
  { id: "style:korean-painting", korean: ["한국화", "수묵"], english: ["ink painting", "ink paintings", "korean painting"] },
  { id: "style:pop", korean: ["팝아트", "팝 아트"], english: ["pop art"] },
  { id: "style:surreal", korean: ["초현실"], english: ["surreal", "surrealism", "surrealist"] },
  { id: "theme:identity", korean: ["정체성"], english: ["identity", "identities"] },
  { id: "theme:memory", korean: ["기억"], english: ["memory", "memories", "remembrance"] },
  { id: "theme:nature", korean: ["자연", "풍경", "생태", "식물"], english: ["nature", "landscape", "landscapes", "ecology", "ecological", "botanical"] },
  { id: "theme:city", korean: ["도시", "메트로폴리스"], english: ["city", "cities", "urban", "metropolis"] },
  { id: "theme:technology", korean: ["테크놀로지", "과학기술", "기계", "로봇", "사이버"], english: ["technology", "technological", "technologies", "machine", "machines", "robot", "robots", "cyber"] },
  { id: "theme:society", korean: ["사회", "공동체", "노동", "정치"], english: ["society", "social", "community", "labor", "labour", "political", "politics"] },
  { id: "theme:body", korean: ["신체"], english: ["bodily", "corporeal", "corporeality", "embodied", "embodiment"] },
  { id: "theme:time", korean: ["시간성", "시간의 흐름", "시간의 층위"], english: ["temporality", "passage of time"] },
  { id: "theme:ecology", korean: ["생태", "기후"], english: ["ecology", "ecological", "climate"] },
  { id: "theme:labor", korean: ["노동"], english: ["labor", "labour"] },
  { id: "theme:home", korean: [], english: [] },
  { id: "theme:gender", korean: ["젠더", "페미니즘", "페미니스트"], english: ["gender", "feminism", "feminist"] },
  { id: "theme:archive", korean: ["아카이브", "아카이빙"], english: ["archive", "archives", "archival"] },
  { id: "theme:landscape", korean: ["풍경", "산수화"], english: ["landscape", "landscapes"] },
  { id: "mood:quiet-meditative", korean: ["고요", "명상", "사유", "관조", "정적인", "침묵"], english: ["quiet", "meditative", "meditation", "contemplative", "contemplation", "stillness", "silence", "serene"] },
  { id: "mood:energetic", korean: ["역동", "에너지", "활력", "강렬"], english: ["energetic", "dynamic", "energy", "vibrant", "intense"] },
  { id: "mood:playful", korean: ["유희", "유머", "위트", "장난", "놀이"], english: ["playful", "humor", "humour", "humorous", "witty", "whimsical"] },
  { id: "mood:unsettling", korean: ["불안", "기괴", "불편", "낯선", "섬뜩"], english: ["unsettling", "uncanny", "eerie", "anxiety", "anxious", "disquiet", "uneasy"] },
  { id: "mood:intimate", korean: ["친밀", "내밀", "사적인", "일상"], english: ["intimate", "intimacy", "everyday", "domestic"] },
  { id: "mood:monumental", korean: ["기념비", "거대한", "대규모", "대형", "웅장"], english: ["monumental", "monumentality", "large-scale", "large scale", "massive"] },
  { id: "mood:dark", korean: [], english: [] },
  { id: "mood:warm", korean: [], english: [] },
  { id: "mood:humorous", korean: ["유머", "해학"], english: ["humor", "humour", "humorous"] },
  { id: "mood:melancholy", korean: [], english: [] },
];
