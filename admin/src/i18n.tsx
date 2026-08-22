import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from "react";
import { editorEnglishMessages, editorKoreanMessages } from "./editor-i18n-messages";
import { staffEnglishMessages, staffKoreanMessages } from "./staff-i18n-messages";

export type PortalLocale = "ko" | "en";

export const ADMIN_LOCALE_STORAGE_KEY = "gallr-admin:locale:v1";

const englishMessages = {
  "language.group": "Interface language",
  "language.switchKo": "Korean",
  "language.switchEn": "English",
  "navigation.primary": "Primary navigation",
  "navigation.exhibitions": "Exhibitions",
  "navigation.submissions": "Submissions",
  "navigation.galleryClaims": "Gallery claims",
  "navigation.promotions": "Promotions",
  "navigation.editors": "Editors",
  "actions.signOut": "Sign out",
  "actions.resolveBeforeSignOut": "Resolve or discard the current exhibition changes before signing out.",
  "auth.accessVerifyFailed": "Access could not be verified. Sign out and try again.",
  "auth.noEditorAccess": "This account does not have gallr editor access.",
  "auth.noAdminAccess": "This account does not have gallr admin access.",
  "auth.accountInactive": "This portal account is inactive.",
  "auth.resetSent": "Check your email for a reset link.",
  "auth.resetRateLimited": "Too many reset emails were requested. Wait a few minutes and try again.",
  "auth.resetFailed": "The reset link could not be sent.",
  "auth.passwordBreached": "Choose a unique password that has not appeared in a known data breach.",
  "auth.passwordTooShort": "This password is too short. Use at least 8 characters.",
  "auth.passwordCharacters": "This password does not meet the configured character requirements.",
  "auth.passwordWeak": "This password was rejected as weak. Use a longer, unique password.",
  "auth.passwordSame": "Choose a password different from your current password.",
  "auth.resetExpired": "This reset session has expired. Return to sign-in and request a new link.",
  "auth.passwordUpdateFailed": "Password could not be updated. Try again.",
  "auth.invalidLink": "The invitation or reset link is invalid or has expired.",
  "auth.emailPasswordIncorrect": "Email or password is incorrect.",
  "auth.enterEmailForReset": "Enter your email before requesting a reset link.",
  "auth.googleFailed": "Google sign-in could not be started.",
  "auth.passwordMinimum": "Password must be at least 8 characters.",
  "auth.passwordMismatch": "Passwords do not match.",
  "auth.checkingSession": "Checking session…",
  "auth.openingPortal": "Opening the correct portal…",
  "auth.setNewPassword": "Set a new password",
  "auth.requirementsIntro": "Your new password must meet every requirement.",
  "auth.requirementsLabel": "Password requirements",
  "auth.requirementLength": "At least 8 characters.",
  "auth.requirementDifferent": "Different from your current password.",
  "auth.requirementBreached": "Not found in known password breaches.",
  "auth.requirementMatch": "Both password fields must match.",
  "auth.requirementOptional": "Uppercase letters, numbers, and symbols are optional.",
  "auth.newPassword": "New password",
  "auth.confirmPassword": "Confirm password",
  "auth.updating": "Updating…",
  "auth.updatePassword": "Update password",
  "auth.accessUnavailable": "Access unavailable",
  "auth.editorCuration": "Editor curation",
  "auth.contentAdmin": "Content admin",
  "auth.email": "Email",
  "auth.password": "Password",
  "auth.signingIn": "Signing in…",
  "auth.signIn": "Sign in",
  "auth.forgotPassword": "Forgot password?",
  "auth.or": "or",
  "auth.continueGoogle": "Continue with Google",
  "fields.unsavedDraft": "Unsaved draft field",
  ...editorEnglishMessages,
  ...staffEnglishMessages,
} as const;

export type MessageKey = keyof typeof englishMessages;

