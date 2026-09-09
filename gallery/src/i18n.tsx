import { createContext, useCallback, useContext, useEffect, useMemo, useState } from "react";

export type PortalLocale = "ko" | "en";

export const GALLERY_LOCALE_STORAGE_KEY = "gallr.gallery.locale.v1";

const englishMessages = {
  language: {
    group: "Language",
    korean: "한국어",
    english: "EN",
  },
  common: {
    brand: "gallr gallery",
    signOut: "Sign out",
    galleryWorkspace: "Gallery workspace",
    configurationRequired: "Configuration required",
    configurationMissing: "Gallery workspace configuration is missing.",
    loadingWorkspace: "Loading gallery workspace…",
    workspaceUnavailable: "Workspace unavailable",
  },
  navigation: {
    setup: "Set up gallery",
    exhibitions: "Exhibitions",
    galleryInfo: "Gallery Info",
    launchKit: "Launch Kit",
  },
  auth: {
    heading: "Publish with gallr",
    checkEmail: "Check your email",
    sentMessage: (email: string) => `Use the secure sign-in message sent to ${email}.`,
    differentEmail: "Use a different email",
    intro: "Manage free exhibition listings for your gallery.",
    email: "Email",
    sending: "Sending…",
    sendCode: "Send sign-in code",
    or: "or",
    openingGoogle: "Opening Google…",
    continueGoogle: "Continue with Google",
  },
  onboarding: {
    title: "Set up your gallery",
    intro: "Search before creating a new gallery workspace.",
    galleryName: "Gallery name",
    searchPlaceholder: "Search Korean or English name",
    search: "Search",
    searchHelp: "Search for your gallery by name to see if it already exists.",
    noMatch: "No matching gallery found.",
    alreadyClaimed: "Already claimed",
    requestAccess: "Request access",
    cantFind: "Can’t find your gallery?",
    createNew: "Create a new gallery",
    backToSearch: "Back to search",
    requestAccessTo: (name: string) => `Request access to ${name}`,
    shareReference: "Share one official reference so staff can verify the claim.",
    officialWebsite: "Official website",
    officialSocial: "Official social profile",
    claimNote: "Claim note",
    submitClaim: "Submit claim",
    createTitle: "Create a new gallery",
    nameKo: "Gallery name (Korean)",
    nameEn: "Gallery name (English)",
    createGallery: "Create gallery",
    suspendedTitle: "Gallery access suspended",
    suspendedBody: "This workspace is unavailable. Contact Gallr support to review access.",
    errors: {
      sendEmail: "Sign-in email could not be sent.",
      google: "Google sign-in could not be started.",
      signupDisabled: "Account creation is temporarily unavailable. Try again later.",
      oauthCallback: "Google sign-in couldn’t be completed. Try again.",
      search: "Gallery search failed.",
      evidence: "Add an official website, social profile, or claim note.",
      claim: "Gallery claim could not be submitted.",
      create: "Gallery workspace could not be created.",
      access: "Gallery access could not be verified.",
      session: "Session could not be verified.",
      signOut: "Sign out failed.",
    },
  },
  galleryInfo: {
    title: "Gallery Info",
    intro: "Defaults copied once into each new exhibition draft.",
    saved: "Gallery Info · Saved",
    galleryVenue: "Gallery and venue",
    nameKo: "Gallery name (Korean)",
    nameEn: "Gallery name (English)",
    venueKo: "Venue name (Korean)",
    venueEn: "Venue name (English)",
    addressMap: "Address and map",
    addressHelp: "Search, review the bounded matches, then choose one to set the canonical address and coordinates.",
    findAddress: "Find an address",
    searching: "Searching…",
    searchAddress: "Search address",
    addressMatches: "Address matches",
    useAddress: (address: string) => `Use this address: ${address}`,
    noMatches: "No address matches found. Try a road name or a broader search.",
    cityKo: "City (Korean)",
    cityEn: "City (English)",
    regionKo: "Region (Korean)",
    regionEn: "Region (English)",
    addressKo: "Address (Korean)",
    addressEn: "Address (English)",
    latitude: "Latitude",
    longitude: "Longitude",
    visitDetails: "Default visit details",
    hours: "Default opening hours",
    contact: "Contact",
    saving: "Saving…",
    save: "Save Gallery Info",
    loading: "Loading Gallery Info…",
    errors: {
      revision: "Gallery Info changed elsewhere. Reload and try again.",
      access: "Gallery Info access is no longer available.",
      geocodeAccess: "Address search is not available for this gallery.",
      authentication: "Sign in again to continue.",
      required: "Complete every bilingual gallery and location field before saving.",
      location: "Select a valid address before saving.",
      unsupportedField: "The form included an unsupported field. Reload and try again.",
      unsupportedFormat: "One or more fields has an unsupported format.",
      invalidForm: "The Gallery Info form was invalid. Reload and try again.",
      rateLimited: "Address search is temporarily limited. Wait a moment and try again.",
      characterLimit: "One or more fields exceeds its character limit.",
      load: "Gallery Info could not be loaded.",
      addressSearch: "Address search failed.",
      save: "Gallery Info could not be saved.",
    },
  },
  exhibitions: {
    statuses: {
      draft: "Draft",
      submitted: "Submitted",
      needsChanges: "Needs changes",
      published: "Published",
      archived: "Archived",
    },
    impact: {
      last30Days: "Last 30 days",
      allTime: "All time",
      caveat: "Public page loads, not unique visitors.",
    },
    fields: {
      nameKo: "Name (Korean)",
      nameEn: "Name (English)",
      venueNameKo: "Venue name (Korean)",
      venueNameEn: "Venue name (English)",
      cityKo: "City (Korean)",
      cityEn: "City (English)",
      regionKo: "Region (Korean)",
      regionEn: "Region (English)",
      addressKo: "Address (Korean)",
      addressEn: "Address (English)",
      latitude: "Latitude",
      longitude: "Longitude",
      openingDate: "Opening date",
      closingDate: "Closing date",
      descriptionKo: "Description (Korean)",
      descriptionEn: "Description (English)",
      hours: "Hours",
      contact: "Contact",
      receptionDate: "Opening reception date",
      receptionStartTime: "Opening reception time",
      ticketUrl: "Ticket URL",
    },
    art: {
      heading: "Artists & descriptors",
      reviewHelp: "These suggestions stay private until Gallr staff reviews and publishes this exhibition.",
      unsupported: "Art metadata is not available on this server. You can continue editing the exhibition.",
      artists: "Artists",
      orderedCredits: "Ordered artist credits",
      noArtists: "No artists added yet.",
      unresolved: "SUGGESTED",
      search: "Search canonical artists",
      searching: "Searching…",
      noMatches: "No matching canonical artists.",
      searchFailed: "! Artist search failed.",
      useArtist: (name: string) => `Add ${name}`,
      moveUp: (name: string) => `Move ${name} up`,
      moveDown: (name: string) => `Move ${name} down`,
      remove: (name: string) => `Remove ${name}`,
      suggestionHeading: "Suggest an artist",
      suggestionKo: "Suggested artist name (Korean)",
      suggestionEn: "Suggested artist name (English)",
      addSuggestion: "Add artist suggestion",
      terms: "Controlled descriptors",
      medium: "Medium",
      style: "Style / movement",
      theme: "Theme / subject",
      mood: "Mood / tone",
      loadFailed: "! Controlled descriptors could not be loaded.",
    },
    validation: {
      tooLong: (label: string, limit: string) => `${label} must be ${limit} characters or fewer.`,
      coordinatePair: "Latitude and longitude must be provided together.",
      coordinateRange: "Coordinates are outside the valid range.",
      invalidDate: (label: string) => `${label} must be a valid calendar date.`,
      invalidTime: "Opening reception time must use 24-hour HH:MM format.",
      invalidTicket: "Ticket URL must start with http:// or https://.",
      required: "Required for submission.",
      requiredSummary: (label: string) => `${label} is required for submission.`,
      closingBeforeOpening: "Closing date must be on or after the opening date.",
      locationRequirement: "Location (search and choose an address)",
      coverRequirement: "Cover image",
      fixBeforeSave: "Fix the highlighted fields before saving.",
      fixBeforeCover: "Fix the highlighted fields before uploading the cover.",
      completeBeforeSubmit: "Complete the highlighted required fields before submitting.",
      coverRequired: "A cover image is required for submission.",
    },
    errors: {
      submissionIncomplete: "Complete the required Korean and English fields, dates, and hours before submitting.",
      bilingualIncomplete: "Complete the required Korean and English fields before submitting.",
      addCover: "Add a cover image before submitting.",
      verification: "Gallery verification is required before submission.",
      revision: "This draft changed elsewhere. Reload it and try again.",
      coverMime: "Choose a JPEG, PNG, or WebP image.",
      coverSize: "Choose an image smaller than 10 MB.",
      coverFilename: "Choose an image with a valid filename.",
      coverMissing: "The cover upload did not finish. Try again.",
      coverMimeMismatch: "The uploaded image type did not match the selected file.",
      coverSizeMismatch: "The uploaded image size could not be verified.",
      invalidTicket: "Ticket URL must start with http:// or https://.",
      invalidDate: "Use a valid calendar date in YYYY-MM-DD format.",
      invalidTime: "Use a valid time in 24-hour HH:MM format.",
      tooLong: "One or more fields is too long. Shorten the highlighted content and try again.",
      unsupportedFormat: "One or more fields has an unsupported format.",
      unsupportedField: "The form included an unsupported field. Reload and try again.",
      invalidPatch: "The draft format was invalid. Reload and try again.",
      geocodeAccess: "Address search is not available for this gallery.",
      geocodeRate: "Address search is temporarily limited. Wait a moment and try again.",
      removalRevision: "This exhibition changed elsewhere. Reload the list and try again.",
      removalAccess: "You no longer have permission to remove this exhibition from the list.",
      removal: "Exhibition could not be removed from My exhibitions.",
      addressSearch: "Address search failed.",
      save: "Draft could not be saved.",
      coverUpload: "Cover could not be uploaded.",
      submit: "Exhibition could not be submitted.",
      launchEligibility: "This exhibition is no longer eligible for Launch Kit activation. Reload and check its published status.",
      launchPaymentState: "This exhibition has an unresolved earlier payment attempt. Contact Gallr support before activating its Launch Kit.",
      launchNotActivatable: "This Launch Kit can no longer be activated.",
      launch: "Launch Kit could not be activated.",
      load: "Exhibitions could not be loaded.",
      create: "Draft could not be created.",
    },
    editor: {
      back: "Back to exhibitions",
      title: "Edit exhibition",
      savedSuffix: "Saved",
      requiredNote: "* Required for submission",
      saving: "Saving…",
      save: "Save draft",
      changesRequested: "Changes requested",
      exhibition: "Exhibition",
      visitDetails: "Visit details",
      location: "Location",
      locationHelp: "Search for the venue address and choose a match. The city, region, address, and map coordinates are filled in for you — no need to enter latitude or longitude by hand.",
      findAddress: "Find an address",
      addressPlaceholder: "Road name or building, e.g. 삼청로 12",
      searching: "Searching…",
      searchAddress: "Search address",
      addressMatches: "Address matches",
      useAddress: (address: string) => `Use this address: ${address}`,
      noMatches: "No address matches found. Try a road name or a broader search.",
      clearAddress: "Clear selected address",
      addressRequired: "! Search and choose an address to set the venue location.",
      noAddress: "No address selected yet.",
      cover: "Cover image",
      coverAlt: "Exhibition cover preview",
      noCover: "No cover selected",
      uploading: "Uploading…",
      chooseCover: "Choose cover image",
      coverHelp: "JPEG, PNG, or WebP. Maximum 10 MB.",
      review: "Review",
      inReview: "Your listing is in staff review.",
      viewPublic: "View public page",
      publicPageDelay: "The public page is rebuilt after approval and goes live within a few minutes.",
      qrTitle: "Exhibition QR",
      qrBody: "Colors are sampled from the published poster and darkened for reliable scanning. Keep the printed code at least 32 mm wide, and regenerate it after changing the published title.",
      qrGenerating: "Generating from poster…",
      qrAlt: (name: string) => `QR code for ${name}`,
      qrDownload: "Download exhibition QR",
      qrDownloaded: "Exhibition QR downloaded.",
      qrFallback: "Poster colors could not be read. A scan-safe monochrome version is ready.",
      qrError: "Exhibition QR could not be generated.",
      qrRetry: "Try again",
      publicImpact: "Public impact",
      activatingLaunch: "Activating…",
      activateLaunch: "Activate free Launch Kit",
      launchSoon: "Launch Kit is coming soon. Your published listing is already live.",
      submitting: "Submitting…",
      submit: "Submit for review",
      addBeforeSubmit: "Add these before submitting:",
    },
    dashboard: {
      title: "My exhibitions",
      intro: "Prepare and publish free exhibition listings for your gallery.",
      creating: "Creating…",
      create: "Create exhibition",
      claimPending: "Gallery claim pending",
      claimPendingBody: "You can create drafts while we verify your gallery.",
      loading: "Loading exhibitions…",
      emptyTitle: "Your exhibitions will appear here.",
      emptyBody: "Create a draft when your next exhibition is ready.",
      columnExhibition: "Exhibition",
      columnDates: "Dates",
      columnStatus: "Status",
      columnImpact: "Impact",
      columnUpdated: "Updated",
      untitled: "Untitled exhibition",
      removeAria: (name: string) => `Remove ${name} from My exhibitions`,
      remove: "Remove from My exhibitions",
      removeTitle: "Remove from My exhibitions?",
      removeBody: (status: string) => `This ${status.toLowerCase()} exhibition remains in Gallr's production database. Its review and publication state will not change.`,
      cancel: "Cancel",
      removing: "Removing…",
    },
  },
  launch: {
    errors: {
      load: "Launch Kit could not be loaded.",
      guests: "Guest list could not be loaded.",
      addGuest: "Guest could not be added.",
      checkIn: "Guest could not be checked in.",
      rotate: "RSVP link could not be replaced.",
      copy: "RSVP link could not be copied.",
      qr: "RSVP QR code could not be downloaded.",
      promotionLoad: "Local promotion could not be loaded.",
      promotion: "Local promotion request could not be submitted.",
    },
    promotionStatuses: {
      submitted: "Submitted for review",
      approved: "Scheduled",
      active: "Active now",
      rejected: "Changes required",
      ended: "Ended",
    },
    guest: "guest",
    guests: "guests",
    checkedIn: "Checked in",
    going: "Going",
    checkingIn: "Checking in…",
    checkIn: "Check in",
    exit: "Exit",
    checkInTitle: "Check in guests",
    checkedInCount: (checked: string, total: string) => `${checked} of ${total} checked in`,
    searchNameEmail: "Search name or email",
    loading: "Loading…",
    loadMore: "Load more guests",
    loadingKits: "Loading Launch Kits…",
    emptyTitle: "No Launch Kits yet.",
    emptyBody: "Launch a published exhibition from its editor.",
    activationUnavailable: "Activation unavailable",
    activationUnavailableBody: "This Launch Kit cannot currently be used.",
    kitSelector: "Launch Kit exhibition",
    openingNight: "Opening night",
    viewRsvp: "View RSVP page",
    copying: "Copying…",
    copyRsvp: "Copy RSVP link",
    preparingQr: "Preparing QR…",
    downloadQr: "Download QR code",
    linkCopied: "RSVP link copied.",
    qrDownloaded: "RSVP QR code downloaded.",
    replacing: "Replacing…",
    replaceRsvp: "Replace RSVP link",
    replaceConfirm: "Replace this RSVP link? The current link will stop working immediately.",
    checkInMode: "Check-in mode",
    summaryGoing: "Going",
    summaryGuests: "Guests",
    summaryCheckedIn: "Checked in",
    promotionTitle: "Promoted near you",
    promotionBody: "Paid placement for this exhibition, shown only to relevant local visitors and at most once per day.",
    promotionReview: "Gallr staff reviews every request. Editorial Featured remains separate.",
    submitting: "Submitting…",
    requestAgain: "Request again",
    requestPromotion: "Request local promotion",
    guestList: "Guest list",
    addGuest: "Add guest",
    name: "Name",
    email: "Email",
    party: "Party",
    saveGuest: "Save guest",
    searchGuests: "Search guests",
    guestStatusFilter: "Guest status filter",
    all: "All",
    columnGuest: "Guest",
    columnParty: "Party",
    columnStatus: "Status",
    columnArrival: "Arrival",
  },
} as const;

