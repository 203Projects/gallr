#!/usr/bin/env bash
set -euo pipefail
umask 077

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)
HELPER="$SCRIPT_DIR/reviewed-toolchain.sh"
TEST_ROOT=$(mktemp -d /tmp/gallr-reviewed-toolchain-test.XXXXXX)
TEST_ROOT=$(cd "$TEST_ROOT" && pwd -P)
chmod 0700 "$TEST_ROOT"
cleanup_reviewed_toolchain_test() {
  local status=$?
  local failed_command=${BASH_COMMAND-unknown}
  trap - EXIT
  if ((status != 0)); then
    printf 'reviewed toolchain test failed after command: %s\n' \
      "$failed_command" >&2
  fi
  rm -rf -- "$TEST_ROOT"
  exit "$status"
}
trap cleanup_reviewed_toolchain_test EXIT

# shellcheck source=reviewed-toolchain.sh
source "$HELPER"

NODE_CAPTURE="$TEST_ROOT/node-environment.txt"
NODE_PID_CAPTURE="$TEST_ROOT/node-pid.txt"
NODE_HANDOFF_PID_CAPTURE="$TEST_ROOT/node-handoff-pid.txt"
NODE_STDIN_CAPTURE="$TEST_ROOT/node-stdin.txt"
BASH_CAPTURE="$TEST_ROOT/bash-environment.txt"
FAKE_NODE="$TEST_ROOT/reviewed-node"
FAKE_PSQL="$TEST_ROOT/reviewed-psql"
SIGNAL_DIAGNOSTICS="$TEST_ROOT/signal-diagnostics.txt"
SIGNAL_UNEXPECTED_DIAGNOSTICS="$TEST_ROOT/unexpected-signal-diagnostics.txt"
: >"$SIGNAL_DIAGNOSTICS"

{
  printf '#!/bin/sh\n'
  printf 'if [ "${1-}" = background ]; then\n'
  printf '  printf "%%s\\n" "$$" > %s\n' "$NODE_PID_CAPTURE"
  printf '  trap "exit 0" TERM INT HUP\n'
  printf '  while :; do /bin/sleep 1; done\n'
  printf 'fi\n'
  printf 'if [ "${1-}" = ignore-term ]; then\n'
  printf '  printf "%%s\\n" "$$" > %s\n' "$NODE_PID_CAPTURE"
  printf "  trap '' HUP INT QUIT TERM\n"
  printf '  while :; do /bin/sleep 30 || true; done\n'
  printf 'fi\n'
  printf 'if [ "${1-}" = same-group-descendant ]; then\n'
  printf "  trap '' HUP INT QUIT TERM\n"
  printf '  printf "ready\\n" >"$GALLR_LEFTOVER_READY_PATH"\n'
  printf '  while :; do /bin/sleep 30 || true; done\n'
  printf 'fi\n'
  printf 'if [ "${1-}" = leave-same-group-descendant ]; then\n'
  printf '  "$0" same-group-descendant &\n'
  printf '  same_group_descendant_pid=$!\n'
  printf '  leftover_ready_attempt=0\n'
  printf '  while [ ! -s "$GALLR_LEFTOVER_READY_PATH" ]; do\n'
  printf '    leftover_ready_attempt=$((leftover_ready_attempt + 1))\n'
  printf '    [ "$leftover_ready_attempt" -lt 100 ] || exit 98\n'
  printf '    /bin/sleep 0.01\n'
  printf '  done\n'
  printf '  printf "%%s %%s\\n" "$$" "$same_group_descendant_pid" >"$GALLR_LEFTOVER_PID_PATH"\n'
  printf '  exit 0\n'
  printf 'fi\n'
  printf 'if [ "${1-}" = nested-supervisor ]; then\n'
  printf '  exec "$GALLR_NESTED_REAL_NODE_PATH" "$GALLR_NESTED_NODE_SCRIPT"\n'
  printf 'fi\n'
  printf 'if [ "${1-}" = stdin ]; then\n'
  printf '  /bin/cat > %s\n' "$NODE_STDIN_CAPTURE"
  printf '  exit 0\n'
  printf 'fi\n'
  printf '/usr/bin/env | /usr/bin/sort > %s\n' "$NODE_CAPTURE"
  printf 'printf "CORE_LIMIT=%%s\\n" "$(ulimit -c)" >> %s\n' "$NODE_CAPTURE"
  printf 'printf "%%s\\n" "$*" >> %s\n' "$NODE_CAPTURE"
} >"$FAKE_NODE"
printf '#!/bin/sh\nexit 0\n' >"$FAKE_PSQL"
chmod 0500 "$FAKE_NODE" "$FAKE_PSQL"

NODE_SHA256=$(gallr_fixed_sha256 "$FAKE_NODE")
PSQL_SHA256=$(gallr_fixed_sha256 "$FAKE_PSQL")
MANIFEST="$TEST_ROOT/operator-manifest.txt"
printf '%s\n' \
  'manifest_schema=2' \
  "reviewed_node_path=$FAKE_NODE" \
  "reviewed_node_sha256=$NODE_SHA256" \
  "reviewed_psql_path=$FAKE_PSQL" \
  "reviewed_psql_sha256=$PSQL_SHA256" \
  >"$MANIFEST"
chmod 0444 "$MANIFEST"

