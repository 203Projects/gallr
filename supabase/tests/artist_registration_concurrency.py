#!/usr/bin/env python3
"""Reservation replay and account quota races on a disposable local database."""
import concurrent.futures
import json
import os
import re
import subprocess
import threading

container = os.environ.get("SUPABASE_DB_CONTAINER", "")
if not re.fullmatch(r"supabase_db_[a-z0-9_-]+", container):
    raise SystemExit("Set SUPABASE_DB_CONTAINER to a disposable local test database")
endpoint = os.environ.get("DOCKER_HOST") or subprocess.check_output(
    ["docker", "context", "inspect", "--format", "{{.Endpoints.docker.Host}}"], text=True
).strip()
if not endpoint.startswith("unix://"):
    raise SystemExit("Only a local Unix-socket Docker engine is allowed")
environment = dict(os.environ)
environment.pop("DOCKER_CONTEXT", None)
base = ["docker", "--host", endpoint, "exec", "-i", container, "psql", "-XqAt",
        "-U", "postgres", "-d", "postgres", "-v", "ON_ERROR_STOP=1"]
actor = "00000000-0000-4000-8000-000000008751"


def query(sql, check=True):
    result = subprocess.run(base, input=sql, text=True, capture_output=True,
                            timeout=20, env=environment)
    if check and result.returncode:
        raise RuntimeError("Local artist concurrency query failed")
    return result


def race(requests):
    barrier = threading.Barrier(len(requests))

    def reserve(request):
        barrier.wait(timeout=10)
        return query("begin; set local statement_timeout='10s'; set local role authenticated; "
                     "select set_config('request.jwt.claims','{\"sub\":\"" + actor +
                     "\",\"role\":\"authenticated\"}',true); "
                     "select public.artist_reserve_registration('" + request +
                     "','image/png',100,'race.png'); commit;", check=False)

    with concurrent.futures.ThreadPoolExecutor(max_workers=len(requests)) as pool:
        return list(pool.map(reserve, requests))


if query("select count(*) from auth.users where id='" + actor + "';").stdout.strip() != "0":
    raise SystemExit("Artist race fixture already exists; select a fresh disposable target")
try:
    query("insert into auth.users(id,email,email_confirmed_at) values ('" + actor +
          "','artist-race@example.invalid',now());")
    request = "87500000-0000-4000-8000-000000000001"
    results = race([request, request])
    if any(result.returncode for result in results):
        raise RuntimeError("Concurrent reservation replay failed")
    receipts = [json.loads(next(line for line in result.stdout.splitlines()
                                if '"object_path"' in line)) for result in results]
    if receipts[0] != receipts[1]:
        raise RuntimeError("Concurrent replay returned different upload targets")
    if query("select count(*) from content.artist_registrations where user_id='" +
             actor + "';").stdout.strip() != "1":
        raise RuntimeError("Concurrent replay duplicated a reservation")
    print("PASS: simultaneous retries return one immutable reservation")

    query("delete from content.artist_registrations where user_id='" + actor + "';")
    requests = [f"87500000-0000-4000-8000-{index:012d}" for index in range(2, 6)]
    results = race(requests)
    if sum(result.returncode == 0 for result in results) != 3:
        raise RuntimeError("Concurrent reservations bypassed the account quota")
    failures = [result for result in results if result.returncode]
    if any("artist_registration_rate_limited" not in result.stderr for result in failures):
        raise RuntimeError("Quota race failed for an unexpected reason")
    if query("select count(*) from content.artist_registrations where user_id='" +
             actor + "';").stdout.strip() != "3":
        raise RuntimeError("Quota race did not retain exactly three reservations")
    print("PASS: four simultaneous requests retain exactly three upload reservations")
finally:
    query("delete from auth.users where id='" + actor + "';")
