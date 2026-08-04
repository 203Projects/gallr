#!/usr/bin/env bash
set -euo pipefail

if [[ $- == *x* ]]; then
  set +x
fi
if [[ $- == *a* ]]; then
  set +a
fi

fail() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 1
}

require_env() {
  local name="$1"
  [[ -n "${!name:-}" ]] || fail "${name} is required"
}

sha256_text() {
  if command -v shasum >/dev/null 2>&1; then
    printf '%s' "$1" | shasum -a 256 | awk '{print $1}'
  elif command -v sha256sum >/dev/null 2>&1; then
    printf '%s' "$1" | sha256sum | awk '{print $1}'
  else
    fail 'shasum or sha256sum is required'
  fi
}

sha256_file() {
  if command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$1" | awk '{print $1}'
  elif command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  else
    fail 'shasum or sha256sum is required'
  fi
}

require_env GALLR_EXPECTED_STAGING_PROJECT_REF
require_env GALLR_PRODUCTION_PROJECT_REF
require_env GALLR_STAGING_REHEARSAL_CONFIRM
require_env GALLR_STAGING_EVIDENCE_DIR

unset staging_ref production_ref staging_confirmation evidence_dir_input
staging_ref="${GALLR_EXPECTED_STAGING_PROJECT_REF}"
production_ref="${GALLR_PRODUCTION_PROJECT_REF}"
staging_confirmation="${GALLR_STAGING_REHEARSAL_CONFIRM}"
evidence_dir_input="${GALLR_STAGING_EVIDENCE_DIR}"
unset GALLR_EXPECTED_STAGING_PROJECT_REF GALLR_PRODUCTION_PROJECT_REF
unset GALLR_STAGING_REHEARSAL_CONFIRM GALLR_STAGING_EVIDENCE_DIR

# This guard never needs database or API credentials. Its callers may have
# exported them for a later phase, so remove every known credential-bearing
# variable before invoking hashing, text-processing, or Git subprocesses.
unset GALLR_STAGING_DATABASE_URL DATABASE_URL
unset SUPABASE_ACCESS_TOKEN SUPABASE_URL SUPABASE_ANON_KEY
unset SUPABASE_SERVICE_ROLE_KEY SUPABASE_SECRET_KEY
unset GALLR_SERVICE_ROLE_KEY
unset PGPASSFILE PGPASSWORD PGSERVICE PGSERVICEFILE
unset NODE_OPTIONS NODE_PATH NODE_DEBUG NODE_DEBUG_NATIVE
unset NODE_EXTRA_CA_CERTS NODE_TLS_REJECT_UNAUTHORIZED NODE_USE_ENV_PROXY
unset LD_PRELOAD LD_LIBRARY_PATH LD_AUDIT LD_DEBUG LD_PROFILE GLIBC_TUNABLES
unset DYLD_FRAMEWORK_PATH DYLD_FALLBACK_FRAMEWORK_PATH
unset DYLD_LIBRARY_PATH DYLD_FALLBACK_LIBRARY_PATH DYLD_INSERT_LIBRARIES
unset SSL_CERT_FILE SSL_CERT_DIR SSLKEYLOGFILE
unset HTTP_PROXY HTTPS_PROXY ALL_PROXY NO_PROXY
unset http_proxy https_proxy all_proxy no_proxy

unset GIT_DIR GIT_WORK_TREE GIT_INDEX_FILE GIT_OBJECT_DIRECTORY
unset GIT_ALTERNATE_OBJECT_DIRECTORIES GIT_COMMON_DIR GIT_CEILING_DIRECTORIES
unset GIT_DISCOVERY_ACROSS_FILESYSTEM GIT_CONFIG GIT_CONFIG_PARAMETERS
unset GIT_CONFIG_SYSTEM
export GIT_CONFIG_COUNT=0 GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_NOSYSTEM=1
export GIT_OPTIONAL_LOCKS=0
safe_git() {
  command git \
    --no-replace-objects \
    -c core.fsmonitor=false \
    -c core.hooksPath=/dev/null \
    -c core.excludesFile=/dev/null \
    -c core.attributesFile=/dev/null \
    "$@"
}

project_ref_pattern='^[a-z0-9]{20}$'
[[ "${staging_ref}" =~ ${project_ref_pattern} ]] \
  || fail 'expected staging project ref must be 20 lowercase alphanumeric characters'
