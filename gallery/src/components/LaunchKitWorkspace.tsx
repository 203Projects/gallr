import { useEffect, useMemo, useRef, useState } from "react";
import type { LaunchGuest, LaunchGuestCursor, LaunchGuestStatus, LaunchKit, LocalPromotion, OwnerRepository } from "../domain";
import {
  LocaleToggle,
  formatNumber,
  formatTime,
  formatTimestampDate,
  localizeBilingual,
  useLocale,
  type PortalMessages,
} from "../i18n";
import { publicRsvpUrl } from "../publicRsvpUrl";
import { downloadRsvpQr } from "../rsvpQr";
import { OwnerShell } from "./OwnerShell";
import type { OwnerWorkspaceTarget } from "./OwnerShell";

type Repository = Pick<
  OwnerRepository,
  "listLaunchKits" | "listLaunchGuests" | "addLaunchGuest" | "checkInLaunchGuest" |
  "rotateLaunchRsvpToken" | "listLocalPromotions" | "requestLocalPromotion"
>;

type LaunchErrorKey = keyof PortalMessages["launch"]["errors"];

function message(_error: unknown, fallback: LaunchErrorKey): LaunchErrorKey {
  return fallback;
}

function arrival(value: string | null, locale: "ko" | "en"): string {
  return value ? formatTime(value, locale) : "—";
}

function promotionStatus(promotion: LocalPromotion, messages: PortalMessages): string {
  switch (promotion.status) {
    case "submitted": return messages.launch.promotionStatuses.submitted;
    case "approved": return messages.launch.promotionStatuses.approved;
    case "active": return messages.launch.promotionStatuses.active;
    case "rejected": return messages.launch.promotionStatuses.rejected;
    case "ended": return messages.launch.promotionStatuses.ended;
  }
}

function GuestRows({
  guests,
  onCheckIn,
  busyGuest,
  checkInView = false,
}: {
  guests: LaunchGuest[];
  onCheckIn: (guest: LaunchGuest) => void;
  busyGuest: string | null;
  checkInView?: boolean;
}) {
  const { locale, messages } = useLocale();
  return <>{guests.map((guest) => (
    <article className="launch-guest-row" key={guest.id}>
      <div><strong>{guest.name}</strong><span>{guest.email}</span></div>
      <span>{formatNumber(guest.partySize, locale)}{checkInView ? ` ${guest.partySize === 1 ? messages.launch.guest : messages.launch.guests}` : ""}</span>
      <span>{guest.status === "checked_in" ? messages.launch.checkedIn : messages.launch.going}</span>
      <span>{arrival(guest.checkedInAt, locale)}</span>
      {guest.status === "going" ? (
        <button type="button" onClick={() => onCheckIn(guest)} disabled={busyGuest === guest.id}>
          {busyGuest === guest.id ? messages.launch.checkingIn : messages.launch.checkIn}
        </button>
      ) : <span />}
    </article>
  ))}</>;
}

