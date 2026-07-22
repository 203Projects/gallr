#!/usr/bin/env node

const [action, ...args] = process.argv.slice(2);
const hash = "a".repeat(64);

if (action === "baseline") {
  const firstCount = process.env.FAKE_BASELINE_DRIFT === "1" ? "1" : "0";
  const fields = [
    firstCount, hash, hash, "0", hash,
    "0", hash, hash, "0", hash, hash,
    "0", hash, "0", hash, "0", hash, "0", hash, "0", hash,
    "0", hash, "0", hash, "0", hash, "0", hash,
    "0", "2026-07-21T00:00:00.000000Z",
  ];
  process.stdout.write(`${fields.join("\t")}\n`);
  process.exit(0);
}

if (action === "provision") {
  const [prefix, loadEventId, emptyEventId, editorId, boundaryId, mutationId, mediaObjectPath] = args;
  const exhibitionIds = Array.from({ length: 1205 }, (_, offset) => {
    const ordinal = offset + 1;
    if (ordinal === 500) return boundaryId;
    if (ordinal === 750) return mutationId;
    return `${prefix}catalog-${String(ordinal).padStart(4, "0")}`;
  });
  const fixtureVersionIds = Array.from(
    { length: 1205 },
    (_, offset) => `00000000-0000-4000-8000-${(offset + 1).toString(16).padStart(12, "0")}`,
  );
  const evidence = {
    state: "provisioned",
    captured_at: "2026-07-21T00:01:00.000000Z",
    fixture_count: 1205,
    featured_count: 5,
    homepage_count: 4,
    load_event_id: loadEventId,
    empty_event_id: emptyEventId,
    boundary_cursor_id: boundaryId,
    mutation_target_id: mutationId,
    editor_id: editorId,
    media_object_path: mediaObjectPath,
    storage_bytes_created: false,
    runtime: { legacy_mirror_enabled: true },
    event_integrity: { row_count: 1205 },
    featured_integrity: { row_count: 5 },
    empty_integrity: { row_count: 0 },
    reconciliation: { in_sync: true },
    fixture_version_id_checksum_sha256: hash,
    fixture_media_id_checksum_sha256: hash,
    fixture_attachment_id_checksum_sha256: hash,
    fixture_curation_id_checksum_sha256: hash,
    fixture_exhibition_ids: exhibitionIds,
    fixture_version_ids: fixtureVersionIds,
    fixture_media_asset_ids: ["00000000-0000-4000-8000-000000000501"],
    fixture_curation_ids: [
      "00000000-0000-4000-8000-000000000601",
      "00000000-0000-4000-8000-000000000602",
    ],
  };
  process.stdout.write(`${JSON.stringify(evidence)}\n`);
  process.exit(0);
}

if (action === "cleanup") {
  const evidence = {
    state: "cleaned",
    captured_at: "2026-07-21T00:02:00.000000Z",
    fixture_count: 1205,
    baseline_restored: true,
    audit_rows_retained: 0,
    deleted: {
      canonical_exhibitions: 1205,
      curation_placements: 2,
      editors: 1,
      events: 2,
      exhibition_versions: 1205,
      media_assets: 1,
      publication_pointers: 1205,
      version_media: 1,
    },
    v2_integrity: { in_sync: true },
    legacy_integrity: { in_sync: true },
    reconciliation: { in_sync: true },
  };
  process.stdout.write(`${JSON.stringify(evidence)}\n`);
  process.exit(0);
}

process.stderr.write(`unsupported fake evidence action: ${action}\n`);
process.exit(2);