type CatalogShape<T> = {
  [Key in keyof T]: T[Key] extends (...args: infer Args) => string
    ? (...args: Args) => string
    : T[Key] extends string
      ? string
      : CatalogShape<T[Key]>;
};

export type PortalMessages = CatalogShape<typeof englishMessages>;

const koreanMessages = {
  language: { group: "언어", korean: "한국어", english: "EN" },
  common: {
    brand: "gallr gallery",
    signOut: "로그아웃",
    galleryWorkspace: "갤러리 워크스페이스",
    configurationRequired: "설정이 필요합니다",
    configurationMissing: "갤러리 워크스페이스 설정이 없습니다.",
    loadingWorkspace: "갤러리 워크스페이스를 불러오는 중…",
    workspaceUnavailable: "워크스페이스를 사용할 수 없습니다",
  },
  navigation: { setup: "갤러리 설정", exhibitions: "전시", galleryInfo: "갤러리 정보", launchKit: "런치 키트" },
  auth: {
    heading: "gallr와 함께 전시를 게시하세요",
    checkEmail: "이메일을 확인하세요",
    sentMessage: (email: string) => `${email}로 보낸 안전한 로그인 메일을 확인하세요.`,
    differentEmail: "다른 이메일 사용",
    intro: "갤러리의 무료 전시 목록을 관리하세요.",
    email: "이메일",
    sending: "전송 중…",
    sendCode: "로그인 코드 보내기",
    or: "또는",
    openingGoogle: "Google 여는 중…",
    continueGoogle: "Google로 계속하기",
  },
  onboarding: {
    title: "갤러리를 설정하세요",
    intro: "새 갤러리 워크스페이스를 만들기 전에 먼저 검색하세요.",
    galleryName: "갤러리명",
    searchPlaceholder: "한국어 또는 영어 이름 검색",
    search: "검색",
    searchHelp: "이미 등록된 갤러리인지 이름으로 검색해 보세요.",
    noMatch: "일치하는 갤러리를 찾지 못했습니다.",
    alreadyClaimed: "이미 관리 권한 확인됨",
    requestAccess: "접근 권한 요청",
    cantFind: "갤러리를 찾을 수 없나요?",
    createNew: "새 갤러리 만들기",
    backToSearch: "검색으로 돌아가기",
    requestAccessTo: (name: string) => `${name} 접근 권한 요청`,
    shareReference: "스태프가 관리 권한을 확인할 수 있도록 공식 자료를 하나 이상 공유하세요.",
    officialWebsite: "공식 웹사이트",
    officialSocial: "공식 소셜 프로필",
    claimNote: "관리 권한 요청 메모",
    submitClaim: "관리 권한 요청 제출",
    createTitle: "새 갤러리 만들기",
    nameKo: "갤러리명 (한국어)",
    nameEn: "갤러리명 (영어)",
    createGallery: "갤러리 만들기",
    suspendedTitle: "갤러리 접근이 일시 중지되었습니다",
    suspendedBody: "이 워크스페이스를 사용할 수 없습니다. Gallr 지원팀에 접근 검토를 요청하세요.",
    errors: {
      sendEmail: "로그인 이메일을 보내지 못했습니다.",
      google: "Google 로그인을 시작하지 못했습니다.",
      signupDisabled: "계정 만들기를 일시적으로 사용할 수 없습니다. 잠시 후 다시 시도해주세요.",
      oauthCallback: "Google 로그인을 완료하지 못했습니다. 다시 시도해주세요.",
      search: "갤러리 검색에 실패했습니다.",
      evidence: "공식 웹사이트, 소셜 프로필 또는 관리 권한 요청 메모를 추가하세요.",
      claim: "갤러리 관리 권한 요청을 제출하지 못했습니다.",
      create: "갤러리 워크스페이스를 만들지 못했습니다.",
      access: "갤러리 접근 권한을 확인하지 못했습니다.",
      session: "세션을 확인하지 못했습니다.",
      signOut: "로그아웃하지 못했습니다.",
    },
  },
  galleryInfo: {
    title: "갤러리 정보",
    intro: "새 전시 초안을 만들 때 한 번 복사되는 기본 정보입니다.",
    saved: "갤러리 정보 · 저장됨",
    galleryVenue: "갤러리 및 장소",
    nameKo: "갤러리명 (한국어)",
    nameEn: "갤러리명 (영어)",
    venueKo: "장소명 (한국어)",
    venueEn: "장소명 (영어)",
    addressMap: "주소 및 지도",
    addressHelp: "검색 결과를 검토한 뒤 하나를 선택해 공식 주소와 좌표를 설정하세요.",
    findAddress: "주소 찾기",
    searching: "검색 중…",
    searchAddress: "주소 검색",
    addressMatches: "주소 검색 결과",
    useAddress: (address: string) => `이 주소 사용: ${address}`,
    noMatches: "일치하는 주소가 없습니다. 도로명이나 더 넓은 검색어를 사용해 보세요.",
    cityKo: "도시 (한국어)",
    cityEn: "도시 (영어)",
    regionKo: "지역 (한국어)",
    regionEn: "지역 (영어)",
    addressKo: "주소 (한국어)",
    addressEn: "주소 (영어)",
    latitude: "위도",
    longitude: "경도",
    visitDetails: "기본 관람 정보",
    hours: "기본 운영 시간",
    contact: "연락처",
    saving: "저장 중…",
    save: "갤러리 정보 저장",
    loading: "갤러리 정보 불러오는 중…",
    errors: {
      revision: "다른 곳에서 갤러리 정보가 변경되었습니다. 새로고침 후 다시 시도하세요.",
      access: "더 이상 갤러리 정보에 접근할 수 없습니다.",
      geocodeAccess: "이 갤러리에서는 주소 검색을 사용할 수 없습니다.",
      authentication: "계속하려면 다시 로그인하세요.",
      required: "저장하기 전에 모든 한국어·영어 갤러리 및 위치 필드를 입력하세요.",
      location: "저장하기 전에 유효한 주소를 선택하세요.",
      unsupportedField: "지원하지 않는 필드가 포함되었습니다. 새로고침 후 다시 시도하세요.",
      unsupportedFormat: "하나 이상의 필드 형식이 지원되지 않습니다.",
      invalidForm: "갤러리 정보 양식이 올바르지 않습니다. 새로고침 후 다시 시도하세요.",
      rateLimited: "주소 검색이 일시적으로 제한되었습니다. 잠시 후 다시 시도하세요.",
      characterLimit: "하나 이상의 필드가 글자 수 제한을 초과했습니다.",
      load: "갤러리 정보를 불러오지 못했습니다.",
      addressSearch: "주소 검색에 실패했습니다.",
      save: "갤러리 정보를 저장하지 못했습니다.",
    },
  },
  exhibitions: {
    statuses: { draft: "초안", submitted: "검토 중", needsChanges: "수정 필요", published: "게시됨", archived: "보관됨" },
    impact: { last30Days: "최근 30일", allTime: "전체 기간", caveat: "순 방문자가 아닌 공개 페이지 조회수입니다." },
    fields: {
      nameKo: "전시명 (한국어)", nameEn: "전시명 (영어)", venueNameKo: "장소명 (한국어)", venueNameEn: "장소명 (영어)",
      cityKo: "도시 (한국어)", cityEn: "도시 (영어)", regionKo: "지역 (한국어)", regionEn: "지역 (영어)",
      addressKo: "주소 (한국어)", addressEn: "주소 (영어)", latitude: "위도", longitude: "경도",
      openingDate: "개막일", closingDate: "종료일", descriptionKo: "설명 (한국어)", descriptionEn: "설명 (영어)",
      hours: "운영 시간", contact: "연락처", receptionDate: "오프닝 리셉션 날짜", receptionStartTime: "오프닝 리셉션 시간", ticketUrl: "티켓 URL",
    },
    art: {
      heading: "작가 및 작품 설명어",
      reviewHelp: "이 제안은 Gallr 스태프가 검토하고 전시를 게시할 때까지 비공개로 유지됩니다.",
      unsupported: "이 서버에서는 작품 메타데이터를 사용할 수 없습니다. 전시는 계속 편집할 수 있습니다.",
      artists: "작가",
      orderedCredits: "정렬된 작가 크레딧",
      noArtists: "추가된 작가가 없습니다.",
      unresolved: "제안됨",
      search: "등록 작가 검색",
      searching: "검색 중…",
      noMatches: "일치하는 등록 작가가 없습니다.",
      searchFailed: "! 작가 검색에 실패했습니다.",
      useArtist: (name: string) => `${name} 추가`,
      moveUp: (name: string) => `${name} 위로 이동`,
      moveDown: (name: string) => `${name} 아래로 이동`,
      remove: (name: string) => `${name} 삭제`,
      suggestionHeading: "새 작가 제안",
      suggestionKo: "제안 작가명 (한국어)",
      suggestionEn: "제안 작가명 (영어)",
      addSuggestion: "작가 제안 추가",
      terms: "관리형 설명어",
      medium: "매체",
      style: "스타일 / 사조",
      theme: "주제 / 소재",
      mood: "분위기 / 톤",
      loadFailed: "! 관리형 설명어를 불러오지 못했습니다.",
    },
    validation: {
      tooLong: (label: string, limit: string) => `${label}은(는) ${limit}자 이하여야 합니다.`,
      coordinatePair: "위도와 경도는 함께 입력되어야 합니다.",
      coordinateRange: "좌표가 유효한 범위를 벗어났습니다.",
      invalidDate: (label: string) => `${label}에 유효한 날짜를 입력하세요.`,
      invalidTime: "오프닝 리셉션 시간은 24시간제 HH:MM 형식이어야 합니다.",
      invalidTicket: "티켓 URL은 http:// 또는 https://로 시작해야 합니다.",
      required: "제출 필수 항목입니다.",
      requiredSummary: (label: string) => `${label}은(는) 제출 필수 항목입니다.`,
      closingBeforeOpening: "종료일은 개막일과 같거나 이후여야 합니다.",
      locationRequirement: "위치 (주소 검색 후 선택)",
      coverRequirement: "커버 이미지",
      fixBeforeSave: "저장하기 전에 표시된 필드를 수정하세요.",
      fixBeforeCover: "커버를 업로드하기 전에 표시된 필드를 수정하세요.",
      completeBeforeSubmit: "제출하기 전에 표시된 필수 항목을 입력하세요.",
      coverRequired: "제출하려면 커버 이미지가 필요합니다.",
    },
    errors: {
      submissionIncomplete: "제출하기 전에 필수 한국어·영어 필드, 날짜, 운영 시간을 입력하세요.",
      bilingualIncomplete: "제출하기 전에 필수 한국어·영어 필드를 입력하세요.",
      addCover: "제출하기 전에 커버 이미지를 추가하세요.",
      verification: "제출하려면 갤러리 인증이 필요합니다.",
      revision: "다른 곳에서 이 초안이 변경되었습니다. 새로고침 후 다시 시도하세요.",
      coverMime: "JPEG, PNG 또는 WebP 이미지를 선택하세요.", coverSize: "10MB보다 작은 이미지를 선택하세요.", coverFilename: "유효한 파일명의 이미지를 선택하세요.",
      coverMissing: "커버 업로드가 완료되지 않았습니다. 다시 시도하세요.", coverMimeMismatch: "업로드한 이미지 형식이 선택한 파일과 일치하지 않습니다.", coverSizeMismatch: "업로드한 이미지 크기를 확인하지 못했습니다.",
      invalidTicket: "티켓 URL은 http:// 또는 https://로 시작해야 합니다.", invalidDate: "YYYY-MM-DD 형식의 유효한 날짜를 사용하세요.", invalidTime: "24시간제 HH:MM 형식의 유효한 시간을 사용하세요.",
      tooLong: "하나 이상의 필드가 너무 깁니다. 표시된 내용을 줄인 뒤 다시 시도하세요.", unsupportedFormat: "하나 이상의 필드 형식이 지원되지 않습니다.",
      unsupportedField: "지원하지 않는 필드가 포함되었습니다. 새로고침 후 다시 시도하세요.", invalidPatch: "초안 형식이 올바르지 않습니다. 새로고침 후 다시 시도하세요.",
      geocodeAccess: "이 갤러리에서는 주소 검색을 사용할 수 없습니다.", geocodeRate: "주소 검색이 일시적으로 제한되었습니다. 잠시 후 다시 시도하세요.",
      removalRevision: "다른 곳에서 이 전시가 변경되었습니다. 목록을 새로고침 후 다시 시도하세요.", removalAccess: "더 이상 이 전시를 내 전시에서 제외할 권한이 없습니다.", removal: "내 전시에서 전시를 제외하지 못했습니다.",
      addressSearch: "주소 검색에 실패했습니다.", save: "초안을 저장하지 못했습니다.", coverUpload: "커버를 업로드하지 못했습니다.", submit: "전시를 제출하지 못했습니다.",
      launchEligibility: "이 전시는 더 이상 런치 키트를 활성화할 수 없습니다. 게시 상태를 다시 확인해 주세요.",
      launchPaymentState: "이 전시에 해결되지 않은 이전 결제 시도가 있습니다. 런치 키트를 활성화하기 전에 Gallr 지원팀에 문의해 주세요.",
      launchNotActivatable: "이 런치 키트는 더 이상 활성화할 수 없습니다.",
      launch: "런치 키트를 활성화하지 못했습니다.", load: "전시를 불러오지 못했습니다.", create: "초안을 만들지 못했습니다.",
    },
    editor: {
      back: "전시 목록으로 돌아가기", title: "전시 편집", savedSuffix: "저장됨", requiredNote: "* 제출 필수 항목", saving: "저장 중…", save: "초안 저장",
      changesRequested: "수정 요청", exhibition: "전시", visitDetails: "관람 정보", location: "위치",
      locationHelp: "장소 주소를 검색하고 결과를 선택하세요. 도시, 지역, 주소, 지도 좌표가 자동으로 입력되므로 위도와 경도를 직접 입력할 필요가 없습니다.",
      findAddress: "주소 찾기", addressPlaceholder: "도로명 또는 건물명 (예: 삼청로 12)", searching: "검색 중…", searchAddress: "주소 검색", addressMatches: "주소 검색 결과",
      useAddress: (address: string) => `이 주소 사용: ${address}`, noMatches: "일치하는 주소가 없습니다. 도로명이나 더 넓은 검색어를 사용해 보세요.", clearAddress: "선택한 주소 지우기",
      addressRequired: "! 장소 위치를 설정하려면 주소를 검색하고 선택하세요.", noAddress: "아직 선택한 주소가 없습니다.",
      cover: "커버 이미지", coverAlt: "전시 커버 미리보기", noCover: "선택한 커버 없음", uploading: "업로드 중…", chooseCover: "커버 이미지 선택", coverHelp: "JPEG, PNG 또는 WebP. 최대 10MB.",
      review: "검토", inReview: "전시 목록을 스태프가 검토하고 있습니다.", viewPublic: "공개 페이지 보기", publicPageDelay: "공개 페이지는 승인 후 다시 생성되며 몇 분 안에 공개됩니다.",
      qrTitle: "전시 QR", qrBody: "게시된 포스터에서 색을 추출한 뒤 안정적인 인식을 위해 어둡게 조정합니다. 인쇄 시 QR 너비를 32mm 이상으로 유지하고 게시된 제목을 변경한 뒤에는 다시 생성하세요.", qrGenerating: "포스터에서 생성 중…", qrAlt: (name: string) => `${name} QR 코드`,
      qrDownload: "전시 QR 다운로드", qrDownloaded: "전시 QR을 다운로드했습니다.", qrFallback: "포스터 색을 읽지 못했습니다. 인식하기 쉬운 흑백 버전을 준비했습니다.", qrError: "전시 QR을 생성하지 못했습니다.", qrRetry: "다시 시도", publicImpact: "공개 페이지 조회",
      activatingLaunch: "활성화 중…", activateLaunch: "무료 런치 키트 활성화", launchSoon: "런치 키트는 곧 제공됩니다. 게시된 전시 목록은 이미 공개되었습니다.",
      submitting: "제출 중…", submit: "검토 요청 제출", addBeforeSubmit: "제출하기 전에 다음 항목을 추가하세요:",
    },
    dashboard: {
      title: "내 전시", intro: "갤러리의 무료 전시 목록을 준비하고 게시하세요.", creating: "만드는 중…", create: "전시 만들기",
      claimPending: "갤러리 관리 권한 확인 중", claimPendingBody: "갤러리를 확인하는 동안 초안을 만들 수 있습니다.", loading: "전시 불러오는 중…",
      emptyTitle: "전시가 여기에 표시됩니다.", emptyBody: "다음 전시가 준비되면 초안을 만드세요.",
      columnExhibition: "전시", columnDates: "기간", columnStatus: "상태", columnImpact: "조회수", columnUpdated: "업데이트",
      untitled: "제목 없는 전시", removeAria: (name: string) => `내 전시에서 ${name} 제외`, remove: "내 전시에서 제외", removeTitle: "내 전시에서 제외할까요?",
      removeBody: (status: string) => `${status} 전시는 Gallr 운영 데이터베이스에 남아 있습니다. 검토 및 게시 상태는 변경되지 않습니다.`,
      cancel: "취소", removing: "제외 중…",
    },
  },
  launch: {
    errors: {
      load: "런치 키트를 불러오지 못했습니다.",
      guests: "게스트 명단을 불러오지 못했습니다.",
      addGuest: "게스트를 추가하지 못했습니다.",
      checkIn: "게스트 입장을 확인하지 못했습니다.",
      rotate: "RSVP 링크를 교체하지 못했습니다.",
      copy: "RSVP 링크를 복사하지 못했습니다.",
      qr: "RSVP QR 코드를 다운로드하지 못했습니다.",
      promotionLoad: "지역 프로모션을 불러오지 못했습니다.",
      promotion: "지역 프로모션 요청을 제출하지 못했습니다.",
    },
    promotionStatuses: { submitted: "검토 요청됨", approved: "예약됨", active: "진행 중", rejected: "수정 필요", ended: "종료됨" },
    guest: "명", guests: "명", checkedIn: "입장 확인", going: "참석 예정", checkingIn: "입장 확인 중…", checkIn: "입장 확인", exit: "나가기",
    checkInTitle: "게스트 입장 확인", checkedInCount: (checked: string, total: string) => `${total}명 중 ${checked}명 입장 확인`, searchNameEmail: "이름 또는 이메일 검색",
    loading: "불러오는 중…", loadMore: "게스트 더 보기", loadingKits: "런치 키트 불러오는 중…", emptyTitle: "아직 런치 키트가 없습니다.", emptyBody: "게시된 전시의 편집 화면에서 런치하세요.",
    activationUnavailable: "활성화할 수 없음", activationUnavailableBody: "현재 이 런치 키트를 사용할 수 없습니다.", kitSelector: "런치 키트 전시", openingNight: "오프닝", viewRsvp: "RSVP 페이지 보기",
    copying: "복사 중…", copyRsvp: "RSVP 링크 복사", preparingQr: "QR 준비 중…", downloadQr: "QR 코드 다운로드", linkCopied: "RSVP 링크를 복사했습니다.", qrDownloaded: "RSVP QR 코드를 다운로드했습니다.",
    replacing: "교체 중…", replaceRsvp: "RSVP 링크 교체", replaceConfirm: "이 RSVP 링크를 교체할까요? 현재 링크는 즉시 작동을 멈춥니다.", checkInMode: "입장 확인 모드",
    summaryGoing: "참석 예정", summaryGuests: "게스트", summaryCheckedIn: "입장 확인", promotionTitle: "내 주변 프로모션",
    promotionBody: "관련 지역 방문자에게 하루 최대 한 번만 노출되는 유료 전시 배치입니다.", promotionReview: "모든 요청은 Gallr 스태프가 검토하며 에디토리얼 추천과 분리됩니다.",
    submitting: "제출 중…", requestAgain: "다시 요청", requestPromotion: "지역 프로모션 요청", guestList: "게스트 명단", addGuest: "게스트 추가",
    name: "이름", email: "이메일", party: "참석 인원", saveGuest: "게스트 저장", searchGuests: "게스트 검색", guestStatusFilter: "게스트 상태 필터", all: "전체",
    columnGuest: "게스트", columnParty: "참석 인원", columnStatus: "상태", columnArrival: "도착 시간",
  },
} satisfies PortalMessages;

