"use strict";

class ExhibitionReaderSourceConfigurationError extends Error {
  constructor(value) {
    super(
      `invalid GALLR_EXHIBITION_SOURCE ${JSON.stringify(value)}; ` +
      `expected "legacy" or "canonical-v2"`
    );
    this.name = "ExhibitionReaderSourceConfigurationError";
  }
}

const LEGACY_EXHIBITION_READER_SOURCE = Object.freeze({
  name: "legacy",
  resource: "exhibitions",
  integrityRpc: "exhibition_reader_integrity",
  integrityMode: "id-only",
});

const CANONICAL_V2_EXHIBITION_READER_SOURCE = Object.freeze({
  name: "canonical-v2",
  resource: "exhibition_catalog_v2",
  integrityRpc: "exhibition_catalog_v2_integrity",
  integrityMode: "id-and-content",
});

const SOURCES = Object.freeze({
  [LEGACY_EXHIBITION_READER_SOURCE.name]: LEGACY_EXHIBITION_READER_SOURCE,
  [CANONICAL_V2_EXHIBITION_READER_SOURCE.name]: CANONICAL_V2_EXHIBITION_READER_SOURCE,
});

function resolveExhibitionReaderSource(value = process.env.GALLR_EXHIBITION_SOURCE) {
  if (value === undefined || value === null) return LEGACY_EXHIBITION_READER_SOURCE;
  if (typeof value !== "string") throw new ExhibitionReaderSourceConfigurationError(value);

  const normalized = value.trim();
  if (normalized === "") return LEGACY_EXHIBITION_READER_SOURCE;

  const source = SOURCES[normalized];
  if (!source) throw new ExhibitionReaderSourceConfigurationError(value);
  return source;
}

function assertExhibitionReaderSource(source) {
  if (
    source !== LEGACY_EXHIBITION_READER_SOURCE &&
    source !== CANONICAL_V2_EXHIBITION_READER_SOURCE
  ) {
    throw new ExhibitionReaderSourceConfigurationError(
      source && typeof source === "object" ? source.name : source
    );
  }
  return source;
}

module.exports = {
  CANONICAL_V2_EXHIBITION_READER_SOURCE,
  ExhibitionReaderSourceConfigurationError,
  LEGACY_EXHIBITION_READER_SOURCE,
  assertExhibitionReaderSource,
  resolveExhibitionReaderSource,
};
