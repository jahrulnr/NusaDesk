#!/usr/bin/env bash
# Fail before compiling if the recursive checkout and reviewed lock disagree.
set -euo pipefail
source_dir="${1:?source checkout required}"
lock_file="${2:?submodule lock required}"
diff -u <(awk '{print $1 " " $2}' "$lock_file" | LC_ALL=C sort) \
    <(git -C "$source_dir" submodule status --recursive | awk '{print $1 " " $2}' | LC_ALL=C sort) || {
    echo 'X11 submodule revisions differ from the reviewed lock.' >&2
    exit 1
}