const messagesByLocale: Record<PortalLocale, PortalMessages> = {
  en: englishMessages,
  ko: koreanMessages,
};

export function resolvePortalLocale(
  savedLocale: string | null | undefined,
  browserLanguage: string | null | undefined,
): PortalLocale {
  if (savedLocale === "ko" || savedLocale === "en") return savedLocale;
  return browserLanguage?.toLowerCase().startsWith("ko") ? "ko" : "en";
}

export function localeTag(locale: PortalLocale): "ko-KR" | "en-US" {
  return locale === "ko" ? "ko-KR" : "en-US";
}

const dateOnlyFormatters: Record<PortalLocale, Intl.DateTimeFormat> = {
  ko: new Intl.DateTimeFormat("ko-KR", {
    year: "numeric", month: "short", day: "numeric", timeZone: "UTC",
  }),
  en: new Intl.DateTimeFormat("en-US", {
    year: "numeric", month: "short", day: "numeric", timeZone: "UTC",
  }),
};
const timestampDateFormatters: Record<PortalLocale, Intl.DateTimeFormat> = {
  ko: new Intl.DateTimeFormat("ko-KR", {
    year: "numeric", month: "short", day: "numeric", timeZone: "Asia/Seoul",
  }),
  en: new Intl.DateTimeFormat("en-US", {
    year: "numeric", month: "short", day: "numeric", timeZone: "Asia/Seoul",
  }),
};
const timeFormatters: Record<PortalLocale, Intl.DateTimeFormat> = {
  ko: new Intl.DateTimeFormat("ko-KR", {
    hour: "numeric", minute: "2-digit", hour12: true, timeZone: "Asia/Seoul",
  }),
  en: new Intl.DateTimeFormat("en-US", {
    hour: "numeric", minute: "2-digit", timeZone: "Asia/Seoul",
  }),
};
const numberFormatters: Record<PortalLocale, Intl.NumberFormat> = {
  ko: new Intl.NumberFormat("ko-KR"),
  en: new Intl.NumberFormat("en-US"),
};

