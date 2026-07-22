#!/usr/bin/env bash
set -euo pipefail
set +x

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)
# shellcheck source=common.sh
source "$SCRIPT_DIR/common.sh"

fixture_validate_environment "fixture provisioning"

[[ ! -e "$FIXTURE_RUN_DIR" && ! -L "$FIXTURE_RUN_DIR" ]] ||
  fixture_die "evidence run already exists; choose a new GALLR_FIXTURE_RUN_ID"

target_identity_root_log="$FIXTURE_EVIDENCE_ROOT/target-identity-fixture-${GALLR_FIXTURE_RUN_ID}-provision.log"
fixture_assert_disposable_clone_target "$target_identity_root_log"

mkdir -m 700 "$FIXTURE_RUN_DIR"
[[ -O "$FIXTURE_RUN_DIR" ]] || fixture_die "run evidence directory ownership is invalid"
[[ "$(fixture_mode "$FIXTURE_RUN_DIR")" == "700" ]] ||
  fixture_die "run evidence directory must have mode 0700"
mv "$target_identity_root_log" "$FIXTURE_RUN_DIR/target-identity-provision.log"

identity_tmp="$FIXTURE_RUN_DIR/identity.tsv.incomplete"
identity_path="$FIXTURE_RUN_DIR/identity.tsv"
baseline_tmp="$FIXTURE_RUN_DIR/baseline.tsv.incomplete"
baseline_path="$FIXTURE_RUN_DIR/baseline.tsv"
provisioned_tmp="$FIXTURE_RUN_DIR/provisioned.json.incomplete"
provisioned_path="$FIXTURE_RUN_DIR/provisioned.json"
manifest_tmp="$FIXTURE_RUN_DIR/manifest.json.incomplete"
manifest_path="$FIXTURE_RUN_DIR/manifest.json"

printf '%s\t%s\t%s\t%s\n' \
  "$GALLR_FIXTURE_RUN_ID" \
  "$FIXTURE_PREFIX" \
  "$FIXTURE_STAGING_REF_FINGERPRINT" \
  "$FIXTURE_PRODUCTION_REF_FINGERPRINT" > "$identity_tmp"
chmod 600 "$identity_tmp"
mv "$identity_tmp" "$identity_path"
fixture_read_identity "$identity_path" "600"

printf 'Capturing immutable baseline for the fingerprinted staging target...\n'
fixture_psql -Atq -f "$FIXTURE_SCRIPT_DIR/baseline.sql" > "$baseline_tmp"
chmod 600 "$baseline_tmp"
mv "$baseline_tmp" "$baseline_path"
fixture_read_baseline "$baseline_path"

