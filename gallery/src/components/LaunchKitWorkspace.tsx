import { useEffect, useMemo, useState } from "react";
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
}: {
  repository: Repository;
  onNavigate: (target: OwnerWorkspaceTarget) => void;
  onSignOut: () => void;
}) {
  const { locale, messages } = useLocale();
  const [selected, setSelected] = useState<LaunchKit | null>(null);
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

  useEffect(() => {
    void repository.listLaunchKits().then((records) => {
      setSelected(records.find((kit) => kit.status === "active") || records[0] || null);
    }).catch((cause) => setError(message(cause, "load"))).finally(() => setLoading(false));
  }, [repository]);

  useEffect(() => {
    let current = true;
    void repository.listLocalPromotions()
      .then((records) => { if (current) setPromotion(records[0] || null); })
      .catch((cause) => { if (current) setError(message(cause, "promotionLoad")); });
    return () => { current = false; };
  }, [repository]);

  const selectedId = selected?.id;
  const selectedStatus = selected?.status;
  useEffect(() => {
    if (!selectedId || selectedStatus !== "active") return;
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

  const loadMore = async () => {
    if (!selectedId || !nextCursor || guestsLoading) return;
    setGuestsLoading(true);
    setError(null);
    try {
      const page = await repository.listLaunchGuests(selectedId, query, filter, nextCursor);
      setGuests((current) => [...current, ...page.records]);
      setNextCursor(page.nextCursor);
    } catch (cause) { setError(message(cause, "guests")); } finally { setGuestsLoading(false); }
  };

  const checkIn = async (guest: LaunchGuest) => {
    if (!selected || busyGuest) return;
    setBusyGuest(guest.id);
    setError(null);
    try {
      const updated = await repository.checkInLaunchGuest(selected.id, guest.id);
      setGuests((current) => current.map((item) => item.id === updated.id ? updated : item));
      if (guest.status === "going") {
        setSelected((current) => current ? { ...current, checkedInCount: current.checkedInCount + guest.partySize } : current);
      }
    } catch (cause) { setError(message(cause, "checkIn")); } finally { setBusyGuest(null); }
  };

  const addGuest = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!selected) return;
    const form = new FormData(event.currentTarget);
    try {
      const guest = await repository.addLaunchGuest(
        selected.id,
        String(form.get("name") || ""),
        String(form.get("email") || ""),
        Number(form.get("party_size")),
      );
      setGuests((current) => {
        const next = [guest, ...current.filter((item) => item.id !== guest.id)];
        return next;
      });
      const refreshed = await repository.listLaunchKits();
      setSelected(refreshed.find((kit) => kit.id === selected.id) || selected);
      setAdding(false);
    } catch (cause) { setError(message(cause, "addGuest")); }
  };

  const rotateToken = async () => {
    if (!selected || rotatingToken || !window.confirm(messages.launch.replaceConfirm)) return;
    setRotatingToken(true);
    setError(null);
    try {
      setSelected(await repository.rotateLaunchRsvpToken(selected.id));
    } catch (cause) { setError(message(cause, "rotate")); } finally { setRotatingToken(false); }
  };

  const requestPromotion = async () => {
    if (!selected || promotionBusy) return;
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
          <div className="launch-filters">
            <button className={filter === "going" ? "is-active" : ""} onClick={() => setFilter("going")}>{messages.launch.going}</button>
            <button className={filter === "checked_in" ? "is-active" : ""} onClick={() => setFilter("checked_in")}>{messages.launch.checkedIn}</button>
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
          <section><h1>{messages.launch.paymentPending}</h1><p>{messages.launch.paymentBody}</p></section>
        ) : (
          <>
            <header className="launch-heading">
              <div><h1>{messages.launch.openingNight}</h1><p>{localizeBilingual(selected.nameKo, selected.nameEn, locale)}</p></div>
              <div className="launch-heading-actions">
                <a href={`https://gallrmap.com/rsvp/?token=${selected.publicToken}`} target="_blank" rel="noreferrer">{messages.launch.viewRsvp}</a>
                <button className="text-button rotate-rsvp" type="button" disabled={rotatingToken} onClick={() => void rotateToken()}>{rotatingToken ? messages.launch.replacing : messages.launch.replaceRsvp}</button>
                <button className="outlined-button" type="button" onClick={() => { setFilter("going"); setCheckInMode(true); }}>{messages.launch.checkInMode}</button>
              </div>
            </header>
            <dl className="launch-summary">
              <div><dt>{messages.launch.summaryGoing}</dt><dd>{formatNumber(selected.rsvpCount, locale)}</dd></div>
              <div><dt>{messages.launch.summaryGuests}</dt><dd>{formatNumber(selected.guestCount, locale)}</dd></div>
              <div><dt>{messages.launch.summaryCheckedIn}</dt><dd>{formatNumber(selected.checkedInCount, locale)}</dd></div>
            </dl>
            <section className="promotion-request" aria-labelledby="promotion-heading">
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
            </section>
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
                <div className="launch-filters">
                  {(["all", "going", "checked_in"] as const).map((status) => <button key={status} className={filter === status ? "is-active" : ""} onClick={() => setFilter(status)}>{status === "all" ? messages.launch.all : status === "going" ? messages.launch.going : messages.launch.checkedIn}</button>)}
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