export function localizeBilingual(
  korean: string,
  english: string,
  locale: PortalLocale,
): string {
  const preferred = locale === "ko" ? korean.trim() : english.trim();
  const fallback = locale === "ko" ? english.trim() : korean.trim();
  return preferred || fallback;
}

export function alternateBilingual(
  korean: string,
  english: string,
  locale: PortalLocale,
): string {
  const primary = localizeBilingual(korean, english, locale);
  const alternate = (locale === "ko" ? english : korean).trim();
  return alternate && alternate !== primary ? alternate : "";
}

function dateOnly(value: string): Date | null {
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value);
  if (!match) return null;
  const date = new Date(Date.UTC(Number(match[1]), Number(match[2]) - 1, Number(match[3])));
  return Number.isNaN(date.getTime()) ? null : date;
}

export function formatDateOnly(value: string, locale: PortalLocale): string {
  const date = dateOnly(value);
  if (!date) return value;
  return dateOnlyFormatters[locale].format(date);
}

export function formatTimestampDate(value: string, locale: PortalLocale): string {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return timestampDateFormatters[locale].format(date);
}

export function formatTime(value: string, locale: PortalLocale): string {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  if (locale === "ko") {
    const parts = timeFormatters.ko.formatToParts(date);
    const part = (type: Intl.DateTimeFormatPartTypes) =>
      parts.find((item) => item.type === type)?.value ?? "";
    const dayPeriod = part("dayPeriod").toLocaleUpperCase();
    const marker = dayPeriod === "AM" || dayPeriod === "오전" ? "오전" : "오후";
    return `${marker} ${part("hour")}:${part("minute")}`;
  }
  return timeFormatters[locale].format(date);
}

