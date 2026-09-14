#!/usr/bin/env python3
"""Two-session regression on an explicitly selected disposable local database.

SUPABASE_DB_CONTAINER must name the local QA container. Fixtures are removed after the race: staff and owner race for the same review row without provider calls.
"""
import os
from pathlib import Path
import subprocess
import time

container = os.environ.get("SUPABASE_DB_CONTAINER", "")
if not container.startswith("supabase_db_"):
    raise SystemExit("Set SUPABASE_DB_CONTAINER to the disposable local test database")
base = ["docker", "exec", "-i", container, "psql", "-X", "-qAt", "-U", "postgres", "-d", "postgres", "-v", "ON_ERROR_STOP=1"]

def query(sql):
    result = subprocess.run(base, input=sql, text=True, capture_output=True, timeout=20)
    if result.returncode:
        raise RuntimeError(result.stderr)
    return result.stdout.strip()

# Reuse the contract suite's owner, gallery, complete draft, media and review
# fixture, ending before its first assertion. Fixed IDs are scoped to this test.
contract = Path(__file__).with_name("database").joinpath("042_owner_submission_control.test.sql").read_text()
fixture = contract[contract.index("insert into auth.users"):contract.index("set local role authenticated;")]
owner = "set local role authenticated; select set_config('request.jwt.claims','{\"sub\":\"00000000-0000-0000-0000-000000002501\",\"role\":\"authenticated\"}',true);"
# Run only against the explicitly selected disposable local database. Fixed
# fixture identities are checked first and removed in dependency order below.
if query("select count(*) from auth.users where id::text like '00000000-0000-0000-0000-00000000250%'") != "0":
    raise SystemExit("QA fixture already exists; use a fresh disposable database")
children = []
try:
    query(fixture)
    query("insert into content.staff_members(user_id,role,active) values ('00000000-0000-0000-0000-000000002501','publisher',true);")
    for winner in ("owner", "staff"):
        if winner == "staff":
            query("update content.exhibitions set owner_status='submitted' where id='hide-submitted'; update content.exhibition_submissions set status='in_review',reviewed_at=null,reviewed_by=null,accepted_exhibition_id=null where id='25300000-0000-0000-0000-000000000001'; update content.exhibition_versions set revision=4 where id='25200000-0000-0000-0000-000000000002';")
        command_owner = "select public.owner_withdraw_exhibition('hide-submitted','25200000-0000-0000-0000-000000000002',4,'25400000-0000-0000-0000-000000000099');"
        command_staff = "select content_private.admin_accept_submission_impl('25300000-0000-0000-0000-000000000001');"
        first_command, second_command = (command_owner, command_staff) if winner == "owner" else (command_staff, command_owner)
        # Hold the winning transaction open after its command has completed.
        first = subprocess.Popen(base, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        children.append(first)
        first.stdin.write("begin; set local statement_timeout='10s'; " + owner + first_command + "select 'winner_ready';\n")
        first.stdin.flush()
        while first.stdout.readline().strip() != "winner_ready":
            if first.poll() is not None:
                raise RuntimeError(first.stderr.read())
        second_sql = "begin; set local statement_timeout='10s'; set local application_name='owner_submission_loser'; " + owner + second_command + "commit;"
        second = subprocess.Popen(base, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        children.append(second)
        second.stdin.write(second_sql + "\n")
        second.stdin.close()
        deadline = time.monotonic() + 5
        while query("select count(*) from pg_stat_activity where application_name='owner_submission_loser' and wait_event_type='Lock'") != "1":
            if time.monotonic() > deadline:
                raise RuntimeError("competing decision did not block on the review lock")
            time.sleep(0.05)
        first.stdin.write("commit;\n")
        first.stdin.close()
        first.wait(timeout=10)
        second.wait(timeout=10)
        error = second.stderr.read()
        expected = "submission_not_acceptable" if winner == "owner" else "owner_submission_already_decided"
        if second.returncode == 0 or expected not in error:
            raise RuntimeError("losing decision did not fail with " + expected + ": " + error)
        status = query("select status::text from content.exhibition_submissions where id='25300000-0000-0000-0000-000000000001'")
        assert status == ("withdrawn" if winner == "owner" else "accepted"), status
        # Remove only this synthetic command receipt so the reversed race starts fresh.
        query("delete from content.command_requests where request_id='25400000-0000-0000-0000-000000000099';")
        print("PASS: " + winner + " wins; competing decision fails without deadlock")
finally:
    for child in children:
        if child.poll() is None:
            child.terminate()
            child.wait(timeout=10)
    query("""
      delete from content.command_requests where actor_user_id in ('00000000-0000-0000-0000-000000002501','00000000-0000-0000-0000-000000002502','00000000-0000-0000-0000-000000002503');
      delete from content.outbox_events where aggregate_id in ('hide-draft','hide-submitted','hide-published','hide-other','25300000-0000-0000-0000-000000000001') or payload->>'actor_user_id' in ('00000000-0000-0000-0000-000000002501','00000000-0000-0000-0000-000000002502','00000000-0000-0000-0000-000000002503');
      delete from content.exhibition_submissions where owner_exhibition_id in ('hide-draft','hide-submitted','hide-published','hide-other');
      update content.exhibitions set published_version_id=null where id in ('hide-draft','hide-submitted','hide-published','hide-other');
      delete from content.exhibition_versions where exhibition_id in ('hide-draft','hide-submitted','hide-published','hide-other');
      delete from content.exhibitions where id in ('hide-draft','hide-submitted','hide-published','hide-other');
      delete from content.media_assets where id='25500000-0000-0000-0000-000000000001';
      delete from content.gallery_memberships where user_id in ('00000000-0000-0000-0000-000000002501','00000000-0000-0000-0000-000000002502','00000000-0000-0000-0000-000000002503');
      delete from content.galleries where id in ('25100000-0000-0000-0000-000000000001','25100000-0000-0000-0000-000000000002');
      delete from content.audit_log where actor_user_id in ('00000000-0000-0000-0000-000000002501','00000000-0000-0000-0000-000000002502','00000000-0000-0000-0000-000000002503');
      delete from content.staff_members where user_id='00000000-0000-0000-0000-000000002501';
      delete from auth.users where id in ('00000000-0000-0000-0000-000000002501','00000000-0000-0000-0000-000000002502','00000000-0000-0000-0000-000000002503');
    """)