const koreanMessages: Record<MessageKey, string> = {
  "language.group": "언어",
  "language.switchKo": "한국어",
  "language.switchEn": "영어",
  "navigation.primary": "주요 메뉴",
  "navigation.exhibitions": "전시",
  "navigation.submissions": "제출 검토",
  "navigation.galleryClaims": "갤러리 권한 요청",
  "navigation.promotions": "프로모션",
  "navigation.editors": "에디터",
  "actions.signOut": "로그아웃",
  "actions.resolveBeforeSignOut": "로그아웃하기 전에 현재 전시 변경 사항을 해결하거나 취소하세요.",
  "auth.accessVerifyFailed": "접근 권한을 확인하지 못했습니다. 로그아웃한 뒤 다시 시도하세요.",
  "auth.noEditorAccess": "이 계정에는 gallr 에디터 접근 권한이 없습니다.",
  "auth.noAdminAccess": "이 계정에는 gallr 관리자 접근 권한이 없습니다.",
  "auth.accountInactive": "이 포털 계정은 비활성 상태입니다.",
  "auth.resetSent": "이메일로 전송된 재설정 링크를 확인하세요.",
  "auth.resetRateLimited": "재설정 이메일 요청이 너무 많습니다. 몇 분 뒤 다시 시도하세요.",
  "auth.resetFailed": "재설정 링크를 보내지 못했습니다.",
  "auth.passwordBreached": "알려진 유출 목록에 없는 고유한 비밀번호를 선택하세요.",
  "auth.passwordTooShort": "비밀번호가 너무 짧습니다. 8자 이상 입력하세요.",
  "auth.passwordCharacters": "이 비밀번호는 설정된 문자 요구 사항을 충족하지 않습니다.",
  "auth.passwordWeak": "안전하지 않은 비밀번호입니다. 더 길고 고유한 비밀번호를 사용하세요.",
  "auth.passwordSame": "현재 비밀번호와 다른 비밀번호를 선택하세요.",
  "auth.resetExpired": "비밀번호 재설정 세션이 만료되었습니다. 로그인 화면에서 새 링크를 요청하세요.",
  "auth.passwordUpdateFailed": "비밀번호를 변경하지 못했습니다. 다시 시도하세요.",
  "auth.invalidLink": "초대 또는 재설정 링크가 유효하지 않거나 만료되었습니다.",
  "auth.emailPasswordIncorrect": "이메일 또는 비밀번호가 올바르지 않습니다.",
  "auth.enterEmailForReset": "재설정 링크를 요청하기 전에 이메일을 입력하세요.",
  "auth.googleFailed": "Google 로그인을 시작하지 못했습니다.",
  "auth.passwordMinimum": "비밀번호는 8자 이상이어야 합니다.",
  "auth.passwordMismatch": "비밀번호가 일치하지 않습니다.",
  "auth.checkingSession": "세션을 확인하는 중…",
  "auth.openingPortal": "올바른 포털을 여는 중…",
  "auth.setNewPassword": "새 비밀번호 설정",
  "auth.requirementsIntro": "새 비밀번호는 모든 요구 사항을 충족해야 합니다.",
  "auth.requirementsLabel": "비밀번호 요구 사항",
  "auth.requirementLength": "8자 이상",
  "auth.requirementDifferent": "현재 비밀번호와 다름",
  "auth.requirementBreached": "알려진 비밀번호 유출 목록에 없음",
  "auth.requirementMatch": "두 비밀번호 입력값이 일치함",
  "auth.requirementOptional": "대문자, 숫자, 특수문자는 선택 사항입니다.",
  "auth.newPassword": "새 비밀번호",
  "auth.confirmPassword": "비밀번호 확인",
  "auth.updating": "변경 중…",
  "auth.updatePassword": "비밀번호 변경",
  "auth.accessUnavailable": "접근할 수 없음",
  "auth.editorCuration": "에디터 큐레이션",
  "auth.contentAdmin": "콘텐츠 관리",
  "auth.email": "이메일",
  "auth.password": "비밀번호",
  "auth.signingIn": "로그인 중…",
  "auth.signIn": "로그인",
  "auth.forgotPassword": "비밀번호를 잊으셨나요?",
  "auth.or": "또는",
  "auth.continueGoogle": "Google로 계속하기",
  "fields.unsavedDraft": "저장되지 않은 초안 필드",
  ...editorKoreanMessages,
  ...staffKoreanMessages,
};

export type MessageParameters = Record<string, string | number>;

export type UiMessage = {
  kind: "interface";
  key: MessageKey;
  parameters?: MessageParameters;
};

type Translate = (key: MessageKey, parameters?: MessageParameters) => string;