printf 'Provisioning %s canonical published fixtures in one transaction...\n' "$FIXTURE_COUNT"
fixture_psql -Atq \
  -v "staging_ref_sha256=$FIXTURE_STAGING_REF_FINGERPRINT" \
  -v "production_ref_sha256=$FIXTURE_PRODUCTION_REF_FINGERPRINT" \
  -v "confirmation_ref_sha256=$FIXTURE_STAGING_REF_FINGERPRINT" \
  -v "run_id=$GALLR_FIXTURE_RUN_ID" \
  -v "fixture_prefix=$FIXTURE_PREFIX" \
  -v "load_event_id=$FIXTURE_LOAD_EVENT_ID" \
  -v "empty_event_id=$FIXTURE_EMPTY_EVENT_ID" \
  -v "editor_id=$FIXTURE_EDITOR_ID" \
  -v "boundary_id=$FIXTURE_BOUNDARY_ID" \
  -v "mutation_id=$FIXTURE_MUTATION_ID" \
  -v "media_object_path=$FIXTURE_MEDIA_OBJECT_PATH" \
  -v "baseline_canonical_count=$BASELINE_CANONICAL_COUNT" \
  -v "baseline_canonical_id_hash=$BASELINE_CANONICAL_ID_HASH" \
  -v "baseline_canonical_catalog_hash=$BASELINE_CANONICAL_CATALOG_HASH" \
  -v "baseline_version_count=$BASELINE_VERSION_COUNT" \
  -v "baseline_version_catalog_hash=$BASELINE_VERSION_CATALOG_HASH" \
  -v "baseline_v2_count=$BASELINE_V2_COUNT" \
  -v "baseline_v2_id_hash=$BASELINE_V2_ID_HASH" \
  -v "baseline_v2_catalog_hash=$BASELINE_V2_CATALOG_HASH" \
  -v "baseline_legacy_count=$BASELINE_LEGACY_COUNT" \
  -v "baseline_legacy_id_hash=$BASELINE_LEGACY_ID_HASH" \
  -v "baseline_legacy_catalog_hash=$BASELINE_LEGACY_CATALOG_HASH" \
  -v "baseline_event_count=$BASELINE_EVENT_COUNT" \
  -v "baseline_event_catalog_hash=$BASELINE_EVENT_CATALOG_HASH" \
  -v "baseline_editor_count=$BASELINE_EDITOR_COUNT" \
  -v "baseline_editor_catalog_hash=$BASELINE_EDITOR_CATALOG_HASH" \
  -v "baseline_media_count=$BASELINE_MEDIA_COUNT" \
  -v "baseline_media_catalog_hash=$BASELINE_MEDIA_CATALOG_HASH" \
  -v "baseline_attachment_count=$BASELINE_ATTACHMENT_COUNT" \
  -v "baseline_attachment_catalog_hash=$BASELINE_ATTACHMENT_CATALOG_HASH" \
  -v "baseline_curation_count=$BASELINE_CURATION_COUNT" \
  -v "baseline_curation_catalog_hash=$BASELINE_CURATION_CATALOG_HASH" \
  -v "baseline_submission_count=$BASELINE_SUBMISSION_COUNT" \
  -v "baseline_submission_catalog_hash=$BASELINE_SUBMISSION_CATALOG_HASH" \
  -v "baseline_submission_media_count=$BASELINE_SUBMISSION_MEDIA_COUNT" \
  -v "baseline_submission_media_catalog_hash=$BASELINE_SUBMISSION_MEDIA_CATALOG_HASH" \
  -v "baseline_import_row_count=$BASELINE_IMPORT_ROW_COUNT" \
  -v "baseline_import_row_catalog_hash=$BASELINE_IMPORT_ROW_CATALOG_HASH" \
  -v "baseline_import_link_count=$BASELINE_IMPORT_LINK_COUNT" \
  -v "baseline_import_link_catalog_hash=$BASELINE_IMPORT_LINK_CATALOG_HASH" \
  -v "baseline_audit_count=$BASELINE_AUDIT_COUNT" \
  -f "$FIXTURE_SCRIPT_DIR/provision.sql" > "$provisioned_tmp"
chmod 600 "$provisioned_tmp"
fixture_validate_provisioned_evidence "$provisioned_tmp" "600"
mv "$provisioned_tmp" "$provisioned_path"

fixture_build_manifest "$manifest_tmp" "$provisioned_path"
fixture_validate_manifest "$manifest_tmp" "$provisioned_path" "600" "$baseline_path"
mv "$manifest_tmp" "$manifest_path"
chmod 400 "$identity_path" "$baseline_path" "$provisioned_path" "$manifest_path"

printf 'Fixture provisioning passed.\n'
printf '  Evidence manifest: %s\n' "$manifest_path"
printf '  Event filter:      %s\n' "$FIXTURE_LOAD_EVENT_ID"
printf '  Empty event:       %s\n' "$FIXTURE_EMPTY_EVENT_ID"
printf '  Boundary cursor:   %s\n' "$FIXTURE_BOUNDARY_ID"
printf '  Mutation target:   %s\n' "$FIXTURE_MUTATION_ID"
printf 'No Storage object bytes were created. Keep this run ID for guarded cleanup.\n'
