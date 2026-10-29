#!/usr/bin/env bash
# Run build_cell.sh in the builder container with resource limits (zo is the owner's home server – the other
# services must not notice). Usage: tools/cells/run.sh Z X Y
#   DATA=~/onpath-data (mounted at /data)   MEM=3g  CPUS=3
#   passed through when set: FORMAT SRC_PBF CUT BUFFER_KM KEEP_WORK JAVA_XMX TERRARIUM_CACHE (paths inside the
#   container, /data/...)   IMAGE=onpath-cell-builder (docker image)
set -euo pipefail
REPO=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
DATA=${DATA:-$HOME/onpath-data}
[ $# -eq 3 ] || { echo "usage: $0 Z X Y" >&2; exit 2; }
ENVS=()
CUT=${CUT:-renumbered}  # MEM=3g: a raw cut needs ~4 GB (build_cell.sh header)
for v in FORMAT SRC_PBF CUT BUFFER_KM KEEP_WORK JAVA_XMX TERRARIUM_CACHE; do [ -n "${!v:-}" ] && ENVS+=(-e "$v=${!v}"); done
# default host: no veth interface per container (protects the Docbox mDNS daemon on zo, see README)
ENVS+=(--network "${NETWORK:-host}")
# DOCKER_ARGS: extra docker run options, e.g. "--dns 1.1.1.1" where the bridge has no working DNS
read -r -a EXTRA <<<"${DOCKER_ARGS:-}"
ENVS+=("${EXTRA[@]}")
# NETWORK (docker --network, default bridge): on zo every bridge container start/stop adds/removes a veth interface,
# and the owner's Docbox mDNS daemon (dnssd) crashes on the vanished interface, so systemd restarts the Docbox
# services. See tools/cells/README.md for the options.
# memory-swap = memory: the container never swaps (zo's swap is used by the other services);
# cpu-shares 512: under contention the other containers get the CPU first
exec docker run --rm --name "onpath-cell-$1-$2-$3" \
  --memory="${MEM:-3g}" --memory-swap="${MEM:-3g}" --cpus="${CPUS:-3}" --cpu-shares=512 \
  --user "$(id -u):$(id -g)" -v "$REPO:/onpath:ro" -v "$DATA:/data" "${ENVS[@]}" \
  "${IMAGE:-onpath-cell-builder}" /onpath/tools/cells/build_cell.sh "$1" "$2" "$3"
