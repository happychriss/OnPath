#!/bin/sh
# Job image only (Dockerfile.job): the image has no .git, so build_cell.sh's "git rev-parse --short HEAD" gets the
# revision the image was built from, and "git status --porcelain" reports a clean tree. Everything else → real git.
case "$*" in
  *rev-parse*) echo "${PIPELINE_REV:-unknown}" ;;
  *"status --porcelain"*) : ;;
  *) exec /usr/bin/git "$@" ;;
esac