gallr_read_reviewed_toolchain "$MANIFEST"
[[ "$GALLR_REVIEWED_NODE_PATH" == "$FAKE_NODE" ]]
[[ "$GALLR_REVIEWED_PSQL_PATH" == "$FAKE_PSQL" ]]

# A cancellation deadline must return failure before invoking wait when either
# the direct child or its group is still reported live. Override only the two
# bounded poll results and use a DEBUG probe to make any reap attempt visible.
DEADLINE_WAIT_MARKER="$TEST_ROOT/deadline-wait-called"
(
  gallr_poll_tracked_processes_term_grace() {
    return 1
  }
  gallr_poll_tracked_processes_kill_drain() {
    return 1
  }
  detect_deadline_wait() {
    case ${BASH_COMMAND-} in
      'builtin wait "$tracked_pid"' | 'wait "$tracked_pid"')
        : >"$DEADLINE_WAIT_MARKER"
        ;;
    esac
  }
  set -T
  trap detect_deadline_wait DEBUG
  ! gallr_kill_and_reap_tracked_group 2147483647
  trap - DEBUG
  set +T
)
[[ ! -e "$DEADLINE_WAIT_MARKER" ]]

# Replace the pathname with a same-mode, byte-identical fresh inode after the
# descriptor's first fstat but before its first read. The snapshot must reject
# the replacement even though both the opened bytes and the new pathname are
# otherwise independently valid.
MANIFEST_REPLACEMENT="$TEST_ROOT/operator-manifest.replacement.txt"
cp "$MANIFEST" "$MANIFEST_REPLACEMENT"
chmod 0444 "$MANIFEST_REPLACEMENT"
MANIFEST_RECORD_BEFORE=$(gallr_fixed_open_file_stat "$MANIFEST")
MANIFEST_RACE_ARMED=1
replace_manifest_during_descriptor_read() {
  if [[ ${MANIFEST_RACE_ARMED-0} == 1 &&
        ${FUNCNAME[1]-} == gallr_snapshot_reviewed_toolchain_manifest &&
        ${BASH_COMMAND-} == 'IFS= read -r line' ]]; then
    MANIFEST_RACE_ARMED=0
    trap - DEBUG
    /bin/mv -f -- "$MANIFEST_REPLACEMENT" "$MANIFEST"
  fi
}
set -T
trap replace_manifest_during_descriptor_read DEBUG
if gallr_read_reviewed_toolchain "$MANIFEST"; then
  MANIFEST_RACE_STATUS=0
else
  MANIFEST_RACE_STATUS=$?
fi
trap - DEBUG
set +T
[[ "$MANIFEST_RACE_STATUS" -ne 0 ]]
[[ ! -e "$MANIFEST_REPLACEMENT" ]]
MANIFEST_RECORD_AFTER=$(gallr_fixed_open_file_stat "$MANIFEST")
[[ "$MANIFEST_RECORD_AFTER" != "$MANIFEST_RECORD_BEFORE" ]]
gallr_read_reviewed_toolchain "$MANIFEST"

chmod 0644 "$MANIFEST"
! gallr_read_reviewed_toolchain "$MANIFEST"
chmod 0444 "$MANIFEST"
MANIFEST_HARDLINK="$TEST_ROOT/operator-manifest-hardlink.txt"
ln "$MANIFEST" "$MANIFEST_HARDLINK"
! gallr_read_reviewed_toolchain "$MANIFEST"
rm "$MANIFEST_HARDLINK"
gallr_read_reviewed_toolchain "$MANIFEST"

POISON_BIN="$TEST_ROOT/poison-bin"
mkdir -m 0700 "$POISON_BIN"
POISON_MARKER="$TEST_ROOT/poisoned-command-ran"
for command_name in stat shasum sha256sum; do
  {
    printf '#!/bin/sh\n'
    printf ': > %s\n' "$POISON_MARKER"
    printf 'exit 99\n'
  } >"$POISON_BIN/$command_name"
  chmod 0700 "$POISON_BIN/$command_name"
done

PATH="$POISON_BIN:$PATH" \
LD_PRELOAD=poisoned \
DYLD_INSERT_LIBRARIES=poisoned \
PERL5OPT=-MThisModuleMustNeverLoad \
PERL5LIB="$POISON_BIN" \
SECRET_SHOULD_NOT_SURVIVE=poisoned \
  gallr_run_reviewed_node \
    GALLR_TEST_ALLOWED=value \
    -- alpha 'two words'
[[ ! -e "$POISON_MARKER" ]]
grep -Fqx 'GALLR_TEST_ALLOWED=value' "$NODE_CAPTURE"
grep -Fqx 'HOME=/nonexistent' "$NODE_CAPTURE"
grep -Fqx 'PATH=/usr/bin:/bin:/usr/sbin:/sbin' "$NODE_CAPTURE"
grep -Fqx 'TMPDIR=/tmp' "$NODE_CAPTURE"
grep -Fqx 'CORE_LIMIT=0' "$NODE_CAPTURE"
grep -Fqx 'alpha two words' "$NODE_CAPTURE"
! grep -Fq 'SECRET_SHOULD_NOT_SURVIVE' "$NODE_CAPTURE"
! grep -Fq 'LD_PRELOAD' "$NODE_CAPTURE"
! grep -Fq 'DYLD_' "$NODE_CAPTURE"

