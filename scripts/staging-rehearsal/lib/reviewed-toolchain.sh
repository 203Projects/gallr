#!/usr/bin/env bash

# Bind credential-bearing rehearsal commands to the exact Node.js and psql
# executables recorded by the local-only preflight. This file is sourced by the
# Bash runners and can also validate two explicit paths for preflight:
#   /bin/bash reviewed-toolchain.sh NODE_PATH PSQL_PATH

gallr_toolchain_error() {
  printf 'ERROR: reviewed toolchain validation failed\n' >&2
  return 1
}

gallr_fixed_stat() {
  local target=$1
  local record
  local kernel

  [[ -x /usr/bin/stat && -x /usr/bin/uname ]] || return 1
  kernel=$(/usr/bin/uname -s) || return 1
  case $kernel in
    Darwin)
      record=$(/usr/bin/stat -f '%d %i %u %p %l' "$target" 2>/dev/null) ||
        return 1
      ;;
    Linux)
      record=$(/usr/bin/stat -c '%d %i %u %a %h' "$target" 2>/dev/null) ||
        return 1
      ;;
    *)
      return 1
      ;;
  esac
  printf '%s\n' "$record"
}

gallr_fixed_open_file_stat() {
  local target=$1
  local record
  local kernel

  [[ -x /usr/bin/stat && -x /usr/bin/uname ]] || return 1
  kernel=$(/usr/bin/uname -s) || return 1
  case $kernel in
    Darwin)
      record=$(
        /usr/bin/stat -L -f '%d %i %u %g %Lp %l %z %m %c' "$target" \
          2>/dev/null
      ) || return 1
      ;;
    Linux)
      record=$(
        /usr/bin/stat -L -c '%d %i %u %g %a %h %s %Y %Z' "$target" \
          2>/dev/null
      ) || return 1
      ;;
    *)
      return 1
      ;;
  esac
  printf '%s\n' "$record"
}

gallr_fixed_descriptor_stat() {
  local descriptor=${1-}
  local record

  [[ $descriptor == 9 ]] || return 1
  [[ -x /usr/bin/perl && ! -L /usr/bin/perl ]] || return 1
  record=$(
    /usr/bin/env -i \
      HOME=/nonexistent LANG=C LC_ALL=C PATH=/usr/bin:/bin:/usr/sbin:/sbin \
      /usr/bin/perl -e '
        -f STDIN or exit 1;
        my @stat = stat(STDIN);
        @stat == 13 or exit 1;
        printf "%s %s %s %s %o %s %s %s %s\n",
          $stat[0], $stat[1], $stat[4], $stat[5], ($stat[2] & 07777),
          $stat[3], $stat[7], $stat[9], $stat[10];
      ' <&9
  ) || return 1
  printf '%s\n' "$record"
}

gallr_fixed_sha256() {
  local target=$1
  local output digest

  if [[ -x /sbin/sha256 && ! -L /sbin/sha256 ]]; then
    output=$(
      /usr/bin/env -i \
        HOME=/nonexistent LANG=C LC_ALL=C PATH=/usr/bin:/bin:/usr/sbin:/sbin \
        /sbin/sha256 -q "$target"
    ) || return 1
  elif [[ -x /usr/bin/sha256sum && ! -L /usr/bin/sha256sum ]]; then
    output=$(
      /usr/bin/env -i \
        HOME=/nonexistent LANG=C LC_ALL=C PATH=/usr/bin:/bin:/usr/sbin:/sbin \
        /usr/bin/sha256sum "$target"
    ) || return 1
  elif [[ -x /usr/bin/shasum && ! -L /usr/bin/shasum ]]; then
    output=$(
      /usr/bin/env -i \
        HOME=/nonexistent LANG=C LC_ALL=C PATH=/usr/bin:/bin:/usr/sbin:/sbin \
        /usr/bin/shasum -a 256 "$target"
    ) || return 1
  else
    return 1
  fi
  digest=${output%%[[:space:]]*}
  [[ $digest =~ ^[0-9a-f]{64}$ ]] || return 1
  printf '%s\n' "$digest"
}

