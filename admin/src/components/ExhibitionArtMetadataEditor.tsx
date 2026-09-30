import { useEffect, useLayoutEffect, useMemo, useRef, useState } from "react";
import type {
  ArtistLookup,
  ArtTerm,
  ArtTermCategory,
  ExhibitionArtMetadata,
} from "../domain";
import { useI18n, type MessageKey } from "../i18n";

const MAX_ARTISTS = 32;
const MAX_ARTIST_SEARCH_LENGTH = 100;
const MAX_TERMS = 16;
const MAX_TERMS_PER_CATEGORY = 6;
const categories: ArtTermCategory[] = ["medium", "style", "theme", "mood"];
const categoryKeys: Record<ArtTermCategory, MessageKey> = {
  medium: "art.medium",
  style: "art.style",
  theme: "art.theme",
  mood: "art.mood",
};

function withCanonicalArtist(
  metadata: ExhibitionArtMetadata,
  artist: ArtistLookup,
  resolvingIndex: number | null,
): ExhibitionArtMetadata | null {
  const duplicateIndex = metadata.artists.findIndex(({ id }) => id === artist.id);
  if (duplicateIndex >= 0 && duplicateIndex !== resolvingIndex) {
    return resolvingIndex === null
      ? null
      : {
          ...metadata,
          artists: metadata.artists.filter((_, index) => index !== resolvingIndex),
        };
  }
  const artists = [...metadata.artists];
  if (resolvingIndex === null) {
    if (artists.length >= MAX_ARTISTS) return null;
    artists.push(artist);
  } else if (resolvingIndex < artists.length) {
    artists[resolvingIndex] = artist;
  } else {
    return null;
  }
  return { ...metadata, artists };
}

interface Props {
  metadata: ExhibitionArtMetadata | null;
  terms: ArtTerm[] | null;
  disabled: boolean;
  onChange: (metadata: ExhibitionArtMetadata) => void;
  onSearchArtists: (query: string) => Promise<ArtistLookup[]>;
  onCreateArtist: (
    nameKo: string,
    nameEn: string,
    requestId: string,
  ) => Promise<ArtistLookup>;
}