export function LaunchKitWorkspace({
  repository,
  onNavigate,
  onSignOut,
  promotionEnabled = false,
  publicSiteUrl = "https://gallrmap.com",
}: {
  repository: Repository;
  onNavigate: (target: OwnerWorkspaceTarget) => void;
  onSignOut: () => void;
  promotionEnabled?: boolean;
  publicSiteUrl?: string;
}) {
  const { locale, messages } = useLocale();
  const [kits, setKits] = useState<LaunchKit[]>([]);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const selectedIdRef = useRef<string | null>(null);
  const [guests, setGuests] = useState<LaunchGuest[]>([]);
  const [nextCursor, setNextCursor] = useState<LaunchGuestCursor | null>(null);
  const [guestsLoading, setGuestsLoading] = useState(false);
  const [query, setQuery] = useState("");
  const [filter, setFilter] = useState<"all" | LaunchGuestStatus>("all");
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<LaunchErrorKey | null>(null);
  const [adding, setAdding] = useState(false);
  const [checkInMode, setCheckInMode] = useState(false);
  const [busyGuest, setBusyGuest] = useState<string | null>(null);
  const [rotatingToken, setRotatingToken] = useState(false);
  const [promotion, setPromotion] = useState<LocalPromotion | null>(null);
  const [promotionBusy, setPromotionBusy] = useState(false);
  const [shareBusy, setShareBusy] = useState<"copy" | "qr" | null>(null);
  const [shareStatus, setShareStatus] = useState<string | null>(null);
  const selected = useMemo(
    () => kits.find((kit) => kit.id === selectedId) || null,
    [kits, selectedId],
  );
  const activeKits = useMemo(
    () => kits.filter((kit) => kit.status === "active"),
    [kits],
  );
  const selectedStatus = selected?.status;
  const selectedEntitlementSource = selected?.entitlementSource;
  const rsvpUrl = selected ? publicRsvpUrl(selected.publicToken, publicSiteUrl) : "";

  useEffect(() => {
    let current = true;
    void repository.listLaunchKits()
      .then((records) => {
        if (!current) return;
        setKits(records);
        setSelectedId((previous) => {
          const next = previous && records.some((kit) => kit.id === previous)
            ? previous
            : records.find((kit) => kit.status === "active")?.id || records[0]?.id || null;
          selectedIdRef.current = next;
          return next;
        });
      })
      .catch((cause) => { if (current) setError(message(cause, "load")); })
      .finally(() => { if (current) setLoading(false); });
    return () => { current = false; };
  }, [repository]);

  useEffect(() => {
    if (!promotionEnabled || !selectedId || selectedEntitlementSource !== "paid") {
      setPromotion(null);
      return;
    }
    let current = true;
    void repository.listLocalPromotions()
      .then((records) => {
        if (current) {
          setPromotion(records.find((item) => item.launchKitId === selectedId) || null);
        }
      })
      .catch((cause) => { if (current) setError(message(cause, "promotionLoad")); });
    return () => { current = false; };
  }, [promotionEnabled, repository, selectedEntitlementSource, selectedId]);

  useEffect(() => {
    if (!selectedId || selectedStatus !== "active") {
      setGuests([]);
      setNextCursor(null);
      return;
    }
    let current = true;
    const timer = window.setTimeout(() => {
      setGuestsLoading(true);
      void repository.listLaunchGuests(selectedId, query, filter)
        .then((page) => {
          if (!current) return;
          setGuests(page.records);
          setNextCursor(page.nextCursor);
        })
        .catch((cause) => { if (current) setError(message(cause, "guests")); })
        .finally(() => { if (current) setGuestsLoading(false); });
    }, query ? 250 : 0);
    return () => { current = false; window.clearTimeout(timer); };
  }, [repository, selectedId, selectedStatus, query, filter]);

  const visibleGuests = useMemo(() => {
    const normalized = query.trim().toLowerCase();
    return guests.filter((guest) => (
      (filter === "all" || guest.status === filter) &&
      (!normalized || guest.name.toLowerCase().includes(normalized) || guest.email.toLowerCase().includes(normalized))
    ));
  }, [guests, query, filter]);

  const updateKit = (updated: LaunchKit) => {
    setKits((current) => current.map((kit) => kit.id === updated.id ? updated : kit));
  };

  const selectKit = (launchKitId: string) => {
    selectedIdRef.current = launchKitId;
    setSelectedId(launchKitId);
    setGuests([]);
    setNextCursor(null);
    setGuestsLoading(false);
    setQuery("");
    setFilter("all");
    setAdding(false);
    setCheckInMode(false);
    setBusyGuest(null);
    setPromotion(null);
    setShareStatus(null);
    setError(null);
  };

  const loadMore = async () => {
    if (!selectedId || !nextCursor || guestsLoading) return;
    const operationKitId = selectedId;
    setGuestsLoading(true);
    setError(null);
    try {
      const page = await repository.listLaunchGuests(selectedId, query, filter, nextCursor);
      if (selectedIdRef.current !== operationKitId) return;
      setGuests((current) => [...current, ...page.records]);
      setNextCursor(page.nextCursor);
    } catch (cause) {
      if (selectedIdRef.current === operationKitId) setError(message(cause, "guests"));
    } finally {
      if (selectedIdRef.current === operationKitId) setGuestsLoading(false);
    }
  };

  const checkIn = async (guest: LaunchGuest) => {
    if (!selected || busyGuest) return;
    const operationKitId = selected.id;
    setBusyGuest(guest.id);
    setError(null);
    try {
      const updated = await repository.checkInLaunchGuest(selected.id, guest.id);
      if (selectedIdRef.current !== operationKitId) return;
      setGuests((current) => current.map((item) => item.id === updated.id ? updated : item));
      if (guest.status === "going") {
        setKits((current) => current.map((kit) => kit.id === selected.id
          ? { ...kit, checkedInCount: kit.checkedInCount + guest.partySize }
          : kit));
      }
    } catch (cause) {
      if (selectedIdRef.current === operationKitId) setError(message(cause, "checkIn"));
    } finally {
      if (selectedIdRef.current === operationKitId) setBusyGuest(null);
    }
  };

  const addGuest = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!selected) return;
    const operationKitId = selected.id;
    const form = new FormData(event.currentTarget);
    try {
      const guest = await repository.addLaunchGuest(
        operationKitId,
        String(form.get("name") || ""),
        String(form.get("email") || ""),
        Number(form.get("party_size")),
      );
      const refreshed = await repository.listLaunchKits();
      setKits(refreshed);
      if (selectedIdRef.current === operationKitId) {
        setGuests((current) => [
          guest,
          ...current.filter((item) => item.id !== guest.id),
        ]);
        setAdding(false);
      }
    } catch (cause) {
      if (selectedIdRef.current === operationKitId) setError(message(cause, "addGuest"));
    }
  };

  const rotateToken = async () => {
    if (
      !selected || rotatingToken || shareBusy ||
      !window.confirm(messages.launch.replaceConfirm)
    ) return;
    setRotatingToken(true);
    setError(null);
    setShareStatus(null);
    try {
      updateKit(await repository.rotateLaunchRsvpToken(selected.id));
    } catch (cause) { setError(message(cause, "rotate")); } finally { setRotatingToken(false); }
  };

  const copyRsvpLink = async () => {
    if (!selected || shareBusy || rotatingToken) return;
    setShareBusy("copy");
    setError(null);
    setShareStatus(null);
    try {
      if (!navigator.clipboard?.writeText) throw new Error("Clipboard access is unavailable.");
      await navigator.clipboard.writeText(rsvpUrl);
      setShareStatus(messages.launch.linkCopied);
    } catch (cause) {
      setError(message(cause, "copy"));
    } finally {
      setShareBusy(null);
    }
  };

  const downloadQr = async () => {
    if (!selected || shareBusy || rotatingToken) return;
    setShareBusy("qr");
    setError(null);
    setShareStatus(null);
    try {
      await downloadRsvpQr({ rsvpUrl, launchKitId: selected.id });
      setShareStatus(messages.launch.qrDownloaded);
    } catch (cause) {
      setError(message(cause, "qr"));
    } finally {
      setShareBusy(null);
    }
  };

  const requestPromotion = async () => {
    if (
      !promotionEnabled || !selected || selected.entitlementSource !== "paid" ||
      promotionBusy
    ) return;
    setPromotionBusy(true);
    setError(null);
    try {
      setPromotion(await repository.requestLocalPromotion(selected.id));
    } catch (cause) { setError(message(cause, "promotion")); } finally { setPromotionBusy(false); }
  };

  if (checkInMode && selected) {
    return (
      <div className="checkin-layout">
        <header><strong>gallr</strong><div className="checkin-header-actions"><LocaleToggle /><button type="button" onClick={() => setCheckInMode(false)}>{messages.launch.exit}</button></div></header>
        <main>
          <h1>{messages.launch.checkInTitle}</h1><p className="checkin-exhibition">{localizeBilingual(selected.nameKo, selected.nameEn, locale)}</p>
          <p>{messages.launch.checkedInCount(formatNumber(selected.checkedInCount, locale), formatNumber(selected.guestCount, locale))}</p>
          <input aria-label={messages.launch.searchNameEmail} placeholder={messages.launch.searchNameEmail} value={query} onChange={(event) => setQuery(event.target.value)} />
          <div className="launch-filters" role="group" aria-label={messages.launch.guestStatusFilter}>
            <button type="button" aria-pressed={filter === "going"} className={filter === "going" ? "is-active" : ""} onClick={() => setFilter("going")}>{messages.launch.going}</button>
            <button type="button" aria-pressed={filter === "checked_in"} className={filter === "checked_in" ? "is-active" : ""} onClick={() => setFilter("checked_in")}>{messages.launch.checkedIn}</button>
          </div>
          {error && <p className="field-error" role="alert">! {messages.launch.errors[error]}</p>}
          <div className="checkin-guests"><GuestRows guests={visibleGuests} onCheckIn={(guest) => void checkIn(guest)} busyGuest={busyGuest} checkInView /></div>
          {nextCursor && <button className="checkin-load-more" type="button" disabled={guestsLoading} onClick={() => void loadMore()}>{guestsLoading ? messages.launch.loading : messages.launch.loadMore}</button>}
        </main>
      </div>
    );
  }

  return (
    <OwnerShell active="launch" launchKitEnabled onNavigate={onNavigate} onSignOut={onSignOut}>
      <main className="workspace launch-workspace">
        {loading ? <p>{messages.launch.loadingKits}</p> : !selected ? (
          <section className="dashboard-empty"><h1>{messages.launch.emptyTitle}</h1><p>{messages.launch.emptyBody}</p></section>
        ) : selected.status !== "active" ? (
          <section><h1>{messages.launch.activationUnavailable}</h1><p>{messages.launch.activationUnavailableBody}</p></section>
        ) : (
          <>
            <header className="launch-heading">
              <div>
                {activeKits.length > 1 && (
                  <label className="launch-kit-selector">
                    <span>{messages.launch.kitSelector}</span>
                    <select
                      value={selected.id}
                      disabled={shareBusy !== null || rotatingToken || promotionBusy}
                      onChange={(event) => selectKit(event.target.value)}
                    >
                      {activeKits.map((kit) => (
                        <option key={kit.id} value={kit.id}>{localizeBilingual(kit.nameKo, kit.nameEn, locale)}</option>
                      ))}
                    </select>
                  </label>
                )}
                <h1>{messages.launch.openingNight}</h1><p>{localizeBilingual(selected.nameKo, selected.nameEn, locale)}</p>
              </div>
              <div className="launch-heading-actions">
                <a href={rsvpUrl} target="_blank" rel="noreferrer">{messages.launch.viewRsvp}</a>
                <button className="text-button" type="button" disabled={shareBusy !== null || rotatingToken} onClick={() => void copyRsvpLink()}>{shareBusy === "copy" ? messages.launch.copying : messages.launch.copyRsvp}</button>
                <button className="outlined-button" type="button" disabled={shareBusy !== null || rotatingToken} onClick={() => void downloadQr()}>{shareBusy === "qr" ? messages.launch.preparingQr : messages.launch.downloadQr}</button>
                <button className="text-button rotate-rsvp" type="button" disabled={rotatingToken || shareBusy !== null} onClick={() => void rotateToken()}>{rotatingToken ? messages.launch.replacing : messages.launch.replaceRsvp}</button>
                <button className="outlined-button" type="button" onClick={() => { setQuery(""); setFilter("going"); setCheckInMode(true); }}>{messages.launch.checkInMode}</button>
              </div>
            </header>
            {shareStatus && <p className="rsvp-action-status" role="status">{shareStatus}</p>}
            <dl className="launch-summary">
              <div><dt>{messages.launch.summaryGoing}</dt><dd>{formatNumber(selected.rsvpCount, locale)}</dd></div>
              <div><dt>{messages.launch.summaryGuests}</dt><dd>{formatNumber(selected.guestCount, locale)}</dd></div>
              <div><dt>{messages.launch.summaryCheckedIn}</dt><dd>{formatNumber(selected.checkedInCount, locale)}</dd></div>
            </dl>
            {promotionEnabled && selected.entitlementSource === "paid" && <section className="promotion-request" aria-labelledby="promotion-heading">
              <div>
                <h2 id="promotion-heading">{messages.launch.promotionTitle}</h2>
                <p>{messages.launch.promotionBody}</p>
                <p className="promotion-review-note">{messages.launch.promotionReview}</p>
              </div>
              <div className="promotion-request-action">
                {promotion ? (
                  <>
                    <strong>{promotionStatus(promotion, messages)}</strong>
                    <span>{localizeBilingual(promotion.cityKo, promotion.cityEn, locale)}{promotion.regionEn || promotion.regionKo ? ` · ${localizeBilingual(promotion.regionKo, promotion.regionEn, locale)}` : ""}</span>
                    {promotion.startsAt && promotion.endsAt && <span>{formatTimestampDate(promotion.startsAt, locale)} — {formatTimestampDate(promotion.endsAt, locale)}</span>}
                    {promotion.reviewNotes && <span>! {promotion.reviewNotes}</span>}
                    {(promotion.status === "rejected" || promotion.status === "ended") && (
                      <button className="outlined-button" type="button" disabled={promotionBusy} onClick={() => void requestPromotion()}>
                        {promotionBusy ? messages.launch.submitting : messages.launch.requestAgain}
                      </button>
                    )}
                  </>
                ) : (
                  <button className="primary-button" type="button" disabled={promotionBusy} onClick={() => void requestPromotion()}>
                    {promotionBusy ? messages.launch.submitting : messages.launch.requestPromotion}
                  </button>
                )}
              </div>
            </section>}
            <section className="guest-list">
              <div className="guest-list-heading"><h2>{messages.launch.guestList}</h2><button className="primary-button" type="button" onClick={() => setAdding((value) => !value)}>{messages.launch.addGuest}</button></div>
              {adding && <form className="add-guest-form" onSubmit={(event) => void addGuest(event)}>
                <label className="field"><span>{messages.launch.name}</span><input name="name" required maxLength={200} /></label>
                <label className="field"><span>{messages.launch.email}</span><input name="email" type="email" required maxLength={320} /></label>
                <label className="field"><span>{messages.launch.party}</span><select name="party_size" defaultValue="1">{[1,2,3,4,5,6].map((size) => <option key={size}>{size}</option>)}</select></label>
                <button className="standard-button" type="submit">{messages.launch.saveGuest}</button>
              </form>}
              <div className="guest-tools">
                <input aria-label={messages.launch.searchGuests} placeholder={messages.launch.searchGuests} value={query} onChange={(event) => setQuery(event.target.value)} />
                <div className="launch-filters" role="group" aria-label={messages.launch.guestStatusFilter}>
                  {(["all", "going", "checked_in"] as const).map((status) => <button type="button" aria-pressed={filter === status} key={status} className={filter === status ? "is-active" : ""} onClick={() => setFilter(status)}>{status === "all" ? messages.launch.all : status === "going" ? messages.launch.going : messages.launch.checkedIn}</button>)}
                </div>
              </div>
              {error && <p className="field-error" role="alert">! {messages.launch.errors[error]}</p>}
              <div className="guest-list-head" aria-hidden="true"><span>{messages.launch.columnGuest}</span><span>{messages.launch.columnParty}</span><span>{messages.launch.columnStatus}</span><span>{messages.launch.columnArrival}</span><span /></div>
              <GuestRows guests={visibleGuests} onCheckIn={(guest) => void checkIn(guest)} busyGuest={busyGuest} />
              {nextCursor && <button className="outlined-button guest-load-more" type="button" disabled={guestsLoading} onClick={() => void loadMore()}>{guestsLoading ? messages.launch.loading : messages.launch.loadMore}</button>}
            </section>
          </>
        )}
      </main>
    </OwnerShell>
  );
}