export function interfaceMessage(
  key: MessageKey,
  parameters?: MessageParameters,
): UiMessage {
  return { kind: "interface", key, parameters };
}

export function uiErrorMessage(
  _error: unknown,
  fallbackKey: MessageKey,
  parameters?: MessageParameters,
): UiMessage {
  return interfaceMessage(fallbackKey, parameters);
}

export function uiMessageText(message: UiMessage, t: Translate): string {
  return t(message.key, message.parameters);
}

function interpolate(template: string, parameters?: MessageParameters): string {
  if (!parameters) return template;
  return template.replace(/\{([a-zA-Z0-9_]+)\}/gu, (match, key: string) =>
    Object.prototype.hasOwnProperty.call(parameters, key)
      ? String(parameters[key])
      : match
  );
}

export function translateMessage(
  locale: PortalLocale,
  key: MessageKey,
  parameters?: MessageParameters,
): string {
  return interpolate((locale === "ko" ? koreanMessages : englishMessages)[key], parameters);
}

export function resolveLocale(
  savedLocale: unknown,
  browserLanguages: readonly string[],
): PortalLocale {
  if (savedLocale === "ko" || savedLocale === "en") return savedLocale;
  return browserLanguages[0]?.toLocaleLowerCase().startsWith("ko")
    ? "ko"
    : "en";
}

export interface LocaleStorage {
  getItem(key: string): string | null;
  setItem(key: string, value: string): void;
}

function browserStorage(): LocaleStorage | null {
  try {
    return window.localStorage ?? null;
  } catch {
    return null;
  }
}

export function localizedText(
  locale: PortalLocale,
  korean: string | null | undefined,
  english: string | null | undefined,
  fallback = "",
): string {
  const preferred = locale === "ko" ? korean : english;
  const counterpart = locale === "ko" ? english : korean;
  return preferred?.trim() || counterpart?.trim() || fallback;
}

export function alternateLocalizedText(
  locale: PortalLocale,
  korean: string | null | undefined,
  english: string | null | undefined,
): string {
  const primary = localizedText(locale, korean, english);
  const alternate = (locale === "ko" ? english : korean)?.trim() ?? "";
  return alternate && alternate !== primary ? alternate : "";
}

const localeTag = (locale: PortalLocale) => locale === "ko" ? "ko-KR" : "en-US";

const calendarDateFormatters: Record<PortalLocale, Intl.DateTimeFormat> = {
  ko: new Intl.DateTimeFormat("ko-KR", {
    year: "numeric", month: "short", day: "numeric", timeZone: "UTC",
  }),
  en: new Intl.DateTimeFormat("en-US", {
    year: "numeric", month: "short", day: "numeric", timeZone: "UTC",
  }),
};
const instantDateFormatters: Record<PortalLocale, Intl.DateTimeFormat> = {
  ko: new Intl.DateTimeFormat("ko-KR", {
    year: "numeric", month: "short", day: "numeric", timeZone: "Asia/Seoul",
  }),
  en: new Intl.DateTimeFormat("en-US", {
    year: "numeric", month: "short", day: "numeric", timeZone: "Asia/Seoul",
  }),
};
const dateTimeFormatters: Record<PortalLocale, Intl.DateTimeFormat> = {
  ko: new Intl.DateTimeFormat("ko-KR", {
    year: "numeric", month: "short", day: "numeric",
    hour: "2-digit", minute: "2-digit", timeZone: "Asia/Seoul",
  }),
  en: new Intl.DateTimeFormat("en-US", {
    year: "numeric", month: "short", day: "numeric",
    hour: "2-digit", minute: "2-digit", timeZone: "Asia/Seoul",
  }),
};
const numberFormatters: Record<PortalLocale, Intl.NumberFormat> = {
  ko: new Intl.NumberFormat("ko-KR"),
  en: new Intl.NumberFormat("en-US"),
};

function parseCalendarDate(value: string): Date | null {
  const match = /^(\d{4})-(\d{2})-(\d{2})$/u.exec(value);
  if (!match) return null;
  const date = new Date(Date.UTC(Number(match[1]), Number(match[2]) - 1, Number(match[3])));
  return Number.isNaN(date.getTime()) ? null : date;
}