export function ExhibitionArtMetadataEditor({
  metadata,
  terms,
  disabled,
  onChange,
  onSearchArtists,
  onCreateArtist,
}: Props) {
  const { localized, t } = useI18n();
  const [query, setQuery] = useState("");
  const [results, setResults] = useState<ArtistLookup[]>([]);
  const [searching, setSearching] = useState(false);
  const [searchFailed, setSearchFailed] = useState(false);
  const [searchCompleted, setSearchCompleted] = useState(false);
  const [resolvingIndex, setResolvingIndex] = useState<number | null>(null);
  const [nameKo, setNameKo] = useState("");
  const [nameEn, setNameEn] = useState("");
  const [creating, setCreating] = useState(false);
  const [createFailed, setCreateFailed] = useState(false);
  const searchGeneration = useRef(0);
  const createGeneration = useRef(0);
  const mounted = useRef(true);
  const createRequest = useRef<{ key: string; id: string } | null>(null);
  const searchInput = useRef<HTMLInputElement>(null);
  const previousCreation = useRef<{
    nameKo: string;
    nameEn: string;
    request: typeof createRequest.current;
  } | null>(null);
  const latestMetadata = useRef(metadata);
  const latestOnChange = useRef(onChange);
  const latestResolvingIndex = useRef(resolvingIndex);
  latestMetadata.current = metadata;
  latestOnChange.current = onChange;
  latestResolvingIndex.current = resolvingIndex;
  const metadataSupported = metadata !== null;
  const interactionDisabled = disabled || creating;
  const selectedTermIds = useMemo(
    () => new Set(metadata?.terms.map(({ id }) => id) ?? []),
    [metadata?.terms],
  );
  const groupedTerms = useMemo(() => {
    const groups = new Map<ArtTermCategory, ArtTerm[]>();
    for (const category of categories) groups.set(category, []);
    for (const term of terms ?? []) groups.get(term.category)?.push(term);
    return groups;
  }, [terms]);

  useEffect(() => {
    const normalized = Array.from(query.trim()).slice(0, MAX_ARTIST_SEARCH_LENGTH).join("");
    const generation = ++searchGeneration.current;
    setSearchFailed(false);
    setSearchCompleted(false);
    if (normalized.length < (resolvingIndex === null ? 2 : 1) || interactionDisabled || !metadataSupported) {
      setResults([]);
      setSearching(false);
      return;
    }
    setSearching(true);
    const timer = window.setTimeout(() => {
      void onSearchArtists(normalized)
        .then((artists) => {
          if (searchGeneration.current !== generation) return;
          setResults(artists);
          setSearchCompleted(true);
        })
        .catch(() => {
          if (searchGeneration.current !== generation) return;
          setResults([]);
          setSearchFailed(true);
        })
        .finally(() => {
          if (searchGeneration.current === generation) setSearching(false);
        });
    }, 250);
    return () => {
      window.clearTimeout(timer);
      if (searchGeneration.current === generation) searchGeneration.current += 1;
    };
  }, [interactionDisabled, metadataSupported, onSearchArtists, query, resolvingIndex]);

  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
      createGeneration.current += 1;
      searchGeneration.current += 1;
    };
  }, []);

  useLayoutEffect(() => {
    // Focus after the prompt is laid out so the search scrolls into view too.
    if (resolvingIndex !== null) searchInput.current?.focus();
  }, [resolvingIndex]);

  if (metadata === null) {
    return <p className="field-help" role="status">{t("art.unsupported")}</p>;
  }

  const artistName = (artist: { nameKo: string; nameEn: string }) =>
    localized(artist.nameKo, artist.nameEn, t("common.unavailable"));
  const resolvingArtist = resolvingIndex === null ? null : metadata.artists[resolvingIndex];
  const resolvingName = resolvingArtist ? artistName(resolvingArtist) : "";
  const canCreate = !interactionDisabled && Boolean(nameKo.trim() && nameEn.trim()) &&
    (resolvingIndex !== null || metadata.artists.length < MAX_ARTISTS);

  const clearSearch = () => {
    searchGeneration.current += 1;
    setQuery("");
    setResults([]);
    setSearching(false);
    setSearchFailed(false);
    setSearchCompleted(false);
  };

  const finishResolution = () => {
    setResolvingIndex(null);
    previousCreation.current = null;
    setNameKo("");
    setNameEn("");
    createRequest.current = null;
    setCreateFailed(false);
    clearSearch();
  };

  const cancelResolution = () => {
    setResolvingIndex(null);
    if (previousCreation.current) {
      setNameKo(previousCreation.current.nameKo);
      setNameEn(previousCreation.current.nameEn);
      createRequest.current = previousCreation.current.request;
    }
    previousCreation.current = null;
    setCreateFailed(false);
    clearSearch();
  };

  const beginResolution = (index: number) => {
    const artist = metadata.artists[index];
    if (interactionDisabled || artist.id !== null) return;
    if (resolvingIndex === index) {
      searchInput.current?.focus();
      return;
    }
    if (previousCreation.current === null) {
      previousCreation.current = { nameKo, nameEn, request: createRequest.current };
    }
    clearSearch();
    setResolvingIndex(index);
    setQuery(artistName(artist));
    setNameKo(artist.nameKo);
    setNameEn(artist.nameEn);
    createRequest.current = null;
    setCreateFailed(false);
  };

  const updateArtist = (artist: ArtistLookup) => {
    if (creating) return;
    const next = withCanonicalArtist(metadata, artist, resolvingIndex);
    if (next === null) return;
    onChange(next);
    if (resolvingIndex !== null) finishResolution();
    else clearSearch();
  };

  const move = (index: number, offset: -1 | 1) => {
    const target = index + offset;
    if (target < 0 || target >= metadata.artists.length) return;
    const artists = [...metadata.artists];
    [artists[index], artists[target]] = [artists[target], artists[index]];
    onChange({ ...metadata, artists });
    if (resolvingIndex === index) setResolvingIndex(target);
    else if (resolvingIndex === target) setResolvingIndex(index);
  };

  const remove = (index: number) => {
    onChange({
      ...metadata,
      artists: metadata.artists.filter((_, artistIndex) => artistIndex !== index),
    });
    if (resolvingIndex === index) cancelResolution();
    else if (resolvingIndex !== null && resolvingIndex > index) setResolvingIndex(resolvingIndex - 1);
  };

  const toggleTerm = (term: ArtTerm, checked: boolean) => {
    onChange({
      ...metadata,
      terms: checked
        ? [...metadata.terms, term]
        : metadata.terms.filter(({ id }) => id !== term.id),
    });
  };

  const createArtist = async () => {
    const normalizedKo = nameKo.trim();
    const normalizedEn = nameEn.trim();
    if (!normalizedKo || !normalizedEn || creating || disabled) return;
    const key = `${normalizedKo}\u0000${normalizedEn}`;
    if (createRequest.current?.key !== key) {
      createRequest.current = { key, id: crypto.randomUUID() };
    }
    setCreating(true);
    setCreateFailed(false);
    const generation = ++createGeneration.current;
    const requestId = createRequest.current.id;
    try {
      const artist = await onCreateArtist(
        normalizedKo,
        normalizedEn,
        requestId,
      );
      if (!mounted.current || createGeneration.current !== generation) return;
      const current = latestMetadata.current;
      if (current === null) return;
      const next = withCanonicalArtist(current, artist, latestResolvingIndex.current);
      if (next === null) return;
      latestOnChange.current(next);
      finishResolution();
    } catch {
      if (mounted.current && createGeneration.current === generation) setCreateFailed(true);
    } finally {
      if (mounted.current && createGeneration.current === generation) setCreating(false);
    }
  };

  return (
    <div className="art-metadata-editor">
      <p className="field-help">{t("art.reviewHelp")}</p>
      <section aria-labelledby="art-artists-heading">
        <h3 id="art-artists-heading">{t("art.artists")}</h3>
        {metadata.artists.length === 0 ? (
          <p className="field-help">{t("art.noArtists")}</p>
        ) : (
          <ol className="artist-credit-list" aria-label={t("art.orderedCredits")}>
            {metadata.artists.map((artist, index) => {
              const name = artistName(artist);
              return (
                <li key={artist.id ?? `${artist.nameKo}\u0000${artist.nameEn}`} className={resolvingIndex === index ? "is-resolving" : undefined}>
                  <div>
                    <strong>{name}</strong>
                    {artist.id === null && <span className="unresolved-label">{t(resolvingIndex === index ? "art.resolving" : "art.unresolved")}</span>}
                  </div>
                  <div className="artist-credit-actions">
                    {artist.id === null && (
                      <button
                        className="outlined-compact"
                        type="button"
                        disabled={interactionDisabled}
                        aria-pressed={resolvingIndex === index}
                        onClick={() => beginResolution(index)}
                      >
                        {t("art.resolveArtist", { name })}
                      </button>
                    )}
                    <button className="outlined-compact" type="button" disabled={interactionDisabled || index === 0} aria-label={t("art.moveUp", { name })} onClick={() => move(index, -1)}>↑</button>
                    <button className="outlined-compact" type="button" disabled={interactionDisabled || index === metadata.artists.length - 1} aria-label={t("art.moveDown", { name })} onClick={() => move(index, 1)}>↓</button>
                    <button className="text-button" type="button" disabled={interactionDisabled} aria-label={t("art.remove", { name })} onClick={() => remove(index)}>×</button>
                  </div>
                </li>
              );
            })}
          </ol>
        )}

        {resolvingArtist && (
          <div className="artist-resolution-prompt" role="group" aria-labelledby="artist-resolution-help">
            <p id="artist-resolution-help" className="field-help" role="status">{t("art.resolvePrompt", { name: resolvingName })}</p>
            <div className="artist-resolution-actions">
              <button className="outlined-button" type="button" disabled={!canCreate} onClick={() => void createArtist()}>
                {creating ? t("art.creating") : t("art.createNamed", { name: resolvingName })}
              </button>
              <button className="text-button" type="button" disabled={interactionDisabled} onClick={cancelResolution}>
                {t("art.cancelResolve")}
              </button>
            </div>
            {createFailed && <p className="field-error" role="alert">{t("art.createFailed")}</p>}
          </div>
        )}
        <label className="field">
          <span>{t("art.searchArtists")}</span>
          <input ref={searchInput} aria-describedby={resolvingArtist ? "artist-resolution-help" : undefined} type="search" value={query} disabled={interactionDisabled} onChange={(event) => setQuery(event.target.value)} />
        </label>
        <div className="art-search-status" role="status" aria-live="polite">
          {searching ? t("art.searching") : searchFailed ? t("art.searchFailed") : searchCompleted && results.length === 0 ? resolvingArtist ? t("art.resolveNoMatches", { name: resolvingName }) : t("art.noMatches") : ""}
        </div>
        {results.length > 0 && (
          <ul className="artist-search-results">
            {results.map((artist) => {
              const name = artistName(artist);
              return (
                <li key={artist.id}>
                  <span>{name}</span>
                  <button
                    className="outlined-compact"
                    type="button"
                    disabled={
                      interactionDisabled ||
                      resolvingIndex === null &&
                      (metadata.artists.length >= MAX_ARTISTS || metadata.artists.some(({ id }) => id === artist.id))
                    }
                    onClick={() => updateArtist(artist)}
                  >
                    {t(resolvingArtist ? "art.linkArtist" : "art.useArtist", { name })}
                  </button>
                </li>
              );
            })}
          </ul>
        )}
      </section>

      <section aria-labelledby="create-artist-heading">
        <h3 id="create-artist-heading">{t("art.createHeading")}</h3>
        <div className="field-pair">
          <label className="field"><span>{t("art.nameKo")}</span><input value={nameKo} maxLength={200} disabled={disabled || creating} onChange={(event) => { setNameKo(event.target.value); createRequest.current = null; }} /></label>
          <label className="field"><span>{t("art.nameEn")}</span><input value={nameEn} maxLength={200} disabled={disabled || creating} onChange={(event) => { setNameEn(event.target.value); createRequest.current = null; }} /></label>
        </div>
        <button className="outlined-button" type="button" disabled={!canCreate} onClick={() => void createArtist()}>
          {t(creating ? "art.creating" : "art.create")}
        </button>
        {createFailed && !resolvingArtist && <p className="field-error" role="alert">{t("art.createFailed")}</p>}
      </section>

      <section aria-labelledby="art-terms-heading">
        <h3 id="art-terms-heading">{t("art.terms")}</h3>
        {terms === null && <p className="field-help" role="status">{t("art.termsUnavailable")}</p>}
        <div className="art-term-groups">
          {categories.map((category) => (
            <fieldset key={category}>
              <legend>{t(categoryKeys[category])}</legend>
              {(groupedTerms.get(category) ?? []).map((term) => (
                <label key={term.id}>
                  <input
                    type="checkbox"
                    checked={selectedTermIds.has(term.id)}
                    disabled={
                      interactionDisabled ||
                      (!selectedTermIds.has(term.id) && metadata.terms.length >= MAX_TERMS) ||
                      (!selectedTermIds.has(term.id) && metadata.terms.filter((selected) => selected.category === category).length >= MAX_TERMS_PER_CATEGORY)
                    }
                    onChange={(event) => toggleTerm(term, event.target.checked)}
                  />
                  <span>{localized(term.nameKo, term.nameEn, term.id)} · {localized(term.nameEn, term.nameKo)}</span>
                </label>
              ))}
            </fieldset>
          ))}
        </div>
      </section>
    </div>
  );
}
