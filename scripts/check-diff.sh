#!/usr/bin/env bash
set -euo pipefail

event_name="${EVENT_NAME:?GitHub event name is required}"
event_after="${EVENT_AFTER:?GitHub event SHA is required}"

validate_sha() {
  [[ "$1" =~ ^[[:xdigit:]]{40}$ || "$1" =~ ^[[:xdigit:]]{64}$ ]]
}

fetch_commit() {
  local sha="$1"
  validate_sha "$sha" || { echo "Invalid commit SHA" >&2; exit 2; }
  if ! git cat-file -e "${sha}^{commit}" 2>/dev/null; then
    git fetch --no-tags origin "$sha"
  fi
  git cat-file -e "${sha}^{commit}"
}

validate_sha "$event_after" || { echo "Invalid event commit SHA" >&2; exit 2; }

case "$event_name" in
  pull_request)
    pr_base_sha="${PR_BASE_SHA:?Pull request base SHA is required}"
    fetch_commit "$pr_base_sha"
    diff_base="$(git merge-base "$pr_base_sha" "$event_after")"
    diff_head="$event_after"
    ;;
  push)
    event_before="${EVENT_BEFORE:?Push before SHA is required}"
    if [[ "$event_before" =~ ^0+$ ]]; then
      diff_base="$(git hash-object -t tree /dev/null)"
    else
      fetch_commit "$event_before"
      diff_base="$event_before"
    fi
    diff_head="$event_after"
    ;;
  *)
    echo "Unsupported GitHub event: $event_name" >&2
    exit 2
    ;;
esac

git diff --check "$diff_base" "$diff_head"
