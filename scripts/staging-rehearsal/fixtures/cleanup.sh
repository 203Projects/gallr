#!/usr/bin/env bash
set -euo pipefail
set +x

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)
# shellcheck source=common.sh
source "$SCRIPT_DIR/common.sh"

fixture_validate_environment "fixture cleanup"

[[ -d "$FIXTURE_RUN_DIR" ]] || fixture_die "fixture evidence run does not exist"
[[ ! -L "$FIXTURE_RUN_DIR" ]] || fixture_die "fixture evidence run must not be a symbolic link"
[[ -O "$FIXTURE_RUN_DIR" ]] || fixture_die "run evidence directory ownership is invalid"
[[ "$(fixture_mode "$FIXTURE_RUN_DIR")" == "700" ]] ||
  fixture_die "run evidence directory must have mode 0700"

identity_path="$FIXTURE_RUN_DIR/identity.tsv"
baseline_path="$FIXTURE_RUN_DIR/baseline.tsv"
provisioned_tmp="$FIXTURE_RUN_DIR/provisioned.json.incomplete"
provisioned_path="$FIXTURE_RUN_DIR/provisioned.json"
manifest_tmp="$FIXTURE_RUN_DIR/manifest.json.incomplete"
manifest_path="$FIXTURE_RUN_DIR/manifest.json"
cleaned_tmp="$FIXTURE_RUN_DIR/cleaned.json.incomplete"
cleaned_path="$FIXTURE_RUN_DIR/cleaned.json"

if [[ -e "$manifest_path" || -L "$manifest_path" ]]; then
  [[ ! -e "$manifest_tmp" && ! -L "$manifest_tmp" ]] ||
    fixture_die "both final and incomplete manifest evidence exist"
  [[ -e "$provisioned_path" || -L "$provisioned_path" ]] ||
    fixture_die "provision database evidence is missing"
  [[ ! -e "$provisioned_tmp" && ! -L "$provisioned_tmp" ]] ||
    fixture_die "both final and incomplete provision evidence exist"
  fixture_read_identity "$identity_path" "400"
  fixture_read_baseline "$baseline_path"
  [[ "$(fixture_mode "$baseline_path")" == "400" ]] ||
    fixture_die "baseline evidence must have mode 0400 with a final manifest"
  fixture_validate_provisioned_evidence "$provisioned_path" "400"
  fixture_validate_manifest "$manifest_path" "$provisioned_path" "400" "$baseline_path"
  provision_source="$provisioned_path"
else
  fixture_read_identity "$identity_path" "400,600"
  fixture_read_baseline "$baseline_path"
  if [[ ( -e "$provisioned_path" || -L "$provisioned_path" ) &&
        ( -e "$provisioned_tmp" || -L "$provisioned_tmp" ) ]]; then
    fixture_die "both final and incomplete provision evidence exist"
  elif [[ -e "$provisioned_path" || -L "$provisioned_path" ]]; then
    provision_source="$provisioned_path"
  elif [[ -e "$provisioned_tmp" || -L "$provisioned_tmp" ]]; then
    provision_source="$provisioned_tmp"
  else
    fixture_die "provision database evidence is missing; guarded recovery is impossible"
  fi
  fixture_validate_provisioned_evidence "$provision_source" "400,600"
  if [[ -e "$manifest_tmp" || -L "$manifest_tmp" ]]; then
    fixture_validate_manifest "$manifest_tmp" "$provision_source" "400,600" "$baseline_path"
  fi
fi

cleaned_state=none
if [[ ( -e "$cleaned_path" || -L "$cleaned_path" ) &&
      ( -e "$cleaned_tmp" || -L "$cleaned_tmp" ) ]]; then
  fixture_die "both final and incomplete cleanup evidence exist"
elif [[ -e "$cleaned_path" || -L "$cleaned_path" ]]; then
  cleaned_state=final
  fixture_validate_cleaned_evidence "$cleaned_path" "400"
elif [[ -e "$cleaned_tmp" || -L "$cleaned_tmp" ]]; then
  cleaned_state=incomplete
  fixture_validate_cleaned_evidence "$cleaned_tmp" "400,600"
fi

fixture_finalize_provision_artifacts() {
  if [[ "$provision_source" == "$provisioned_tmp" ]]; then
    [[ ! -e "$provisioned_path" && ! -L "$provisioned_path" ]] ||
      fixture_die "refusing to overwrite final provision evidence"
    chmod 400 "$provisioned_tmp" || fixture_die "could not seal provision evidence"
    mv "$provisioned_tmp" "$provisioned_path" ||
      fixture_die "could not finalize provision evidence"
    provision_source="$provisioned_path"
  else
    chmod 400 "$provisioned_path" || fixture_die "could not seal provision evidence"
  fi

  if [[ ! -e "$manifest_path" && ! -L "$manifest_path" ]]; then
    if [[ ! -e "$manifest_tmp" && ! -L "$manifest_tmp" ]]; then
      fixture_build_manifest "$manifest_tmp" "$provisioned_path"
    fi
    fixture_validate_manifest "$manifest_tmp" "$provisioned_path" "400,600" "$baseline_path"
    chmod 400 "$manifest_tmp" || fixture_die "could not seal manifest evidence"
    mv "$manifest_tmp" "$manifest_path" || fixture_die "could not finalize manifest evidence"
  fi

  chmod 400 "$identity_path" "$baseline_path" "$provisioned_path" "$manifest_path" ||
    fixture_die "could not seal recovered fixture evidence"
  fixture_read_identity "$identity_path" "400"
  fixture_read_baseline "$baseline_path"
  [[ "$(fixture_mode "$baseline_path")" == "400" ]] ||
    fixture_die "recovered baseline evidence was not sealed"
  fixture_validate_provisioned_evidence "$provisioned_path" "400"
  fixture_validate_manifest "$manifest_path" "$provisioned_path" "400" "$baseline_path"
}

