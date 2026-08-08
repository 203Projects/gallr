import { useEffect, useMemo, useState } from "react";
import type { LaunchGuest, LaunchGuestCursor, LaunchGuestStatus, LaunchKit, LocalPromotion, OwnerRepository } from "../domain";
import { OwnerShell } from "./OwnerShell";

type Repository = Pick<
  OwnerRepository,
  "listLaunchKits" | "listLaunchGuests" | "addLaunchGuest" | "checkInLaunchGuest" |
  "rotateLaunchRsvpToken" | "listLocalPromotions" | "requestLocalPromotion"
>;

function message(error: unknown): string {
  return error instanceof Error && error.message ? error.message : "Launch Kit could not be loaded.";
}

function arrival(value: string | null): string {
  return value ? new Date(value).toLocaleTimeString("en-US", { hour: "numeric", minute: "2-digit" }) : "—";
}

function promotionStatus(promotion: LocalPromotion): string {
  switch (promotion.status) {
    case "submitted": return "Submitted for review";
    case "approved": return "Scheduled";
    case "active": return "Active now";
    case "rejected": return "Changes required";
    case "ended": return "Ended";
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
  return <>{guests.map((guest) => (
    <article className="launch-guest-row" key={guest.id}>
      <div><strong>{guest.name}</strong><span>{guest.email}</span></div>
      <span>{guest.partySize}{checkInView ? ` ${guest.partySize === 1 ? "guest" : "guests"}` : ""}</span>
      <span>{guest.status === "checked_in" ? "Checked in" : "Going"}</span>
      <span>{arrival(guest.checkedInAt)}</span>
      {guest.status === "going" ? (
        <button type="button" onClick={() => onCheckIn(guest)} disabled={busyGuest === guest.id}>
          {busyGuest === guest.id ? "Checking in…" : "Check in"}
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
}: {
  repository: Repository;
  onNavigate: (target: "exhibitions" | "launch") => void;
  onSignOut: () => void;
  promotionEnabled?: boolean;
}) {
  const [selected, setSelected] = useState<LaunchKit | null>(null);
  const [guests, setGuests] = useState<LaunchGuest[]>([]);
  const [nextCursor, setNextCursor] = useState<LaunchGuestCursor | null>(null);
  const [guestsLoading, setGuestsLoading] = useState(false);
  const [query, setQuery] = useState("");
  const [filter, setFilter] = useState<"all" | LaunchGuestStatus>("all");
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [adding, setAdding] = useState(false);
  const [checkInMode, setCheckInMode] = useState(false);
  const [busyGuest, setBusyGuest] = useState<string | null>(null);
  const [rotatingToken, setRotatingToken] = useState(false);
  const [promotion, setPromotion] = useState<LocalPromotion | null>(null);
  const [promotionBusy, setPromotionBusy] = useState(false);

  useEffect(() => {
    void repository.listLaunchKits().then((records) => {
      setSelected(records.find((kit) => kit.status === "active") || records[0] || null);
    }).catch((cause) => setError(message(cause))).finally(() => setLoading(false));
  }, [repository]);

  useEffect(() => {
    if (!promotionEnabled) {
      setPromotion(null);
      return;
    }
    let current = true;
    void repository.listLocalPromotions()
      .then((records) => { if (current) setPromotion(records[0] || null); })
      .catch((cause) => { if (current) setError(message(cause)); });
    return () => { current = false; };
  }, [promotionEnabled, repository]);

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
        .catch((cause) => { if (current) setError(message(cause)); })
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
    } catch (cause) { setError(message(cause)); } finally { setGuestsLoading(false); }
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
    } catch (cause) { setError(message(cause)); } finally { setBusyGuest(null); }
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
    } catch (cause) { setError(message(cause)); }
  };

  const rotateToken = async () => {
    if (!selected || rotatingToken || !window.confirm("Replace this RSVP link? The current link will stop working immediately.")) return;
    setRotatingToken(true);
    setError(null);
    try {
      setSelected(await repository.rotateLaunchRsvpToken(selected.id));
    } catch (cause) { setError(message(cause)); } finally { setRotatingToken(false); }
  };

  const requestPromotion = async () => {
    if (!selected || promotionBusy) return;
    setPromotionBusy(true);
    setError(null);
    try {
      setPromotion(await repository.requestLocalPromotion(selected.id));
    } catch (cause) { setError(message(cause)); } finally { setPromotionBusy(false); }
  };

  if (checkInMode && selected) {
    return (
      <div className="checkin-layout">
        <header><strong>gallr</strong><button type="button" onClick={() => setCheckInMode(false)}>Exit</button></header>
        <main>
          <h1>Check in guests</h1><p className="checkin-exhibition">{selected.nameEn || selected.nameKo}</p>
          <p>{selected.checkedInCount} of {selected.guestCount} checked in</p>
          <input aria-label="Search name or email" placeholder="Search name or email" value={query} onChange={(event) => setQuery(event.target.value)} />
          <div className="launch-filters">
            <button className={filter === "going" ? "is-active" : ""} onClick={() => setFilter("going")}>Going</button>
            <button className={filter === "checked_in" ? "is-active" : ""} onClick={() => setFilter("checked_in")}>Checked in</button>
          </div>
          {error && <p className="field-error" role="alert">! {error}</p>}
          <div className="checkin-guests"><GuestRows guests={visibleGuests} onCheckIn={(guest) => void checkIn(guest)} busyGuest={busyGuest} checkInView /></div>
          {nextCursor && <button className="checkin-load-more" type="button" disabled={guestsLoading} onClick={() => void loadMore()}>{guestsLoading ? "Loading…" : "Load more guests"}</button>}
        </main>
      </div>
    );
  }

  return (
    <OwnerShell active="launch" launchKitEnabled onNavigate={onNavigate} onSignOut={onSignOut}>
      <main className="workspace launch-workspace">
        {loading ? <p>Loading Launch Kits…</p> : !selected ? (
          <section className="dashboard-empty"><h1>No Launch Kits yet.</h1><p>Launch a published exhibition from its editor.</p></section>
        ) : selected.status !== "active" ? (
          <section><h1>Payment pending</h1><p>We’ll activate the Launch Kit after Stripe confirms payment.</p></section>
        ) : (
          <>
            <header className="launch-heading">
              <div><h1>Opening night</h1><p>{selected.nameEn || selected.nameKo}</p></div>
              <div className="launch-heading-actions">
                <a href={`https://gallrmap.com/rsvp/?token=${selected.publicToken}`} target="_blank" rel="noreferrer">View RSVP page</a>
                <button className="text-button rotate-rsvp" type="button" disabled={rotatingToken} onClick={() => void rotateToken()}>{rotatingToken ? "Replacing…" : "Replace RSVP link"}</button>
                <button className="outlined-button" type="button" onClick={() => { setFilter("going"); setCheckInMode(true); }}>Check-in mode</button>
              </div>
            </header>
            <dl className="launch-summary">
              <div><dt>Going</dt><dd>{selected.rsvpCount}</dd></div>
              <div><dt>Guests</dt><dd>{selected.guestCount}</dd></div>
              <div><dt>Checked in</dt><dd>{selected.checkedInCount}</dd></div>
            </dl>
            {promotionEnabled && <section className="promotion-request" aria-labelledby="promotion-heading">
              <div>
                <h2 id="promotion-heading">Promoted near you</h2>
                <p>Paid placement for this exhibition, shown only to relevant local visitors and at most once per day.</p>
                <p className="promotion-review-note">Gallr staff reviews every request. Editorial Featured remains separate.</p>
              </div>
              <div className="promotion-request-action">
                {promotion ? (
                  <>
                    <strong>{promotionStatus(promotion)}</strong>
                    <span>{promotion.cityEn || promotion.cityKo}{promotion.regionEn || promotion.regionKo ? ` · ${promotion.regionEn || promotion.regionKo}` : ""}</span>
                    {promotion.startsAt && promotion.endsAt && <span>{new Date(promotion.startsAt).toLocaleDateString()} — {new Date(promotion.endsAt).toLocaleDateString()}</span>}
                    {promotion.reviewNotes && <span>! {promotion.reviewNotes}</span>}
                    {(promotion.status === "rejected" || promotion.status === "ended") && (
                      <button className="outlined-button" type="button" disabled={promotionBusy} onClick={() => void requestPromotion()}>
                        {promotionBusy ? "Submitting…" : "Request again"}
                      </button>
                    )}
                  </>
                ) : (
                  <button className="primary-button" type="button" disabled={promotionBusy} onClick={() => void requestPromotion()}>
                    {promotionBusy ? "Submitting…" : "Request local promotion"}
                  </button>
                )}
              </div>
            </section>}
            <section className="guest-list">
              <div className="guest-list-heading"><h2>Guest list</h2><button className="primary-button" type="button" onClick={() => setAdding((value) => !value)}>Add guest</button></div>
              {adding && <form className="add-guest-form" onSubmit={(event) => void addGuest(event)}>
                <label className="field"><span>Name</span><input name="name" required maxLength={200} /></label>
                <label className="field"><span>Email</span><input name="email" type="email" required maxLength={320} /></label>
                <label className="field"><span>Party</span><select name="party_size" defaultValue="1">{[1,2,3,4,5,6].map((size) => <option key={size}>{size}</option>)}</select></label>
                <button className="standard-button" type="submit">Save guest</button>
              </form>}
              <div className="guest-tools">
                <input aria-label="Search guests" placeholder="Search guests" value={query} onChange={(event) => setQuery(event.target.value)} />
                <div className="launch-filters">
                  {(["all", "going", "checked_in"] as const).map((status) => <button key={status} className={filter === status ? "is-active" : ""} onClick={() => setFilter(status)}>{status === "all" ? "All" : status === "going" ? "Going" : "Checked in"}</button>)}
                </div>
              </div>
              {error && <p className="field-error" role="alert">! {error}</p>}
              <div className="guest-list-head" aria-hidden="true"><span>Guest</span><span>Party</span><span>Status</span><span>Arrival</span><span /></div>
              <GuestRows guests={visibleGuests} onCheckIn={(guest) => void checkIn(guest)} busyGuest={busyGuest} />
              {nextCursor && <button className="outlined-button guest-load-more" type="button" disabled={guestsLoading} onClick={() => void loadMore()}>{guestsLoading ? "Loading…" : "Load more guests"}</button>}
            </section>
          </>
        )}
      </main>
    </OwnerShell>
  );
}