# Bash recreates these names readonly, so an ambient export used to make the
# clean-child unset loop terminate under `set -e`. They must be de-exported
# without preventing either reviewed child boundary from running.
export SHELLOPTS BASHOPTS
gallr_run_reviewed_node -- readonly-export-boundary
! grep -Fq 'SHELLOPTS=' "$NODE_CAPTURE"
! grep -Fq 'BASHOPTS=' "$NODE_CAPTURE"

# Bash 3.2 with nounset must support reviewed Node calls that need no explicit
# environment assignments (used by local JSON evidence validation).
gallr_run_reviewed_node -- no-assignments
grep -Fqx 'no-assignments' "$NODE_CAPTURE"
! grep -Fq 'GALLR_TEST_ALLOWED' "$NODE_CAPTURE"

# An explicitly inherited descriptor must preserve heredoc/stdin content even
# though the exact reviewed child runs asynchronously for signal supervision.
gallr_run_reviewed_node -- stdin <<'EOF'
first line
second line 한글
EOF
printf '%s\n' 'first line' 'second line 한글' >"$TEST_ROOT/expected-stdin.txt"
cmp "$TEST_ROOT/expected-stdin.txt" "$NODE_STDIN_CAPTURE"

# The asynchronous API returns the PID that execs the reviewed Node path, not
# a disposable outer Bash wrapper. Killing and waiting for it therefore cannot
# leave a Node/psql descendant running.
BACKGROUND_PID=
gallr_start_reviewed_node BACKGROUND_PID -- background
for _ in 1 2 3 4 5 6 7 8 9 10; do
  [[ -s "$NODE_PID_CAPTURE" ]] && break
  /bin/sleep 0.1
done
[[ "$(<"$NODE_PID_CAPTURE")" == "$BACKGROUND_PID" ]]
kill -TERM "$BACKGROUND_PID"
wait "$BACKGROUND_PID"
! kill -0 "$BACKGROUND_PID" 2>/dev/null

# A direct child can exit successfully while a TERM-resistant descendant keeps
# its process group alive. The synchronous helper must not return until that
# owned residual group has received bounded TERM/KILL cleanup, and it must
# preserve the direct child's successful status only after cleanup succeeds.
LEFTOVER_PID_PATH="$TEST_ROOT/leftover-processes.txt"
LEFTOVER_READY_PATH="$TEST_ROOT/leftover-ready.txt"
LEFTOVER_STARTED_AT=$(/bin/date '+%s')
gallr_run_reviewed_node \
  "GALLR_LEFTOVER_PID_PATH=$LEFTOVER_PID_PATH" \
  "GALLR_LEFTOVER_READY_PATH=$LEFTOVER_READY_PATH" \
  -- leave-same-group-descendant
LEFTOVER_ELAPSED=$(( $(/bin/date '+%s') - LEFTOVER_STARTED_AT ))
read -r LEFTOVER_LEADER_PID LEFTOVER_DESCENDANT_PID <"$LEFTOVER_PID_PATH"
[[ "$LEFTOVER_LEADER_PID" =~ ^[1-9][0-9]*$ ]]
[[ "$LEFTOVER_DESCENDANT_PID" =~ ^[1-9][0-9]*$ ]]
((LEFTOVER_ELAPSED >= 2 && LEFTOVER_ELAPSED < 6))
! kill -0 "$LEFTOVER_LEADER_PID" 2>/dev/null
! kill -0 "$LEFTOVER_DESCENDANT_PID" 2>/dev/null
! kill -0 -- "-$LEFTOVER_LEADER_PID" 2>/dev/null

# If either bounded drain reports that the residual group did not settle, the
# direct child's zero status must not escape as success. KILL is still sent
# before this injected deadline failure, so the probe can also verify that the
# synthetic descendant does not leak from the test.
FAIL_CLOSED_PID_PATH="$TEST_ROOT/fail-closed-processes.txt"
FAIL_CLOSED_READY_PATH="$TEST_ROOT/fail-closed-ready.txt"
(
  gallr_poll_tracked_group() {
    return 1
  }
  if gallr_run_reviewed_node \
    "GALLR_LEFTOVER_PID_PATH=$FAIL_CLOSED_PID_PATH" \
    "GALLR_LEFTOVER_READY_PATH=$FAIL_CLOSED_READY_PATH" \
    -- leave-same-group-descendant; then
    exit 98
  fi
)
read -r FAIL_CLOSED_LEADER_PID FAIL_CLOSED_DESCENDANT_PID \
  <"$FAIL_CLOSED_PID_PATH"
for _ in 1 2 3 4 5 6 7 8 9 10; do
  if ! kill -0 "$FAIL_CLOSED_DESCENDANT_PID" 2>/dev/null &&
      ! kill -0 -- "-$FAIL_CLOSED_LEADER_PID" 2>/dev/null; then
    break
  fi
  /bin/sleep 0.1
done
! kill -0 "$FAIL_CLOSED_LEADER_PID" 2>/dev/null
! kill -0 "$FAIL_CLOSED_DESCENDANT_PID" 2>/dev/null
! kill -0 -- "-$FAIL_CLOSED_LEADER_PID" 2>/dev/null

# A DEBUG trap sends each handled signal immediately before the helper writes
# the started PID into the caller's variable. The helper must defer it, assign
# and stop the exact process group, then re-raise the caller's original signal.
HANDOFF_PROBE="$TEST_ROOT/handoff-probe.sh"
cat >"$HANDOFF_PROBE" <<'EOF'
#!/bin/bash
set -euo pipefail
# shellcheck source=reviewed-toolchain.sh
source "$GALLR_HANDOFF_HELPER"

