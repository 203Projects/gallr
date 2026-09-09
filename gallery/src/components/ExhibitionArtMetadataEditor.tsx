import { useEffect, useMemo, useRef, useState } from "react";
import type {
  ArtistLookup,
  ArtTerm,
  ArtTermCategory,
  ExhibitionArtMetadata,
} from "../domain";
import { alternateBilingual, localizeBilingual, useLocale } from "../i18n";

const MAX_ARTISTS = 32;
const MAX_TERMS = 16;
const MAX_TERMS_PER_CATEGORY = 6;
const categories: ArtTermCategory[] = ["medium", "style", "theme", "mood"];

interface Props {
  metadata: ExhibitionArtMetadata | null;
  terms: ArtTerm[] | null;
  termsError?: boolean;
  disabled: boolean;
  onChange: (metadata: ExhibitionArtMetadata) => void;
  onSearchArtists: (query: string) => Promise<ArtistLookup[]>;
}

export function ExhibitionArtMetadataEditor({
  metadata,
  terms,
  termsError = false,
  disabled,
  onChange,
  onSearchArtists,
}: Props) {
  const { locale, messages } = useLocale();
  const copy = messages.exhibitions.art;
  const [query, setQuery] = useState("");
  const [results, setResults] = useState<ArtistLookup[]>([]);
  const [searching, setSearching] = useState(false);
  const [searchCompleted, setSearchCompleted] = useState(false);
  const [searchFailed, setSearchFailed] = useState(false);
  const [suggestionKo, setSuggestionKo] = useState("");
  const [suggestionEn, setSuggestionEn] = useState("");
  const searchGeneration = useRef(0);
  const metadataSupported = metadata !== null;
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
    const normalized = query.trim();
    const generation = ++searchGeneration.current;
    setSearchFailed(false);
    setSearchCompleted(false);
    if (normalized.length < 2 || disabled || !metadataSupported) {
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
    return () => window.clearTimeout(timer);
  }, [disabled, metadataSupported, onSearchArtists, query]);

  if (metadata === null) {
    return <p className="media-help" role="status">{copy.unsupported}</p>;
  }

  const artistName = (artist: { nameKo: string; nameEn: string }) =>
    localizeBilingual(artist.nameKo, artist.nameEn, locale) || artist.nameKo || artist.nameEn;

  const addCanonical = (artist: ArtistLookup) => {
    if (metadata.artists.some(({ id }) => id === artist.id) || metadata.artists.length >= MAX_ARTISTS) return;
    onChange({ ...metadata, artists: [...metadata.artists, artist] });
    setQuery("");
    setResults([]);
  };

  const addSuggestion = () => {
    const nameKo = suggestionKo.trim();
    const nameEn = suggestionEn.trim();
    if ((!nameKo && !nameEn) || metadata.artists.length >= MAX_ARTISTS) return;
    const key = `${nameKo.toLocaleLowerCase()}\u0000${nameEn.toLocaleLowerCase()}`;
    if (metadata.artists.some((artist) =>
      artist.id === null &&
      `${artist.nameKo.toLocaleLowerCase()}\u0000${artist.nameEn.toLocaleLowerCase()}` === key
    )) return;
    onChange({
      ...metadata,
      artists: [...metadata.artists, { id: null, nameKo, nameEn }],
    });
    setSuggestionKo("");
    setSuggestionEn("");
  };

  const move = (index: number, offset: -1 | 1) => {
    const target = index + offset;
    if (target < 0 || target >= metadata.artists.length) return;
    const artists = [...metadata.artists];
    [artists[index], artists[target]] = [artists[target], artists[index]];
    onChange({ ...metadata, artists });
  };

  const toggleTerm = (term: ArtTerm, checked: boolean) => {
    onChange({
      ...metadata,
      terms: checked
        ? [...metadata.terms, term]
        : metadata.terms.filter(({ id }) => id !== term.id),
    });
  };

  const categoryLabel: Record<ArtTermCategory, string> = {
    medium: copy.medium,
    style: copy.style,
    theme: copy.theme,
    mood: copy.mood,
  };

  return (
    <div className="art-metadata-editor">
      <p className="media-help">{copy.reviewHelp}</p>
      <section aria-labelledby="owner-artists-heading">
        <h3 id="owner-artists-heading">{copy.artists}</h3>
        {metadata.artists.length === 0 ? <p className="media-help">{copy.noArtists}</p> : (
          <ol className="artist-credit-list" aria-label={copy.orderedCredits}>
            {metadata.artists.map((artist, index) => {
              const name = artistName(artist);
              const alternate = alternateBilingual(artist.nameKo, artist.nameEn, locale);
              return (
                <li key={artist.id ?? `${artist.nameKo}\u0000${artist.nameEn}`}>
                  <div><strong>{name}</strong>{alternate && <span>{alternate}</span>}{artist.id === null && <span className="unresolved-label">{copy.unresolved}</span>}</div>
                  <div className="artist-credit-actions">
                    <button className="outlined-button compact-control" type="button" disabled={disabled || index === 0} aria-label={copy.moveUp(name)} onClick={() => move(index, -1)}>↑</button>
                    <button className="outlined-button compact-control" type="button" disabled={disabled || index === metadata.artists.length - 1} aria-label={copy.moveDown(name)} onClick={() => move(index, 1)}>↓</button>
                    <button className="text-button compact-control" type="button" disabled={disabled} aria-label={copy.remove(name)} onClick={() => onChange({ ...metadata, artists: metadata.artists.filter((_, itemIndex) => itemIndex !== index) })}>×</button>
                  </div>
                </li>
              );
            })}
          </ol>
        )}
        <label className="field">
          <span>{copy.search}</span>
          <input type="search" value={query} disabled={disabled} onChange={(event) => setQuery(event.target.value)} />
        </label>
        <div className="art-search-status" role="status" aria-live="polite">
          {searching ? copy.searching : searchFailed ? copy.searchFailed : searchCompleted && results.length === 0 ? copy.noMatches : ""}
        </div>
        {results.length > 0 && <ul className="artist-search-results">{results.map((artist) => {
          const name = artistName(artist);
          return <li key={artist.id}><span>{name}</span><button className="outlined-button" type="button" disabled={metadata.artists.length >= MAX_ARTISTS || metadata.artists.some(({ id }) => id === artist.id)} onClick={() => addCanonical(artist)}>{copy.useArtist(name)}</button></li>;
        })}</ul>}
      </section>

      <section aria-labelledby="artist-suggestion-heading">
        <h3 id="artist-suggestion-heading">{copy.suggestionHeading}</h3>
        <div className="field-pair">
          <label className="field"><span>{copy.suggestionKo}</span><input value={suggestionKo} maxLength={200} disabled={disabled} onChange={(event) => setSuggestionKo(event.target.value)} /></label>
          <label className="field"><span>{copy.suggestionEn}</span><input value={suggestionEn} maxLength={200} disabled={disabled} onChange={(event) => setSuggestionEn(event.target.value)} /></label>
        </div>
        <button className="outlined-button" type="button" disabled={disabled || metadata.artists.length >= MAX_ARTISTS || (!suggestionKo.trim() && !suggestionEn.trim())} onClick={addSuggestion}>{copy.addSuggestion}</button>
      </section>

      <section aria-labelledby="owner-art-terms-heading">
        <h3 id="owner-art-terms-heading">{copy.terms}</h3>
        {termsError && <p className="field-error" role="alert">{copy.loadFailed}</p>}
        <div className="art-term-groups">
          {categories.map((category) => (
            <fieldset key={category}>
              <legend>{categoryLabel[category]}</legend>
              {(groupedTerms.get(category) ?? []).map((term) => (
                <label key={term.id}>
                  <input
                    type="checkbox"
                    checked={selectedTermIds.has(term.id)}
                    disabled={
                      disabled ||
                      termsError ||
                      (!selectedTermIds.has(term.id) && metadata.terms.length >= MAX_TERMS) ||
                      (!selectedTermIds.has(term.id) && metadata.terms.filter((selected) => selected.category === category).length >= MAX_TERMS_PER_CATEGORY)
                    }
                    onChange={(event) => toggleTerm(term, event.target.checked)}
                  />
                  <span>{localizeBilingual(term.nameKo, term.nameEn, locale)} · {alternateBilingual(term.nameKo, term.nameEn, locale)}</span>
                </label>
              ))}
            </fieldset>
          ))}
        </div>
      </section>
    </div>
  );
}
