#!/bin/sh -p

# Start credential-bearing rehearsal scripts without inheriting Bash startup
# files or exported shell functions. Keep this launcher POSIX so its direct
# shebang invocation does not need an ambient Bash process.

if [ "$#" -eq 0 ]; then
  printf '%s\n' \
    'usage: run-safe-bash.sh SCRIPT [ARG ...]' >&2
  exit 64
fi

# A crash must not persist database URLs, passwords, or ephemeral passfile
# contents in a core image.
ulimit -c 0 2>/dev/null || exit 1

unset TMP TEMP TMPDIR 2>/dev/null || :
TMPDIR=/tmp
export TMPDIR

unset BASH_ENV 2>/dev/null || :
unset ENV 2>/dev/null || :
unset CDPATH 2>/dev/null || :
unset SHELLOPTS 2>/dev/null || :
unset BASHOPTS 2>/dev/null || :
unset LD_PRELOAD 2>/dev/null || :
unset LD_LIBRARY_PATH 2>/dev/null || :
unset LD_AUDIT 2>/dev/null || :
unset LD_DEBUG 2>/dev/null || :
unset LD_PROFILE 2>/dev/null || :
unset GLIBC_TUNABLES 2>/dev/null || :
unset GCONV_PATH 2>/dev/null || :
unset LOCPATH 2>/dev/null || :
unset NLSPATH 2>/dev/null || :
unset DYLD_FRAMEWORK_PATH 2>/dev/null || :
unset DYLD_FALLBACK_FRAMEWORK_PATH 2>/dev/null || :
unset DYLD_LIBRARY_PATH 2>/dev/null || :
unset DYLD_FALLBACK_LIBRARY_PATH 2>/dev/null || :
unset DYLD_INSERT_LIBRARIES 2>/dev/null || :
unset NODE_OPTIONS NODE_PATH NODE_DEBUG NODE_DEBUG_NATIVE 2>/dev/null || :
unset PERL5OPT PERL5LIB PERLLIB 2>/dev/null || :
unset PYTHONHOME PYTHONPATH 2>/dev/null || :
unset RUBYOPT RUBYLIB GEM_HOME GEM_PATH 2>/dev/null || :
unset OPENSSL_CONF OPENSSL_MODULES SSL_CERT_DIR SSL_CERT_FILE 2>/dev/null || :
unset SSLKEYLOGFILE 2>/dev/null || :

# Locale categories and message catalogs can direct libc at caller-controlled
# data or modules. Clear the POSIX and platform-standard category variables
# explicitly so discovering their names never copies unrelated exported
# credentials through a helper process.
unset LANGUAGE 2>/dev/null || :
unset LC_ADDRESS LC_ALL LC_COLLATE LC_CTYPE LC_IDENTIFICATION 2>/dev/null || :
unset LC_MEASUREMENT LC_MESSAGES LC_MONETARY LC_NAME LC_NUMERIC 2>/dev/null || :
unset LC_PAPER LC_TELEPHONE LC_TIME 2>/dev/null || :
LANG=C
LC_ALL=C
export LANG LC_ALL

# The target scripts resolve their repository root before they can load the
# manifest-bound toolchain helper. Never let an ambient PATH select dirname,
# git, stat, or another bootstrap utility. Optional package-manager directories
# remain last and are included only when their directory entry is not writable
# by group/others; otherwise preflight fails closed when a needed tool is absent.
PATH=/usr/bin:/bin:/usr/sbin:/sbin
gallr_directory_is_secure() {
  gallr_candidate_directory=$1
  [ -d "$gallr_candidate_directory" ] || return 1
  [ ! -L "$gallr_candidate_directory" ] || return 1
  gallr_effective_uid=$(/usr/bin/id -u) || return 1
  if gallr_stat_record=$(
    /usr/bin/stat -f '%u %Lp' "$gallr_candidate_directory" 2>/dev/null
  ); then
    :
  elif gallr_stat_record=$(
    /usr/bin/stat -c '%u %a' "$gallr_candidate_directory" 2>/dev/null
  ); then
    :
  else
    return 1
  fi
  gallr_directory_owner=${gallr_stat_record%% *}
  gallr_directory_mode=${gallr_stat_record#* }
  case "$gallr_directory_owner" in
    0|"$gallr_effective_uid") ;;
    *) return 1 ;;
  esac
  case "$gallr_directory_mode" in
    *[2367][0-7]|*[0-7][2367]) return 1 ;;
  esac
  return 0
}
gallr_append_secure_tool_directory() {
  gallr_candidate_directory=$1
  case "$gallr_candidate_directory" in
    /opt/homebrew/bin)
      gallr_directory_is_secure /opt &&
        gallr_directory_is_secure /opt/homebrew &&
        gallr_directory_is_secure /opt/homebrew/bin ||
        return 0
      ;;
    /usr/local/bin)
      gallr_directory_is_secure /usr &&
        gallr_directory_is_secure /usr/local &&
        gallr_directory_is_secure /usr/local/bin ||
        return 0
      ;;
    *) return 0 ;;
  esac
  PATH=$PATH:$gallr_candidate_directory
}
gallr_append_secure_tool_directory /opt/homebrew/bin
gallr_append_secure_tool_directory /usr/local/bin
unset gallr_candidate_directory gallr_effective_uid gallr_stat_record
unset gallr_directory_owner gallr_directory_mode
unset -f gallr_directory_is_secure gallr_append_secure_tool_directory \
  2>/dev/null || :
export PATH

# SHELLOPTS and BASHOPTS are recreated readonly when this POSIX launcher itself
# is running under Bash, so `unset` above can fail even though it is harmlessly
# ignored. Remove both names at the exec boundary; macOS and GNU `env` support
# `-u`, and the child still receives only the locale/PATH/TMPDIR exports above.
exec /usr/bin/env -u SHELLOPTS -u BASHOPTS \
  /bin/bash --noprofile --norc -p -- "$@"