export function formatDisplayDate(value: string, locale: PortalLocale): string {
  if (!value) return "—";
  const calendarDate = parseCalendarDate(value);
  const date = calendarDate ?? new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return (calendarDate ? calendarDateFormatters : instantDateFormatters)[locale]
    .format(date);
}

export function formatDisplayDateTime(value: string, locale: PortalLocale): string {
  if (!value) return "—";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return dateTimeFormatters[locale].format(date);
}

export function formatDisplayNumber(
  value: number,
  locale: PortalLocale,
  options?: Intl.NumberFormatOptions,
): string {
  if (!options || Object.keys(options).length === 0) {
    return numberFormatters[locale].format(value);
  }
  return new Intl.NumberFormat(localeTag(locale), options).format(value);
}

interface LocaleContextValue {
  locale: PortalLocale;
  setLocale: (locale: PortalLocale) => void;
  t: (key: MessageKey, parameters?: MessageParameters) => string;
  formatDate: (value: string) => string;
  formatDateTime: (value: string) => string;
  formatNumber: (value: number, options?: Intl.NumberFormatOptions) => string;
  localized: (korean: string | null | undefined, english: string | null | undefined, fallback?: string) => string;
}

const defaultContext: LocaleContextValue = {
  locale: "en",
  setLocale: () => undefined,
  t: (key, parameters) => translateMessage("en", key, parameters),
  formatDate: (value) => formatDisplayDate(value, "en"),
  formatDateTime: (value) => formatDisplayDateTime(value, "en"),
  formatNumber: (value, options) => formatDisplayNumber(value, "en", options),
  localized: (korean, english, fallback) => localizedText("en", korean, english, fallback),
};

const LocaleContext = createContext<LocaleContextValue>(defaultContext);

export function LocaleProvider({
  children,
  initialLocale,
  browserLanguages,
  storage = browserStorage(),
}: {
  children: ReactNode;
  initialLocale?: PortalLocale;
  browserLanguages?: readonly string[];
  storage?: LocaleStorage | null;
}) {
  const [locale, setLocale] = useState<PortalLocale>(() =>
    initialLocale ?? resolveLocale(
      (() => {
        try {
          return storage?.getItem(ADMIN_LOCALE_STORAGE_KEY) ?? null;
        } catch {
          return null;
        }
      })(),
      browserLanguages ?? navigator.languages,
    )
  );

  useEffect(() => {
    document.documentElement.lang = locale;
    try {
      storage?.setItem(ADMIN_LOCALE_STORAGE_KEY, locale);
    } catch {
      // Language switching remains live when storage is unavailable.
    }
  }, [locale, storage]);

  const localeRef = useRef(locale);
  localeRef.current = locale;
  const t = useCallback(
    (key: MessageKey, parameters?: MessageParameters) =>
      translateMessage(localeRef.current, key, parameters),
    [],
  );
  const formatDate = useCallback((value: string) => formatDisplayDate(value, locale), [locale]);
  const formatDateTime = useCallback(
    (value: string) => formatDisplayDateTime(value, locale),
    [locale],
  );
  const formatNumber = useCallback(
    (value: number, options?: Intl.NumberFormatOptions) =>
      formatDisplayNumber(value, locale, options),
    [locale],
  );
  const localized = useCallback(
    (korean: string | null | undefined, english: string | null | undefined, fallback?: string) =>
      localizedText(locale, korean, english, fallback),
    [locale],
  );
  const value = useMemo<LocaleContextValue>(
    () => ({ locale, setLocale, t, formatDate, formatDateTime, formatNumber, localized }),
    [formatDate, formatDateTime, formatNumber, locale, localized, t],
  );

  return <LocaleContext.Provider value={value}>{children}</LocaleContext.Provider>;
}

export function useI18n(): LocaleContextValue {
  return useContext(LocaleContext);
}

export function LanguageSwitch() {
  const { locale, setLocale, t } = useI18n();
  return (
    <div className="language-switch" role="group" aria-label={t("language.group")}>
      <button
        type="button"
        aria-label={t("language.switchKo")}
        aria-pressed={locale === "ko"}
        onClick={() => setLocale("ko")}
      >
        한국어
      </button>
      <button
        type="button"
        aria-label={t("language.switchEn")}
        aria-pressed={locale === "en"}
        onClick={() => setLocale("en")}
      >
        EN
      </button>
    </div>
  );
}
