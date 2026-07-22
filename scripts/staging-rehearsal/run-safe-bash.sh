#!/bin/sh -p

# Start credential-bearing rehearsal scripts without inheriting Bash startup
# files or exported shell functions. Keep this launcher POSIX so its direct
# shebang invocation does not need an ambient Bash process.

if [ "$#" -eq 0 ]; then
  printf '%s\n' \
    'usage: run-safe-bash.sh SCRIPT [ARG ...]' >&2
  exit 64
fi

unset BASH_ENV 2>/dev/null || :
unset ENV 2>/dev/null || :
unset CDPATH 2>/dev/null || :
unset SHELLOPTS 2>/dev/null || :
unset BASHOPTS 2>/dev/null || :

exec /bin/bash --noprofile --norc -p -- "$@"