gallr_mode_decimal() {
  local raw=$1
  local permissions

  [[ $raw =~ ^[0-7]+$ ]] || return 1
  if ((${#raw} > 4)); then
    permissions=${raw: -4}
  else
    permissions=$raw
  fi
  printf '%s\n' "$((8#$permissions))"
}

gallr_assert_secure_ancestor() {
  local target=$1
  local trusted_tmp=$2
  local record device inode owner raw_mode links mode

  [[ -d $target && ! -L $target ]] || return 1
  record=$(gallr_fixed_stat "$target") || return 1
  read -r device inode owner raw_mode links <<<"$record"
  [[ $device =~ ^[0-9]+$ && $inode =~ ^[0-9]+$ ]] || return 1
  [[ $owner == 0 || $owner == "$EUID" ]] || return 1
  mode=$(gallr_mode_decimal "$raw_mode") || return 1
  if ((mode & 8#022)); then
    [[ $target == "$trusted_tmp" && $owner == 0 ]] || return 1
    ((mode & 8#1000)) || return 1
    (((mode & 8#777) == 8#777)) || return 1
  fi
}

gallr_assert_reviewed_executable() {
  local target=$1
  local expected_sha256=$2
  local leaf parent canonical_parent trusted_tmp current package_prefix
  local package_link_root
  local before after device inode owner raw_mode links mode digest

  [[ $target == /* && $target != *$'\n'* && $target != *$'\r'* ]] ||
    return 1
  [[ $expected_sha256 =~ ^[0-9a-f]{64}$ ]] || return 1
  [[ -f $target && ! -L $target && -x $target ]] || return 1
  leaf=${target##*/}
  parent=${target%/*}
  [[ -n $leaf && $parent != "$target" ]] || return 1
  canonical_parent=$(CDPATH= cd -P -- "$parent" 2>/dev/null && pwd -P) ||
    return 1
  [[ $canonical_parent/$leaf == "$target" ]] || return 1
  trusted_tmp=$(CDPATH= cd -P -- /tmp 2>/dev/null && pwd -P) || return 1

  before=$(gallr_fixed_stat "$target") || return 1
  read -r device inode owner raw_mode links <<<"$before"
  [[ $device =~ ^[0-9]+$ && $inode =~ ^[0-9]+$ ]] || return 1
  [[ $owner == 0 || $owner == "$EUID" ]] || return 1
  [[ $links == 1 ]] || return 1
  mode=$(gallr_mode_decimal "$raw_mode") || return 1
  ((!(mode & 8#022))) || return 1
  ((mode & 8#111)) || return 1

  current=$canonical_parent
  while :; do
    gallr_assert_secure_ancestor "$current" "$trusted_tmp" || return 1
    [[ $current == / ]] && break
    current=${current%/*}
    [[ -n $current ]] || current=/
  done
  # Homebrew executables load versioned dependencies through the sibling
  # <prefix>/opt symlink hub. Treat that hub as part of the executable's
  # replaceability boundary even though it is not a lexical ancestor.
  case $target in
    */Cellar/*)
      package_prefix=${target%%/Cellar/*}
      package_link_root=$package_prefix/opt
      [[ -d $package_link_root && ! -L $package_link_root ]] || return 1
      gallr_assert_secure_ancestor "$package_link_root" "$trusted_tmp" ||
        return 1
      ;;
  esac

  digest=$(gallr_fixed_sha256 "$target") || return 1
  [[ $digest == "$expected_sha256" ]] || return 1
  after=$(gallr_fixed_stat "$target") || return 1
  [[ $after == "$before" ]] || return 1
}

gallr_snapshot_reviewed_toolchain_manifest() (
  local manifest_path=$1
  local line key value
  local manifest_record manifest_before manifest_device manifest_inode manifest_owner
  local manifest_group
  local manifest_raw_mode manifest_links manifest_size manifest_mtime
  local manifest_ctime manifest_mode manifest_after manifest_path_after
  local node_path= node_sha256= psql_path= psql_sha256=
  local node_path_count=0 node_sha_count=0
  local psql_path_count=0 psql_sha_count=0

  [[ -f $manifest_path && ! -L $manifest_path && -O $manifest_path ]] ||
    return 1
  manifest_record=$(gallr_fixed_open_file_stat "$manifest_path") || return 1
  read -r manifest_device manifest_inode manifest_owner manifest_group \
    manifest_raw_mode \
    manifest_links manifest_size manifest_mtime manifest_ctime \
    <<<"$manifest_record"
  [[ $manifest_device =~ ^[0-9]+$ && $manifest_inode =~ ^[0-9]+$ ]] ||
    return 1
  [[ $manifest_group =~ ^[0-9]+$ && $manifest_size =~ ^[0-9]+$ &&
      $manifest_mtime =~ ^-?[0-9]+$ &&
      $manifest_ctime =~ ^-?[0-9]+$ ]] || return 1
  [[ $manifest_owner == "$EUID" && $manifest_links == 1 ]] || return 1
  manifest_mode=$(gallr_mode_decimal "$manifest_raw_mode") || return 1
  ((!(manifest_mode & 8#222))) || return 1

  exec 9<"$manifest_path" || return 1
  manifest_before=$(gallr_fixed_descriptor_stat 9) || return 1
  [[ $manifest_before == "$manifest_record" ]] || return 1
  while IFS= read -r line || [[ -n $line ]]; do
    key=${line%%=*}
    value=${line#*=}
    case $key in
      reviewed_node_path)
        node_path=$value
        ((node_path_count += 1))
        ;;
      reviewed_node_sha256)
        node_sha256=$value
        ((node_sha_count += 1))
        ;;
      reviewed_psql_path)
        psql_path=$value
        ((psql_path_count += 1))
        ;;
      reviewed_psql_sha256)
        psql_sha256=$value
        ((psql_sha_count += 1))
        ;;
    esac
  done <&9

  manifest_after=$(gallr_fixed_descriptor_stat 9) || return 1
  [[ $manifest_after == "$manifest_before" ]] || return 1
  [[ -f $manifest_path && ! -L $manifest_path && -O $manifest_path ]] ||
    return 1
  manifest_path_after=$(gallr_fixed_open_file_stat "$manifest_path") ||
    return 1
  [[ ! -L $manifest_path && $manifest_path_after == "$manifest_before" ]] ||
    return 1
  exec 9<&-

  [[ $node_path_count == 1 && $node_sha_count == 1 ]] || return 1
  [[ $psql_path_count == 1 && $psql_sha_count == 1 ]] || return 1
  gallr_assert_reviewed_executable "$node_path" "$node_sha256" || return 1
  gallr_assert_reviewed_executable "$psql_path" "$psql_sha256" || return 1

  printf '%s\n' "$node_path" "$node_sha256" "$psql_path" "$psql_sha256"
)

gallr_read_reviewed_toolchain() {
  local manifest_path=$1
  local snapshot snapshot_line snapshot_count=0
  local node_path= node_sha256= psql_path= psql_sha256=

  snapshot=$(gallr_snapshot_reviewed_toolchain_manifest "$manifest_path") ||
    return 1
  while IFS= read -r snapshot_line || [[ -n $snapshot_line ]]; do
    snapshot_count=$((snapshot_count + 1))
    case $snapshot_count in
      1) node_path=$snapshot_line ;;
      2) node_sha256=$snapshot_line ;;
      3) psql_path=$snapshot_line ;;
      4) psql_sha256=$snapshot_line ;;
      *) return 1 ;;
    esac
  done <<<"$snapshot"
  [[ $snapshot_count == 4 ]] || return 1

  GALLR_REVIEWED_NODE_PATH=$node_path
  GALLR_REVIEWED_NODE_SHA256=$node_sha256
  GALLR_REVIEWED_PSQL_PATH=$psql_path
  GALLR_REVIEWED_PSQL_SHA256=$psql_sha256
  export -n GALLR_REVIEWED_NODE_PATH GALLR_REVIEWED_NODE_SHA256
  export -n GALLR_REVIEWED_PSQL_PATH GALLR_REVIEWED_PSQL_SHA256
}

gallr_exec_reviewed_node() {
  local node_path=$GALLR_REVIEWED_NODE_PATH
  local node_sha256=$GALLR_REVIEWED_NODE_SHA256
  local assignment name
  local -a assignments=()
  local -a command_arguments=()

  while (($# > 0)) && [[ $1 != -- ]]; do
    assignment=$1
    name=${assignment%%=*}
    [[ $assignment == *=* && $name =~ ^[A-Z][A-Z0-9_]*$ ]] || return 1
    case $name in
      GALLR_* | FIXTURE_* | BASELINE_* | EXPECTED_* | MANIFEST_* | \
        SUPABASE_URL | SUPABASE_ANON_KEY) ;;
      *) return 1 ;;
    esac
    assignments+=("$assignment")
    shift
  done
  [[ ${1-} == -- ]] || return 1
  shift
  (($# > 0)) || return 1
  command_arguments=("$@")

  local exported_names exported_name
  exported_names=$(compgen -e)
  for exported_name in $exported_names; do
    # Bash recreates SHELLOPTS/BASHOPTS as readonly variables, and callers can
    # export arbitrary readonly names. Remove the export attribute first so a
    # failed readonly unset cannot copy caller state into the reviewed child.
    export -n "$exported_name" 2>/dev/null || true
    unset "$exported_name" 2>/dev/null || true
  done
  export HOME=/nonexistent
  export LANG=C
  export LC_ALL=C
  export PATH=/usr/bin:/bin:/usr/sbin:/sbin
  export TMPDIR=/tmp
  ulimit -c 0 || {
    gallr_toolchain_error
    exit 1
  }
  gallr_assert_reviewed_executable "$node_path" "$node_sha256" || {
    gallr_toolchain_error
    exit 1
  }
  if [[ ${assignments[0]+present} == present ]]; then
    for assignment in "${assignments[@]}"; do
      export "$assignment"
    done
  fi
  exec "$node_path" "${command_arguments[@]}"
}

gallr_terminate_tracked_group() {
  local tracked_pid=${1-}
  [[ $tracked_pid =~ ^[1-9][0-9]*$ ]] || return 1
  builtin kill -TERM -- "-$tracked_pid" 2>/dev/null
}

gallr_tracked_group_is_alive() {
  local tracked_pid=${1-}
  [[ $tracked_pid =~ ^[1-9][0-9]*$ ]] || return 1
  builtin kill -0 -- "-$tracked_pid" 2>/dev/null
}

gallr_tracked_child_is_alive() {
  local tracked_pid=${1-}
  [[ $tracked_pid =~ ^[1-9][0-9]*$ ]] || return 1
  builtin kill -0 -- "$tracked_pid" 2>/dev/null
}

gallr_tracked_processes_are_gone() {
  local tracked_pid=${1-}
  [[ $tracked_pid =~ ^[1-9][0-9]*$ ]] || return 1
  ! gallr_tracked_child_is_alive "$tracked_pid" &&
    ! gallr_tracked_group_is_alive "$tracked_pid"
}

gallr_poll_tracked_group() {
  local tracked_pid=${1-}
  local remaining_polls=${2-}
  [[ $tracked_pid =~ ^[1-9][0-9]*$ ]] || return 1
  [[ $remaining_polls =~ ^[1-9][0-9]*$ ]] || return 1

  while ((remaining_polls > 0)); do
    gallr_tracked_group_is_alive "$tracked_pid" || return 0
    /bin/sleep 0.01
    remaining_polls=$((remaining_polls - 1))
  done
  ! gallr_tracked_group_is_alive "$tracked_pid"
}

gallr_poll_tracked_processes() {
  local tracked_pid=${1-}
  local remaining_polls=${2-}
  [[ $tracked_pid =~ ^[1-9][0-9]*$ ]] || return 1
  [[ $remaining_polls =~ ^[1-9][0-9]*$ ]] || return 1

  while ((remaining_polls > 0)); do
    gallr_tracked_processes_are_gone "$tracked_pid" && return 0
    /bin/sleep 0.01
    remaining_polls=$((remaining_polls - 1))
  done
  gallr_tracked_processes_are_gone "$tracked_pid"
}

gallr_poll_tracked_processes_term_grace() {
  local tracked_pid=${1-}

  # The reviewed Node database launcher has its own bounded 2-second TERM
  # grace followed by a 2-second KILL drain and then removes its passfile and
  # certificate. Keep the outer Bash supervisor alive for longer than that
  # complete inner cleanup budget before it escalates the whole group.
  gallr_poll_tracked_processes "$tracked_pid" 600
}

gallr_poll_tracked_processes_kill_drain() {
  local tracked_pid=${1-}

  # Once the outer supervisor itself has sent SIGKILL, retain a separate short
  # bound rather than reusing the longer nested-supervisor TERM allowance.
  gallr_poll_tracked_processes "$tracked_pid" 200
}

gallr_kill_and_reap_tracked_group() {
  local tracked_pid=${1-}
  [[ $tracked_pid =~ ^[1-9][0-9]*$ ]] || return 1

  if ! gallr_poll_tracked_processes_term_grace "$tracked_pid"; then
    builtin kill -KILL -- "-$tracked_pid" 2>/dev/null || true
    gallr_poll_tracked_processes_kill_drain "$tracked_pid" || return 1
  fi

  # The positive-PID and process-group probes have both observed ESRCH before
  # this wait. Bash has therefore already cached the direct child's status and
  # wait cannot block on a live or uninterruptible OS process. If either probe
  # remains live at the deadline, return failure without calling wait.
  if builtin wait "$tracked_pid"; then :; else :; fi
}

gallr_drain_completed_child_group() {
  local tracked_pid=${1-}
  [[ $tracked_pid =~ ^[1-9][0-9]*$ ]] || return 1

  gallr_tracked_group_is_alive "$tracked_pid" || return 0
  gallr_terminate_tracked_group "$tracked_pid" || true
  # Once the direct child has completed there is no inner supervisor left that
  # needs the longer nested-cleanup allowance. Give residual same-group
  # descendants a short TERM grace, then fail closed unless KILL empties the
  # owned group within the fixed drain deadline.
  if ! gallr_poll_tracked_group "$tracked_pid" 200; then
    builtin kill -KILL -- "-$tracked_pid" 2>/dev/null || true
    gallr_poll_tracked_group "$tracked_pid" 200 || return 1
  fi
}

gallr_stop_tracked_group() {
  local tracked_pid=${1-}
  [[ $tracked_pid =~ ^[1-9][0-9]*$ ]] || return 1
  gallr_terminate_tracked_group "$tracked_pid" || true
  gallr_kill_and_reap_tracked_group "$tracked_pid"
}

gallr_run_tracked_child() {
  local runner=${1-}
  local tracked_child_pid=
  local tracked_signal=
  local child_status=1
  local child_group_cleanup_status=0
  local saved_hup saved_int saved_quit saved_term
  local restore_monitor=0
  shift || return 1
  case $runner in
    gallr_exec_reviewed_node | gallr_exec_clean_bash) ;;
    *) return 1 ;;
  esac

  # Bash defers a caller's signal trap while it waits for a synchronous
  # foreground child. Run the reviewed child asynchronously, retain its exact
  # PID in this shell, and forward/reap cancellation before restoring and
  # re-raising the caller's original signal behavior.
  saved_hup=$(trap -p HUP)
  saved_int=$(trap -p INT)
  saved_quit=$(trap -p QUIT)
  saved_term=$(trap -p TERM)
  trap 'tracked_signal=${tracked_signal:-HUP}; if [[ -n $tracked_child_pid ]]; then builtin kill -TERM -- "-$tracked_child_pid" 2>/dev/null || true; fi' HUP
  trap 'tracked_signal=${tracked_signal:-INT}; if [[ -n $tracked_child_pid ]]; then builtin kill -TERM -- "-$tracked_child_pid" 2>/dev/null || true; fi' INT
  trap 'tracked_signal=${tracked_signal:-QUIT}; if [[ -n $tracked_child_pid ]]; then builtin kill -TERM -- "-$tracked_child_pid" 2>/dev/null || true; fi' QUIT
  trap 'tracked_signal=${tracked_signal:-TERM}; if [[ -n $tracked_child_pid ]]; then builtin kill -TERM -- "-$tracked_child_pid" 2>/dev/null || true; fi' TERM

  [[ $- == *m* ]] || { set -m; restore_monitor=1; }
  (
    "$runner" "$@"
  ) <&0 &
  tracked_child_pid=$!
  ((restore_monitor == 0)) || set +m
  case $tracked_signal in
    HUP | INT | QUIT | TERM)
      gallr_terminate_tracked_group "$tracked_child_pid" || true
      ;;
  esac

  while [[ -z $tracked_signal ]]; do
    # Do not prefix this foreground wait with `builtin`: macOS Bash 3.2 does
    # not interrupt `builtin wait` after running a trapped signal, which would
    # prevent the bounded cancellation path below from ever taking control.
    if wait "$tracked_child_pid"; then
      child_status=0
    else
      child_status=$?
    fi
    break
  done
  if [[ -n $tracked_signal ]]; then
    gallr_kill_and_reap_tracked_group "$tracked_child_pid" || true
  else
    gallr_drain_completed_child_group "$tracked_child_pid" ||
      child_group_cleanup_status=1
  fi

  if [[ -n $saved_hup ]]; then eval "$saved_hup"; else trap - HUP; fi
  if [[ -n $saved_int ]]; then eval "$saved_int"; else trap - INT; fi
  if [[ -n $saved_quit ]]; then eval "$saved_quit"; else trap - QUIT; fi
  if [[ -n $saved_term ]]; then eval "$saved_term"; else trap - TERM; fi

  case $tracked_signal in
    HUP)
      kill -HUP "$$" 2>/dev/null
      return 129
      ;;
    INT)
      kill -INT "$$" 2>/dev/null
      return 130
      ;;
    QUIT)
      kill -QUIT "$$" 2>/dev/null
      return 131
      ;;
    TERM)
      kill -TERM "$$" 2>/dev/null
      return 143
      ;;
  esac
  ((child_group_cleanup_status == 0)) || return 1
  return "$child_status"
}

gallr_run_reviewed_node() {
  gallr_run_tracked_child gallr_exec_reviewed_node "$@"
}

# Start exactly one asynchronous subshell and replace that same PID with Node.
# The first argument names the caller's tracked PID variable. Temporary signal
# traps defer termination across the fork/assignment handoff; the child is
# stopped before returning if a signal arrives in that critical section.
# Backgrounding the foreground helper is forbidden because it adds a second
# wrapper process that can die while its Node/psql descendant survives.
gallr_start_reviewed_node() {
  local pid_variable=${1-}
  local started_pid
  local pending_signal
  local saved_hup saved_int saved_quit saved_term
  local restore_monitor=0
  shift || return 1
  [[ $pid_variable =~ ^[A-Z][A-Z0-9_]*_PID$ ]] || return 1
  printf -v "$pid_variable" '%s' ''

  saved_hup=$(trap -p HUP)
  saved_int=$(trap -p INT)
  saved_quit=$(trap -p QUIT)
  saved_term=$(trap -p TERM)
  GALLR_REVIEWED_NODE_PENDING_SIGNAL=
  trap 'GALLR_REVIEWED_NODE_PENDING_SIGNAL=${GALLR_REVIEWED_NODE_PENDING_SIGNAL:-HUP}; if [[ -n $started_pid ]]; then builtin kill -TERM -- "-$started_pid" 2>/dev/null || true; fi' HUP
  trap 'GALLR_REVIEWED_NODE_PENDING_SIGNAL=${GALLR_REVIEWED_NODE_PENDING_SIGNAL:-INT}; if [[ -n $started_pid ]]; then builtin kill -TERM -- "-$started_pid" 2>/dev/null || true; fi' INT
  trap 'GALLR_REVIEWED_NODE_PENDING_SIGNAL=${GALLR_REVIEWED_NODE_PENDING_SIGNAL:-QUIT}; if [[ -n $started_pid ]]; then builtin kill -TERM -- "-$started_pid" 2>/dev/null || true; fi' QUIT
  trap 'GALLR_REVIEWED_NODE_PENDING_SIGNAL=${GALLR_REVIEWED_NODE_PENDING_SIGNAL:-TERM}; if [[ -n $started_pid ]]; then builtin kill -TERM -- "-$started_pid" 2>/dev/null || true; fi' TERM

  [[ $- == *m* ]] || { set -m; restore_monitor=1; }
  (
    gallr_exec_reviewed_node "$@"
  ) <&0 &
  started_pid=$!
  printf -v "$pid_variable" '%s' "$started_pid"
  ((restore_monitor == 0)) || set +m

  if [[ -n $saved_hup ]]; then eval "$saved_hup"; else trap - HUP; fi
  if [[ -n $saved_int ]]; then eval "$saved_int"; else trap - INT; fi
  if [[ -n $saved_quit ]]; then eval "$saved_quit"; else trap - QUIT; fi
  if [[ -n $saved_term ]]; then eval "$saved_term"; else trap - TERM; fi

  if [[ -n $GALLR_REVIEWED_NODE_PENDING_SIGNAL ]]; then
    gallr_stop_tracked_group "$started_pid" || true
    pending_signal=$GALLR_REVIEWED_NODE_PENDING_SIGNAL
    unset GALLR_REVIEWED_NODE_PENDING_SIGNAL
    case $pending_signal in
      HUP)
        kill -HUP "$$" 2>/dev/null
        return 129
        ;;
      INT)
        kill -INT "$$" 2>/dev/null
        return 130
        ;;
      QUIT)
        kill -QUIT "$$" 2>/dev/null
        return 131
        ;;
      TERM)
        kill -TERM "$$" 2>/dev/null
        return 143
        ;;
    esac
  fi
  unset GALLR_REVIEWED_NODE_PENDING_SIGNAL
  [[ $started_pid =~ ^[1-9][0-9]*$ ]]
}

gallr_exec_clean_bash() {
  local assignment name
  local -a assignments=()
  local -a command_arguments=()

  while (($# > 0)) && [[ $1 != -- ]]; do
    assignment=$1
    name=${assignment%%=*}
    [[ $assignment == *=* && $name =~ ^GALLR_[A-Z0-9_]+$ ]] || return 1
    assignments+=("$assignment")
    shift
  done
  [[ ${1-} == -- ]] || return 1
  shift
  (($# > 0)) || return 1
  command_arguments=("$@")
  [[ -x /bin/bash && ! -L /bin/bash ]] || return 1

  local exported_names exported_name
  exported_names=$(compgen -e)
  for exported_name in $exported_names; do
    export -n "$exported_name" 2>/dev/null || true
    unset "$exported_name" 2>/dev/null || true
  done
  export HOME=/nonexistent
  export LANG=C
  export LC_ALL=C
  export PATH=/usr/bin:/bin:/usr/sbin:/sbin
  export TMPDIR=/tmp
  ulimit -c 0 || exit 1
  if [[ ${assignments[0]+present} == present ]]; then
    for assignment in "${assignments[@]}"; do
      export "$assignment"
    done
  fi
  exec /bin/bash --noprofile --norc -p -- "${command_arguments[@]}"
}

gallr_run_clean_bash() {
  gallr_run_tracked_child gallr_exec_clean_bash "$@"
}

if [[ ${BASH_SOURCE[0]} == "$0" ]]; then
  set -euo pipefail
  if (($# != 2)); then
    gallr_toolchain_error
    exit 1
  fi
  node_path=$1
  psql_path=$2
  node_sha256=$(gallr_fixed_sha256 "$node_path") ||
    { gallr_toolchain_error; exit 1; }
  psql_sha256=$(gallr_fixed_sha256 "$psql_path") ||
    { gallr_toolchain_error; exit 1; }
  gallr_assert_reviewed_executable "$node_path" "$node_sha256" ||
    { gallr_toolchain_error; exit 1; }
  gallr_assert_reviewed_executable "$psql_path" "$psql_sha256" ||
    { gallr_toolchain_error; exit 1; }
  printf 'reviewed_node_path=%s\n' "$node_path"
  printf 'reviewed_node_sha256=%s\n' "$node_sha256"
  printf 'reviewed_psql_path=%s\n' "$psql_path"
  printf 'reviewed_psql_sha256=%s\n' "$psql_sha256"
fi