[[ "${production_ref}" =~ ${project_ref_pattern} ]] \
  || fail 'production project ref must be 20 lowercase alphanumeric characters'
[[ "${staging_ref}" != "${production_ref}" ]] \
  || fail 'staging and production project references must differ'
[[ "${staging_confirmation}" == "${staging_ref}" ]] \
  || fail 'confirmation must exactly match the expected staging project ref'

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
expected_repo_root="$(cd -- "${script_dir}/../.." && pwd -P)"
repo_root="$(safe_git -C "${script_dir}" rev-parse --show-toplevel 2>/dev/null)" \
  || fail 'could not resolve the repository root'
repo_root="$(cd -- "${repo_root}" && pwd -P)"
[[ "${repo_root}" == "${expected_repo_root}" ]] \
  || fail 'Git repository root does not match the checked-in guard location'

[[ "${evidence_dir_input}" = /* ]] \
  || fail 'evidence directory must be an absolute path'
[[ -d "${evidence_dir_input}" ]] \
  || fail 'evidence directory does not exist'
[[ ! -L "${evidence_dir_input}" ]] \
  || fail 'evidence directory must not be a symbolic link'
evidence_dir="$(cd -- "${evidence_dir_input}" && pwd -P)"
case "${evidence_dir}" in
  "${repo_root}"|"${repo_root}/"*)
    fail 'evidence directory must be outside the repository'
    ;;
esac

if evidence_mode="$(stat -f '%Lp' "${evidence_dir}" 2>/dev/null)"; then
  :
elif evidence_mode="$(stat -c '%a' "${evidence_dir}" 2>/dev/null)"; then
  :
else
  fail 'could not inspect evidence directory permissions'
fi
[[ "${evidence_mode}" == '700' ]] \
  || fail 'evidence directory permissions must be 0700'

manifest_path="${evidence_dir}/operator-manifest.txt"
[[ -f "${manifest_path}" && ! -L "${manifest_path}" ]] \
  || fail 'operator manifest is missing or is a symbolic link'
[[ -O "${manifest_path}" ]] || fail 'operator manifest must be owned by the current user'
if manifest_mode="$(stat -f '%Lp' "${manifest_path}" 2>/dev/null)"; then
  :
elif manifest_mode="$(stat -c '%a' "${manifest_path}" 2>/dev/null)"; then
  :
else
  fail 'could not inspect operator manifest permissions'
fi
(( (8#${manifest_mode} & 8#222) == 0 )) \
  || fail 'operator manifest must not be writable'
[[ "$(grep -Fxc 'production_target_mode=staging_rehearsal' "${manifest_path}")" -eq 1 ]] \
  || fail 'production-pair manifests cannot authorize staging remote access'

staging_ref_sha256="$(sha256_text "${staging_ref}")"
production_ref_sha256="$(sha256_text "${production_ref}")"
[[ "$(grep -Fxc "staging_project_ref_sha256=${staging_ref_sha256}" "${manifest_path}")" -eq 1 ]] \
  || fail 'staging project ref does not match the operator manifest'
[[ "$(grep -Fxc "production_project_ref_sha256=${production_ref_sha256}" "${manifest_path}")" -eq 1 ]] \
  || fail 'production project ref does not match the operator manifest'

head_commit="$(safe_git -C "${repo_root}" rev-parse HEAD)"
[[ "$(grep -Fxc "repository_commit=${head_commit}" "${manifest_path}")" -eq 1 ]] \
  || fail 'current commit does not match the operator manifest'

production_ref_anchor_sha256="$(
  safe_git -C "${repo_root}" show \
    "${head_commit}:scripts/staging-rehearsal/production-project-ref.sha256"
)" || fail 'could not read the production project-ref trust anchor from the reviewed commit'
compatibility_ref_anchor_sha256="$(
  safe_git -C "${repo_root}" show \
    "${head_commit}:scripts/staging-rehearsal/legacy-compatibility-project-ref.sha256"
)" || fail 'could not read the compatibility project-ref trust anchor from the reviewed commit'
[[ "${production_ref_anchor_sha256}" =~ ^[0-9a-f]{64}$ ]] \
  || fail 'production project-ref trust anchor must be one lowercase SHA-256 digest'
[[ "${compatibility_ref_anchor_sha256}" =~ ^[0-9a-f]{64}$ ]] \
  || fail 'compatibility project-ref trust anchor must be one lowercase SHA-256 digest'
[[ "${production_ref_anchor_sha256}" != "${compatibility_ref_anchor_sha256}" ]] \
  || fail 'production and compatibility project-ref trust anchors must be distinct'
[[ "$(sha256_text "${production_ref}")" == "${production_ref_anchor_sha256}" ]] \
  || fail 'production project ref does not match the reviewed production trust anchor'
[[ "$(sha256_text "${staging_ref}")" != "${production_ref_anchor_sha256}" ]] \
  || fail 'expected staging project ref resolves to the reviewed production project'
[[ "$(sha256_text "${staging_ref}")" != "${compatibility_ref_anchor_sha256}" ]] \
  || fail 'expected staging project ref resolves to the reviewed compatibility production project'

index_flag_records="$(safe_git -C "${repo_root}" ls-files -v)" \
  || fail 'could not inspect tracked-file index flags'
while IFS= read -r index_flag_record; do
  [[ "${index_flag_record}" == 'H '* ]] \
    || fail 'assume-unchanged, skip-worktree, or nonstandard index flags are forbidden'
done <<< "${index_flag_records}"

verify_checkout_matches_head_without_filters() {
  local index_raw head_raw index_records head_records
  local tab record metadata relative_path mode expected_blob worktree_path
  local path_list expected_blobs worktree_blobs worktree_blob untracked_paths

  index_raw="$(safe_git -C "${repo_root}" ls-files --stage)" \
    || fail 'could not inspect the complete Git index'
  head_raw="$(safe_git -C "${repo_root}" ls-tree -r "${head_commit}")" \
    || fail 'could not inspect the reviewed commit tree'
  [[ -n "${index_raw}" && -n "${head_raw}" ]] \
    || fail 'the reviewed checkout is unexpectedly empty'

  index_records="$(
    printf '%s\n' "${index_raw}" |
      awk -F '\t' '
        NF != 2 { exit 1 }
        {
          field_count = split($1, fields, " ")
          if (field_count != 3 ||
              (fields[1] != "100644" && fields[1] != "100755") ||
              fields[2] !~ /^[0-9a-f]+$/ || fields[3] != "0") {
            exit 1
          }
          print fields[1] " " fields[2] "\t" $2
        }
      '
  )" || fail 'Git index contains an unsupported entry'
  head_records="$(
    printf '%s\n' "${head_raw}" |
      awk -F '\t' '
        NF != 2 { exit 1 }
        {
          field_count = split($1, fields, " ")
          if (field_count != 3 ||
              (fields[1] != "100644" && fields[1] != "100755") ||
              fields[2] != "blob" || fields[3] !~ /^[0-9a-f]+$/) {
            exit 1
          }
          print fields[1] " " fields[3] "\t" $2
        }
      '
  )" || fail 'reviewed commit contains an unsupported entry'

  [[ "${index_records}" == "${head_records}" ]] \
    || fail 'checkout paths, index modes, or index blobs differ from the reviewed commit'

  tab=$'\t'
  path_list=
  expected_blobs=
  while IFS= read -r record; do
    [[ -n "${record}" ]] || continue
    metadata=${record%%"${tab}"*}
    relative_path=${record#*"${tab}"}
    mode=${metadata%% *}
    expected_blob=${metadata#* }
    case "${relative_path}" in
      \"*|*\\*)
        fail 'checkout contains a path requiring unsupported Git quoting'
        ;;
    esac
    worktree_path="${repo_root}/${relative_path}"
    [[ -f "${worktree_path}" && ! -L "${worktree_path}" ]] \
      || fail "tracked artifact is missing or is not a regular file: ${relative_path}"
    case "${mode}" in
      100644)
        [[ ! -x "${worktree_path}" ]] \
          || fail "tracked artifact executable mode differs from the reviewed commit: ${relative_path}"
        ;;
      100755)
        [[ -x "${worktree_path}" ]] \
          || fail "tracked artifact executable mode differs from the reviewed commit: ${relative_path}"
        ;;
    esac
    if [[ -n "${path_list}" ]]; then
      path_list+=$'\n'"${relative_path}"
      expected_blobs+=$'\n'"${expected_blob}"
    else
      path_list=${relative_path}
      expected_blobs=${expected_blob}
    fi
  done <<< "${index_records}"

  worktree_blobs="$(
    printf '%s\n' "${path_list}" |
      safe_git -C "${repo_root}" hash-object --no-filters --stdin-paths
  )" || fail 'could not hash checkout bytes without Git filters'

  if [[ "${worktree_blobs}" != "${expected_blobs}" ]]; then
    while IFS= read -r record; do
      [[ -n "${record}" ]] || continue
      metadata=${record%%"${tab}"*}
      relative_path=${record#*"${tab}"}
      expected_blob=${metadata#* }
      worktree_blob="$(
        safe_git -C "${repo_root}" hash-object --no-filters -- \
          "${repo_root}/${relative_path}"
      )" || fail "could not hash tracked artifact bytes: ${relative_path}"
      [[ "${worktree_blob}" == "${expected_blob}" ]] \
        || fail "tracked artifact bytes differ from the reviewed commit: ${relative_path}"
    done <<< "${index_records}"
    fail 'checkout bytes differ from the reviewed commit'
  fi

  untracked_paths="$(
    safe_git -C "${repo_root}" ls-files --others --exclude-standard
  )" || fail 'could not inspect untracked checkout files'
  [[ -z "${untracked_paths}" ]] \
    || fail 'the staging rehearsal checkout contains untracked files'
}

verify_checkout_matches_head_without_filters

manifest_migration_count=$(awk -F= '
  $1 == "migration_count" { print $2 }
' "${manifest_path}")
[[ "${manifest_migration_count}" =~ ^[0-9]+$ ]] \
  || fail 'operator manifest migration count is missing or invalid'

manifest_migration_entries=$(awk '
  /^\[migration_sha256\]$/ { in_section = 1; next }
  /^\[/ && in_section { exit }
  in_section && NF { print }
' "${manifest_path}")
[[ -n "${manifest_migration_entries}" ]] \
  || fail 'operator manifest contains no migration hashes'

manifest_paths=''
manifest_entry_count=0
while IFS= read -r migration_entry; do
  [[ "${migration_entry}" =~ ^([0-9a-f]{64})\ \ (supabase/migrations/[A-Za-z0-9._-]+\.sql)$ ]] \
    || fail 'operator manifest contains an invalid migration hash entry'
  expected_sha256="${BASH_REMATCH[1]}"
  relative_migration="${BASH_REMATCH[2]}"
  migration_path="${repo_root}/${relative_migration}"
  [[ -f "${migration_path}" && ! -L "${migration_path}" ]] \
    || fail "manifest migration is missing or is a symbolic link: ${relative_migration}"
  [[ "$(sha256_file "${migration_path}")" == "${expected_sha256}" ]] \
    || fail "migration bytes differ from the operator manifest: ${relative_migration}"
  manifest_paths+="${relative_migration}"$'\n'
  manifest_entry_count=$((manifest_entry_count + 1))
done <<< "${manifest_migration_entries}"

[[ "${manifest_entry_count}" -eq "${manifest_migration_count}" ]] \
  || fail 'operator manifest migration count does not match its hash entries'

manifest_paths=$(printf '%s' "${manifest_paths}" | LC_ALL=C sort)
working_tree_paths=$(
  find "${repo_root}/supabase/migrations" -maxdepth 1 -type f -name '*.sql' -print \
    | sed "s#^${repo_root}/##" \
    | LC_ALL=C sort
)
[[ "${manifest_paths}" == "${working_tree_paths}" ]] \
  || fail 'working-tree migration file set differs from the operator manifest'

linked_ref_path="${repo_root}/supabase/.temp/project-ref"
[[ -f "${linked_ref_path}" && ! -L "${linked_ref_path}" ]] \
  || fail 'linked project ref file is missing or is a symbolic link'
[[ "$(awk 'END { print NR }' "${linked_ref_path}")" -eq 1 ]] \
  || fail 'linked project ref file must contain exactly one line'
linked_ref="$(sed -n '1p' "${linked_ref_path}")"
[[ "${linked_ref}" =~ ${project_ref_pattern} ]] \
  || fail 'linked project ref has an invalid format'
[[ "${linked_ref}" == "${staging_ref}" ]] \
  || fail 'linked project is not the approved staging project'
[[ "${linked_ref}" != "${production_ref}" ]] \
  || fail 'linked project resolves to production'

printf 'PASS: linked project matches the reviewed staging manifest\n'