SIGNAL_CHILD_PID=
signal_at_pid_assignment() {
  if [[ ${BASH_COMMAND-} == \
    'printf -v "$pid_variable" '\''%s'\'' "$started_pid"' ]]; then
    trap - DEBUG
    printf '%s\n' "$started_pid" >"$GALLR_HANDOFF_PID_CAPTURE"
    kill "-$GALLR_HANDOFF_SIGNAL" "$$"
  fi
}
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 131' QUIT
trap 'exit 143' TERM
set -T
trap signal_at_pid_assignment DEBUG
gallr_start_reviewed_node SIGNAL_CHILD_PID -- background
exit 99
EOF
chmod 0700 "$HANDOFF_PROBE"
for signal_and_status in HUP:129 INT:130 QUIT:131 TERM:143; do
  HANDOFF_SIGNAL=${signal_and_status%%:*}
  HANDOFF_EXPECTED_STATUS=${signal_and_status#*:}
  rm -f "$NODE_HANDOFF_PID_CAPTURE"
  set +e
  GALLR_HANDOFF_HELPER="$HELPER" \
  GALLR_HANDOFF_PID_CAPTURE="$NODE_HANDOFF_PID_CAPTURE" \
  GALLR_HANDOFF_SIGNAL="$HANDOFF_SIGNAL" \
  GALLR_REVIEWED_NODE_PATH="$GALLR_REVIEWED_NODE_PATH" \
  GALLR_REVIEWED_NODE_SHA256="$GALLR_REVIEWED_NODE_SHA256" \
    /bin/bash --noprofile --norc "$HANDOFF_PROBE" \
      2>>"$SIGNAL_DIAGNOSTICS"
  SIGNAL_HANDOFF_STATUS=$?
  set -e
  [[ "$SIGNAL_HANDOFF_STATUS" -eq "$HANDOFF_EXPECTED_STATUS" ]]
  SIGNAL_CHILD_PID=$(<"$NODE_HANDOFF_PID_CAPTURE")
  ! kill -0 "$SIGNAL_CHILD_PID" 2>/dev/null
done

# A signal delivered only to the top-level wrapper PID must reach and reap the
# exact reviewed child before the caller's original signal trap runs. Job
# control prevents the test wrapper itself from inheriting INT/QUIT ignored
# merely because this harness starts it asynchronously.
FOREGROUND_PROBE="$TEST_ROOT/foreground-probe.sh"
cat >"$FOREGROUND_PROBE" <<'EOF'
#!/bin/bash
set -euo pipefail
# shellcheck source=reviewed-toolchain.sh
source "$GALLR_HANDOFF_HELPER"
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 131' QUIT
trap 'exit 130' TERM
gallr_run_reviewed_node -- "${GALLR_FOREGROUND_MODE:-background}"
exit 99
EOF
chmod 0700 "$FOREGROUND_PROBE"
for signal_and_status in HUP:129 INT:130 QUIT:131 TERM:130; do
  FOREGROUND_SIGNAL=${signal_and_status%%:*}
  FOREGROUND_EXPECTED_STATUS=${signal_and_status#*:}
  rm -f "$NODE_PID_CAPTURE"
  set -m
  GALLR_HANDOFF_HELPER="$HELPER" \
  GALLR_REVIEWED_NODE_PATH="$GALLR_REVIEWED_NODE_PATH" \
  GALLR_REVIEWED_NODE_SHA256="$GALLR_REVIEWED_NODE_SHA256" \
    /bin/bash --noprofile --norc "$FOREGROUND_PROBE" \
      2>>"$SIGNAL_DIAGNOSTICS" &
  FOREGROUND_WRAPPER_PID=$!
  set +m
  for _ in 1 2 3 4 5 6 7 8 9 10; do
    [[ -s "$NODE_PID_CAPTURE" ]] && break
    /bin/sleep 0.1
  done
  FOREGROUND_CHILD_PID=$(<"$NODE_PID_CAPTURE")
  kill "-$FOREGROUND_SIGNAL" "$FOREGROUND_WRAPPER_PID"
  set +e
  wait "$FOREGROUND_WRAPPER_PID" 2>>"$SIGNAL_DIAGNOSTICS"
  FOREGROUND_STATUS=$?
  set -e
  [[ "$FOREGROUND_STATUS" -eq "$FOREGROUND_EXPECTED_STATUS" ]]
  ! kill -0 "$FOREGROUND_CHILD_PID" 2>/dev/null
done

# A TERM-resistant process group must be escalated to KILL within the fixed
# grace period, reaped, and followed by the caller's original TERM behavior.
rm -f "$NODE_PID_CAPTURE"
set -m
GALLR_HANDOFF_HELPER="$HELPER" \
GALLR_FOREGROUND_MODE=ignore-term \
GALLR_REVIEWED_NODE_PATH="$GALLR_REVIEWED_NODE_PATH" \
GALLR_REVIEWED_NODE_SHA256="$GALLR_REVIEWED_NODE_SHA256" \
  /bin/bash --noprofile --norc "$FOREGROUND_PROBE" \
    2>>"$SIGNAL_DIAGNOSTICS" &
RESISTANT_WRAPPER_PID=$!
set +m
for _ in 1 2 3 4 5 6 7 8 9 10; do
  [[ -s "$NODE_PID_CAPTURE" ]] && break
  /bin/sleep 0.1
done
RESISTANT_CHILD_PID=$(<"$NODE_PID_CAPTURE")
RESISTANT_STARTED_AT=$(/bin/date '+%s')
kill -TERM "$RESISTANT_WRAPPER_PID"
set +e
wait "$RESISTANT_WRAPPER_PID" 2>>"$SIGNAL_DIAGNOSTICS"
RESISTANT_STATUS=$?
set -e
RESISTANT_ELAPSED=$(( $(/bin/date '+%s') - RESISTANT_STARTED_AT ))
[[ "$RESISTANT_STATUS" -eq 130 ]]
# The implementation's 6-second TERM grace is intentionally exercised here.
# GNU/Linux starts a fresh /bin/sleep for each poll, and whole-second wall-clock
# rounding plus runner load can add several seconds without changing the fixed
# poll bound. Retain a lower bound that proves the complete TERM grace ran and
# an upper bound that still fails well before an accidentally retained
# 30-second child.
((RESISTANT_ELAPSED >= 6 && RESISTANT_ELAPSED < 12))
! kill -0 "$RESISTANT_CHILD_PID" 2>/dev/null

# The real validated-psql Node launcher owns a separately detached psql process
# group and can spend about four seconds forwarding TERM, escalating to KILL,
# draining that group, and removing its private passfile/certificate directory.
# Model that exact topology and full budget with real Node so the outer Bash
# supervisor cannot accidentally pass by killing a same-group fake descendant.
NESTED_TRANSPORT_DIR="$TEST_ROOT/nested-transport"
NESTED_READY_PATH="$TEST_ROOT/nested.ready"
NESTED_CLEANUP_MARKER="$TEST_ROOT/nested-cleanup.txt"
NESTED_OUTER_RERAISE_MARKER="$TEST_ROOT/nested-outer-reraise.txt"
NESTED_SUPERVISOR_PID_PATH="$TEST_ROOT/nested-supervisor.pid"
NESTED_DESCENDANT_PID_PATH="$TEST_ROOT/nested-descendant.pid"
NESTED_DESCENDANT_READY_PATH="$TEST_ROOT/nested-descendant.ready"
NESTED_NODE_SCRIPT="$TEST_ROOT/nested-supervisor.mjs"
NESTED_PROBE="$TEST_ROOT/nested-supervisor-probe.sh"
NESTED_REAL_NODE_PATH=$(command -v node)
[[ "$NESTED_REAL_NODE_PATH" == /* && -x "$NESTED_REAL_NODE_PATH" ]]
cat >"$NESTED_NODE_SCRIPT" <<'EOF'
import { spawn } from "node:child_process";
import {
  existsSync,
  mkdirSync,
  rmSync,
  writeFileSync,
} from "node:fs";

const handledSignals = ["SIGHUP", "SIGINT", "SIGQUIT", "SIGTERM"];
const delay = (milliseconds) =>
  new Promise((resolve) => setTimeout(resolve, milliseconds));

if (process.argv[2] === "detached-descendant") {
  for (const signal of handledSignals) {
    process.on(signal, () => {});
  }
  writeFileSync(
    process.env.GALLR_NESTED_DESCENDANT_READY_PATH,
    "descendant-ready\n"
  );
  setInterval(() => {}, 30_000);
} else {
  process.umask(0o077);
  mkdirSync(process.env.GALLR_NESTED_TRANSPORT_DIR, { mode: 0o700 });
  writeFileSync(
    `${process.env.GALLR_NESTED_TRANSPORT_DIR}/pgpass`,
    "temporary-password\n",
    { mode: 0o600 }
  );

  const descendant = spawn(
    process.execPath,
    [process.argv[1], "detached-descendant"],
    {
      detached: true,
      env: {
        GALLR_NESTED_DESCENDANT_READY_PATH:
          process.env.GALLR_NESTED_DESCENDANT_READY_PATH,
        LANG: "C",
        LC_ALL: "C",
        PATH: "/usr/bin:/bin:/usr/sbin:/sbin",
      },
      shell: false,
      stdio: "ignore",
    }
  );
  let descendantClosed = false;
  descendant.once("close", () => {
    descendantClosed = true;
  });

  writeFileSync(
    process.env.GALLR_NESTED_SUPERVISOR_PID_PATH,
    `${process.pid}\n`
  );
  writeFileSync(
    process.env.GALLR_NESTED_DESCENDANT_PID_PATH,
    `${descendant.pid}\n`
  );
  for (let attempt = 0; attempt < 200; attempt += 1) {
    if (existsSync(process.env.GALLR_NESTED_DESCENDANT_READY_PATH)) break;
    await delay(10);
  }
  if (!existsSync(process.env.GALLR_NESTED_DESCENDANT_READY_PATH)) {
    throw new Error("detached descendant did not become ready");
  }

  const groupIsAlive = () => {
    try {
      process.kill(-descendant.pid, 0);
      return true;
    } catch (error) {
      return error?.code !== "ESRCH";
    }
  };
  const signalGroup = (signal) => {
    try {
      process.kill(-descendant.pid, signal);
    } catch (error) {
      if (error?.code !== "ESRCH") throw error;
    }
  };

  let cleanupStarted = false;
  const cleanup = async () => {
    if (cleanupStarted) return;
    cleanupStarted = true;
    for (const signal of handledSignals) {
      process.removeAllListeners(signal);
      process.on(signal, () => {});
    }

    signalGroup("SIGTERM");
    await delay(2_100);
    signalGroup("SIGKILL");
    await delay(2_100);
    if (groupIsAlive() || !descendantClosed) {
      throw new Error("detached descendant did not drain");
    }

    rmSync(process.env.GALLR_NESTED_TRANSPORT_DIR, {
      force: true,
      recursive: true,
    });
    writeFileSync(
      process.env.GALLR_NESTED_CLEANUP_MARKER,
      "cleanup-complete\n"
    );
    process.exit(143);
  };
  for (const signal of handledSignals) {
    process.on(signal, () => {
      cleanup().catch(() => process.exit(97));
    });
  }

  writeFileSync(process.env.GALLR_NESTED_READY_PATH, "ready\n");
  setInterval(() => {}, 30_000);
}
EOF
cat >"$NESTED_PROBE" <<'EOF'
#!/bin/bash
set -euo pipefail
# shellcheck source=reviewed-toolchain.sh
source "$GALLR_HANDOFF_HELPER"
nested_outer_term() {
  [[ -f "$GALLR_NESTED_CLEANUP_MARKER" ]]
  [[ ! -e "$GALLR_NESTED_TRANSPORT_DIR" ]]
  nested_supervisor_pid=$(<"$GALLR_NESTED_SUPERVISOR_PID_PATH")
  nested_descendant_pid=$(<"$GALLR_NESTED_DESCENDANT_PID_PATH")
  ! kill -0 "$nested_supervisor_pid" 2>/dev/null
  ! kill -0 "$nested_descendant_pid" 2>/dev/null
  ! kill -0 -- "-$nested_supervisor_pid" 2>/dev/null
  ! kill -0 -- "-$nested_descendant_pid" 2>/dev/null
  printf 'outer-reraised-after-inner-cleanup\n' >"$GALLR_NESTED_OUTER_RERAISE_MARKER"
  exit 130
}
trap nested_outer_term TERM
gallr_run_reviewed_node \
  "GALLR_NESTED_TRANSPORT_DIR=$GALLR_NESTED_TRANSPORT_DIR" \
  "GALLR_NESTED_READY_PATH=$GALLR_NESTED_READY_PATH" \
  "GALLR_NESTED_CLEANUP_MARKER=$GALLR_NESTED_CLEANUP_MARKER" \
  "GALLR_NESTED_OUTER_RERAISE_MARKER=$GALLR_NESTED_OUTER_RERAISE_MARKER" \
  "GALLR_NESTED_SUPERVISOR_PID_PATH=$GALLR_NESTED_SUPERVISOR_PID_PATH" \
  "GALLR_NESTED_DESCENDANT_PID_PATH=$GALLR_NESTED_DESCENDANT_PID_PATH" \
  "GALLR_NESTED_DESCENDANT_READY_PATH=$GALLR_NESTED_DESCENDANT_READY_PATH" \
  "GALLR_NESTED_REAL_NODE_PATH=$GALLR_NESTED_REAL_NODE_PATH" \
  "GALLR_NESTED_NODE_SCRIPT=$GALLR_NESTED_NODE_SCRIPT" \
  -- nested-supervisor
exit 99
EOF
chmod 0500 "$NESTED_NODE_SCRIPT"
chmod 0700 "$NESTED_PROBE"
rm -f "$NESTED_READY_PATH" "$NESTED_CLEANUP_MARKER" \
  "$NESTED_OUTER_RERAISE_MARKER" "$NESTED_DESCENDANT_READY_PATH"
set -m
GALLR_HANDOFF_HELPER="$HELPER" \
GALLR_NESTED_TRANSPORT_DIR="$NESTED_TRANSPORT_DIR" \
GALLR_NESTED_READY_PATH="$NESTED_READY_PATH" \
GALLR_NESTED_CLEANUP_MARKER="$NESTED_CLEANUP_MARKER" \
GALLR_NESTED_OUTER_RERAISE_MARKER="$NESTED_OUTER_RERAISE_MARKER" \
GALLR_NESTED_SUPERVISOR_PID_PATH="$NESTED_SUPERVISOR_PID_PATH" \
GALLR_NESTED_DESCENDANT_PID_PATH="$NESTED_DESCENDANT_PID_PATH" \
GALLR_NESTED_DESCENDANT_READY_PATH="$NESTED_DESCENDANT_READY_PATH" \
GALLR_NESTED_REAL_NODE_PATH="$NESTED_REAL_NODE_PATH" \
GALLR_NESTED_NODE_SCRIPT="$NESTED_NODE_SCRIPT" \
GALLR_REVIEWED_NODE_PATH="$GALLR_REVIEWED_NODE_PATH" \
GALLR_REVIEWED_NODE_SHA256="$GALLR_REVIEWED_NODE_SHA256" \
  /bin/bash --noprofile --norc "$NESTED_PROBE" \
    2>>"$SIGNAL_DIAGNOSTICS" &
NESTED_WRAPPER_PID=$!
set +m
for _ in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20; do
  [[ -s "$NESTED_READY_PATH" ]] && break
  /bin/sleep 0.1
done
[[ -s "$NESTED_READY_PATH" && -s "$NESTED_DESCENDANT_READY_PATH" ]]
[[ -f "$NESTED_TRANSPORT_DIR/pgpass" ]]
NESTED_SUPERVISOR_PID=$(<"$NESTED_SUPERVISOR_PID_PATH")
NESTED_DESCENDANT_PID=$(<"$NESTED_DESCENDANT_PID_PATH")
[[ "$NESTED_DESCENDANT_PID" != "$NESTED_SUPERVISOR_PID" ]]
kill -0 "$NESTED_SUPERVISOR_PID"
kill -0 "$NESTED_DESCENDANT_PID"
# A negative kill probe succeeds only when the numeric PID also names a live
# process group. Distinct successful probes therefore prove that Node's
# `detached: true` child is not a member of the outer supervisor's group without
# relying on ps, which is intentionally unavailable in the macOS sandbox.
kill -0 -- "-$NESTED_SUPERVISOR_PID"
kill -0 -- "-$NESTED_DESCENDANT_PID"
NESTED_STARTED_AT=$(/bin/date '+%s')
kill -TERM "$NESTED_WRAPPER_PID"
set +e
wait "$NESTED_WRAPPER_PID" 2>>"$SIGNAL_DIAGNOSTICS"
NESTED_STATUS=$?
set -e
NESTED_ELAPSED=$(( $(/bin/date '+%s') - NESTED_STARTED_AT ))
[[ "$NESTED_STATUS" -eq 130 ]]
((NESTED_ELAPSED >= 4 && NESTED_ELAPSED < 8))
[[ "$(cat "$NESTED_CLEANUP_MARKER")" == cleanup-complete ]]
[[ "$(cat "$NESTED_OUTER_RERAISE_MARKER")" == \
  outer-reraised-after-inner-cleanup ]]
[[ ! -e "$NESTED_TRANSPORT_DIR" ]]
! kill -0 "$NESTED_SUPERVISOR_PID" 2>/dev/null
! kill -0 "$NESTED_DESCENDANT_PID" 2>/dev/null
! kill -0 -- "-$NESTED_SUPERVISOR_PID" 2>/dev/null
! kill -0 -- "-$NESTED_DESCENDANT_PID" 2>/dev/null

TARGET_SCRIPT="$TEST_ROOT/clean-bash-target.sh"
{
  printf '#!/bin/bash\n'
  printf '/usr/bin/env | /usr/bin/sort > %s\n' "$BASH_CAPTURE"
  printf 'printf "CORE_LIMIT=%%s\\n" "$(ulimit -c)" >> %s\n' "$BASH_CAPTURE"
} >"$TARGET_SCRIPT"
chmod 0500 "$TARGET_SCRIPT"
SECRET_SHOULD_NOT_SURVIVE=poisoned \
  gallr_run_clean_bash \
    GALLR_EXPECTED_TEST_VALUE=allowed \
    -- "$TARGET_SCRIPT"
grep -Fqx 'GALLR_EXPECTED_TEST_VALUE=allowed' "$BASH_CAPTURE"
! grep -Fq 'SECRET_SHOULD_NOT_SURVIVE' "$BASH_CAPTURE"
! grep -Fq 'SHELLOPTS=' "$BASH_CAPTURE"
! grep -Fq 'BASHOPTS=' "$BASH_CAPTURE"
grep -Fqx 'CORE_LIMIT=0' "$BASH_CAPTURE"

# The clean-Bash target guard path has the same top-level cancellation
# contract as reviewed Node. TERM must reach and reap the exact exec'd Bash.
CLEAN_SIGNAL_TARGET="$TEST_ROOT/clean-signal-target.sh"
CLEAN_SIGNAL_PID_CAPTURE="$TEST_ROOT/clean-signal-pid.txt"
CLEAN_DESCENDANT_PID_CAPTURE="$TEST_ROOT/clean-descendant-signal-pid.txt"
CLEAN_SIGNAL_PROBE="$TEST_ROOT/clean-signal-probe.sh"
cat >"$CLEAN_SIGNAL_TARGET" <<'EOF'
#!/bin/bash
set -euo pipefail
printf '%s\n' "$$" >"$GALLR_CLEAN_SIGNAL_PID_CAPTURE"
trap 'exit 0' TERM
/bin/sleep 30 &
descendant_pid=$!
printf '%s\n' "$descendant_pid" >"$GALLR_CLEAN_DESCENDANT_PID_CAPTURE"
wait "$descendant_pid"
EOF
cat >"$CLEAN_SIGNAL_PROBE" <<'EOF'
#!/bin/bash
set -euo pipefail
# shellcheck source=reviewed-toolchain.sh
source "$GALLR_HANDOFF_HELPER"
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 131' QUIT
trap 'exit 130' TERM
gallr_run_clean_bash \
  "GALLR_CLEAN_SIGNAL_PID_CAPTURE=$GALLR_CLEAN_SIGNAL_PID_CAPTURE" \
  "GALLR_CLEAN_DESCENDANT_PID_CAPTURE=$GALLR_CLEAN_DESCENDANT_PID_CAPTURE" \
  -- "$GALLR_CLEAN_SIGNAL_TARGET"
exit 99
EOF
chmod 0700 "$CLEAN_SIGNAL_TARGET" "$CLEAN_SIGNAL_PROBE"
for signal_and_status in HUP:129 INT:130 QUIT:131 TERM:130; do
  CLEAN_SIGNAL=${signal_and_status%%:*}
  CLEAN_EXPECTED_STATUS=${signal_and_status#*:}
  rm -f "$CLEAN_SIGNAL_PID_CAPTURE"
  rm -f "$CLEAN_DESCENDANT_PID_CAPTURE"
  set -m
  GALLR_HANDOFF_HELPER="$HELPER" \
  GALLR_CLEAN_SIGNAL_PID_CAPTURE="$CLEAN_SIGNAL_PID_CAPTURE" \
  GALLR_CLEAN_DESCENDANT_PID_CAPTURE="$CLEAN_DESCENDANT_PID_CAPTURE" \
  GALLR_CLEAN_SIGNAL_TARGET="$CLEAN_SIGNAL_TARGET" \
    /bin/bash --noprofile --norc "$CLEAN_SIGNAL_PROBE" \
      2>>"$SIGNAL_DIAGNOSTICS" &
  CLEAN_SIGNAL_WRAPPER_PID=$!
  set +m
  for _ in 1 2 3 4 5 6 7 8 9 10; do
    [[ -s "$CLEAN_SIGNAL_PID_CAPTURE" ]] && break
    /bin/sleep 0.1
  done
  CLEAN_SIGNAL_CHILD_PID=$(<"$CLEAN_SIGNAL_PID_CAPTURE")
  CLEAN_SIGNAL_DESCENDANT_PID=$(<"$CLEAN_DESCENDANT_PID_CAPTURE")
  kill "-$CLEAN_SIGNAL" "$CLEAN_SIGNAL_WRAPPER_PID"
  set +e
  wait "$CLEAN_SIGNAL_WRAPPER_PID" 2>>"$SIGNAL_DIAGNOSTICS"
  CLEAN_SIGNAL_STATUS=$?
  set -e
  [[ "$CLEAN_SIGNAL_STATUS" -eq "$CLEAN_EXPECTED_STATUS" ]]
  ! kill -0 "$CLEAN_SIGNAL_CHILD_PID" 2>/dev/null
  ! kill -0 "$CLEAN_SIGNAL_DESCENDANT_PID" 2>/dev/null
done

# Keep Bash's expected job-control notices out of the test output, but inspect
# every captured stderr line so internal trap/wait warnings can never be
# hidden by a blanket stderr redirect.
is_expected_signal_diagnostic() {
  local diagnostic=$1
  case $diagnostic in
    # macOS Bash 3.2 includes the signal number, while GNU Bash emits the
    # exact bare word for the same expected job-control notification.
    'Terminated' | 'Terminated: 15') return 0 ;;
    "$HELPER":\ line\ *:*"$RESISTANT_CHILD_PID"\ Killed*) return 0 ;;
    *) return 1 ;;
  esac
}
is_expected_signal_diagnostic 'Terminated'
is_expected_signal_diagnostic 'Terminated: 15'
for near_match in 'Terminated ' 'Terminated: 9' 'Terminated: 15 extra'; do
  ! is_expected_signal_diagnostic "$near_match"
done

: >"$SIGNAL_UNEXPECTED_DIAGNOSTICS"
while IFS= read -r diagnostic || [[ -n $diagnostic ]]; do
  is_expected_signal_diagnostic "$diagnostic" ||
    printf '%s\n' "$diagnostic" >>"$SIGNAL_UNEXPECTED_DIAGNOSTICS"
done <"$SIGNAL_DIAGNOSTICS"
if [[ -s "$SIGNAL_UNEXPECTED_DIAGNOSTICS" ]]; then
  /bin/cat "$SIGNAL_UNEXPECTED_DIAGNOSTICS" >&2
  exit 1
fi

INSECURE_PARENT="$TEST_ROOT/insecure"
mkdir -m 0770 "$INSECURE_PARENT"
INSECURE_TOOL="$INSECURE_PARENT/tool"
printf '#!/bin/sh\nexit 0\n' >"$INSECURE_TOOL"
chmod 0500 "$INSECURE_TOOL"
INSECURE_SHA256=$(gallr_fixed_sha256 "$INSECURE_TOOL")
! gallr_assert_reviewed_executable "$INSECURE_TOOL" "$INSECURE_SHA256"

PACKAGE_PREFIX="$TEST_ROOT/package-prefix"
PACKAGE_TOOL_DIR="$PACKAGE_PREFIX/Cellar/tool/1/bin"
mkdir -p "$PACKAGE_TOOL_DIR" "$PACKAGE_PREFIX/opt"
PACKAGE_TOOL="$PACKAGE_TOOL_DIR/tool"
printf '#!/bin/sh\nexit 0\n' >"$PACKAGE_TOOL"
chmod 0500 "$PACKAGE_TOOL"
chmod 0770 "$PACKAGE_PREFIX/opt"
PACKAGE_TOOL_SHA256=$(gallr_fixed_sha256 "$PACKAGE_TOOL")
! gallr_assert_reviewed_executable "$PACKAGE_TOOL" "$PACKAGE_TOOL_SHA256"
chmod 0700 "$PACKAGE_PREFIX/opt"
gallr_assert_reviewed_executable "$PACKAGE_TOOL" "$PACKAGE_TOOL_SHA256"

SYMLINK_TOOL="$TEST_ROOT/linked-tool"
ln -s "$FAKE_PSQL" "$SYMLINK_TOOL"
! gallr_assert_reviewed_executable "$SYMLINK_TOOL" "$PSQL_SHA256"

chmod 0700 "$FAKE_PSQL"
printf '# modified\n' >>"$FAKE_PSQL"
chmod 0500 "$FAKE_PSQL"
! gallr_assert_reviewed_executable "$FAKE_PSQL" "$PSQL_SHA256"

printf 'reviewed toolchain tests passed\n'