export function formatNumber(value: number, locale: PortalLocale): string {
  return numberFormatters[locale].format(value);
}

function savedLocale(): string | null {
  try {
    return globalThis.localStorage?.getItem(GALLERY_LOCALE_STORAGE_KEY) ?? null;
  } catch {
    return null;
  }
}

function browserLanguage(): string | null {
  if (typeof navigator === "undefined") return null;
  return navigator.languages?.[0] ?? navigator.language ?? null;
}

function persistLocale(locale: PortalLocale) {
  try {
    globalThis.localStorage?.setItem(GALLERY_LOCALE_STORAGE_KEY, locale);
  } catch {
    // Storage can be blocked. The in-memory selection remains usable.
  }
}

interface LocaleContextValue {
  locale: PortalLocale;
  messages: PortalMessages;
  setLocale: (locale: PortalLocale) => void;
}

const defaultLocaleContext: LocaleContextValue = {
  locale: "en",
  messages: englishMessages,
  setLocale: () => undefined,
};

const LocaleContext = createContext<LocaleContextValue>(defaultLocaleContext);

export function LocaleProvider({
  children,
  initialLocale,
}: {
  children: React.ReactNode;
  initialLocale?: PortalLocale;
}) {
  const [locale, setLocaleState] = useState<PortalLocale>(() => (
    initialLocale ?? resolvePortalLocale(savedLocale(), browserLanguage())
  ));

  const setLocale = useCallback((nextLocale: PortalLocale) => {
    persistLocale(nextLocale);
    setLocaleState(nextLocale);
  }, []);

  useEffect(() => {
    document.documentElement.lang = locale;
    persistLocale(locale);
  }, [locale]);

  const value = useMemo<LocaleContextValue>(() => ({
    locale,
    messages: messagesByLocale[locale],
    setLocale,
  }), [locale, setLocale]);

  return <LocaleContext.Provider value={value}>{children}</LocaleContext.Provider>;
}

export function useLocale(): LocaleContextValue {
  return useContext(LocaleContext);
}

export function LocaleToggle({ className = "" }: { className?: string }) {
  const { locale, messages, setLocale } = useLocale();
  return (
    <div className={`locale-toggle ${className}`.trim()} role="group" aria-label={messages.language.group}>
      <button type="button" aria-pressed={locale === "ko"} onClick={() => setLocale("ko")}>
        {messages.language.korean}
      </button>
      <button type="button" aria-pressed={locale === "en"} onClick={() => setLocale("en")}>
        {messages.language.english}
      </button>
    </div>
  );
}
