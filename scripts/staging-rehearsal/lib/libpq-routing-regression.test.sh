#!/usr/bin/env bash

# Prove with the real libpq client that a URI-valued PGDATABASE is a fallback
# database name, while the validated split environment selects the requested
# TCP host and port. This test uses only loopback port 1 and no real credential.

set -euo pipefail
umask 077

command -v psql >/dev/null 2>&1 || {
  printf 'SKIP: psql is unavailable for the libpq routing regression\n'
  exit 0
}

test_root=$(mktemp -d "${TMPDIR:-/tmp}/gallr-libpq-routing.XXXXXX")
case "$test_root" in
  "${TMPDIR:-/tmp}"/gallr-libpq-routing.*) ;;
  *) printf 'unexpected temporary path\n' >&2; exit 1 ;;
esac
trap 'rm -rf -- "$test_root"' EXIT HUP INT TERM

psql_path=$(command -v psql)
path_value=$(dirname "$psql_path"):/usr/bin:/bin
test_uri='postgresql://postgres:test@127.0.0.1:1/postgres?sslmode=disable'

set +e
env -i \
  PATH="$path_value" \
  PGCONNECT_TIMEOUT=1 \
  PGDATABASE="$test_uri" \
  "$psql_path" -X --no-password -c 'select 1' \
  >"$test_root/uri.stdout" 2>"$test_root/uri.stderr"
uri_status=$?

env -i \
  PATH="$path_value" \
  PGCONNECT_TIMEOUT=1 \
  PGHOST=127.0.0.1 \
  PGPORT=1 \
  PGDATABASE=postgres \
  PGUSER=postgres \
  PGSSLMODE=disable \
  "$psql_path" -X --no-password -c 'select 1' \
  >"$test_root/split.stdout" 2>"$test_root/split.stderr"
split_status=$?
set -e

[[ "$uri_status" -ne 0 && "$split_status" -ne 0 ]] || {
  printf 'routing probe unexpectedly connected to a database\n' >&2
  exit 1
}

grep -Eq 'socket|postgresql://postgres:test@127\.0\.0\.1:1' \
  "$test_root/uri.stderr" || {
  printf 'URI-valued PGDATABASE did not exhibit fallback database routing\n' >&2
  exit 1
}
! grep -Eq 'server at "?127\.0\.0\.1"?, port 1|host "?127\.0\.0\.1"?' \
  "$test_root/uri.stderr" || {
  printf 'URI-valued PGDATABASE unexpectedly selected its URI host\n' >&2
  exit 1
}
grep -Eq 'server at "?127\.0\.0\.1"?, port 1|host "?127\.0\.0\.1"?' \
  "$test_root/split.stderr" || {
  printf 'split libpq variables did not select loopback port 1\n' >&2
  exit 1
}

printf 'real libpq routing regression passed\n'