fixture_verify_cleaned_baseline() {
  local attempt=0
  local cleaned_evidence_path
  local verification_tmp
  local verification_path
  local status

  while (( attempt <= 100 )); do
    if (( attempt == 0 )); then
      verification_path="$FIXTURE_RUN_DIR/baseline-restore-verification.tsv"
    else
      printf -v verification_path \
        '%s/baseline-restore-verification-attempt-%03d.tsv' "$FIXTURE_RUN_DIR" "$attempt"
    fi
    verification_tmp="${verification_path}.incomplete"
    if [[ ! -e "$verification_path" && ! -L "$verification_path" &&
          ! -e "$verification_tmp" && ! -L "$verification_tmp" ]]; then
      break
    fi
    ((attempt += 1))
  done
  (( attempt <= 100 )) || fixture_die "too many retained baseline verification attempts"

  (set -o noclobber; : > "$verification_tmp") ||
    fixture_die "could not exclusively create baseline verification evidence"
  chmod 600 "$verification_tmp" || fixture_die "could not protect baseline verification evidence"
  if fixture_psql -Atq -f "$FIXTURE_SCRIPT_DIR/baseline.sql" > "$verification_tmp"; then
    status=0
  else
    status=$?
  fi
  if (( status != 0 )); then
    chmod 400 "$verification_tmp" 2>/dev/null || true
    fixture_die "read-only baseline restoration verification failed"
  fi
  if [[ "$cleaned_state" == "final" ]]; then
    cleaned_evidence_path="$cleaned_path"
  else
    cleaned_evidence_path="$cleaned_tmp"
  fi
  if ! fixture_assert_exact_baseline_restored \
    "$baseline_path" "$verification_tmp" "$cleaned_evidence_path"; then
    chmod 400 "$verification_tmp" 2>/dev/null || true
    fixture_die "cleanup evidence was retained because the exact baseline is not restored"
  fi
  chmod 400 "$verification_tmp" || fixture_die "could not seal baseline verification evidence"
  mv "$verification_tmp" "$verification_path" ||
    fixture_die "could not finalize baseline verification evidence"
}

identity_log="$FIXTURE_RUN_DIR/target-identity-cleanup.log"
if [[ -e "$identity_log" || -L "$identity_log" ]]; then
  identity_attempt=1
  while (( identity_attempt <= 100 )); do
    printf -v identity_log \
      '%s/target-identity-cleanup-attempt-%03d.log' "$FIXTURE_RUN_DIR" "$identity_attempt"
    [[ ! -e "$identity_log" && ! -L "$identity_log" ]] && break
    ((identity_attempt += 1))
  done
  (( identity_attempt <= 100 )) || fixture_die "too many retained target-identity attempts"
fi
fixture_assert_disposable_clone_target "$identity_log"

if [[ "$cleaned_state" != "none" ]]; then
  fixture_verify_cleaned_baseline
  fixture_finalize_provision_artifacts
  if [[ "$cleaned_state" == "incomplete" ]]; then
    chmod 400 "$cleaned_tmp" || fixture_die "could not seal cleanup evidence"
    mv "$cleaned_tmp" "$cleaned_path" || fixture_die "could not finalize cleanup evidence"
  fi
  printf 'Fixture cleanup evidence is finalized and the exact baseline is restored.\n'
  printf '  Cleanup evidence: %s\n' "$cleaned_path"
  exit 0
fi

printf 'Cleaning the exact %s-row fixture manifest from the fingerprinted staging target...\n' \
  "$FIXTURE_COUNT"
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
  -v "fixture_version_id_hash=$FIXTURE_VERSION_ID_HASH" \
  -v "fixture_media_id_hash=$FIXTURE_MEDIA_ID_HASH" \
  -v "fixture_attachment_id_hash=$FIXTURE_ATTACHMENT_ID_HASH" \
  -v "fixture_curation_id_hash=$FIXTURE_CURATION_ID_HASH" \
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
  -f "$FIXTURE_SCRIPT_DIR/cleanup.sql" > "$cleaned_tmp"
chmod 600 "$cleaned_tmp"
fixture_validate_cleaned_evidence "$cleaned_tmp" "600"
fixture_finalize_provision_artifacts
chmod 400 "$cleaned_tmp"
mv "$cleaned_tmp" "$cleaned_path"

printf 'Fixture cleanup passed; baseline count and hashes were restored.\n'
printf '  Cleanup evidence: %s\n' "$cleaned_path"
printf 'Audit count is non-decreasing; fixture SQL did not mutate audit rows, runtime state, or grants.\n'
