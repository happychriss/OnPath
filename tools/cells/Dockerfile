# OnPath cell builder (docs/design/map-cells.md, usage: tools/cells/README.md).
# Runtime only: the pipeline code is mounted from the repo at /onpath (read-only), data at /data.
#   docker build --network host -t onpath-cell-builder -f tools/cells/Dockerfile tools/cells
# tools/cloud/Dockerfile.job builds on top of this image, so the Cloud Run job gets tippecanoe from here too.

# tippecanoe + tile-join (format 2: overlays.pmtiles, base trim), built from source – no distro package is new enough
FROM ubuntu:24.04 AS tippecanoe
ARG TIPPECANOE_VERSION=2.79.0
RUN apt-get update \
 && apt-get install -y --no-install-recommends build-essential libsqlite3-dev zlib1g-dev curl ca-certificates \
 && curl -fsSL "https://github.com/felt/tippecanoe/archive/refs/tags/${TIPPECANOE_VERSION}.tar.gz" | tar xz -C /tmp \
 && make -C "/tmp/tippecanoe-${TIPPECANOE_VERSION}" -j"$(nproc)" tippecanoe tile-join \
 && install -m 0755 "/tmp/tippecanoe-${TIPPECANOE_VERSION}/tippecanoe" "/tmp/tippecanoe-${TIPPECANOE_VERSION}/tile-join" \
      /usr/local/bin/

FROM eclipse-temurin:21-jre-noble

ARG PLANETILER_VERSION=0.10.2

RUN apt-get update \
 && apt-get install -y --no-install-recommends osmium-tool python3 python3-venv git time curl ca-certificates \
      libsqlite3-0 zlib1g \
 && rm -rf /var/lib/apt/lists/*

# same versions as the venv the region build uses on zo (2026-10)
RUN python3 -m venv /opt/venv \
 && /opt/venv/bin/pip install --no-cache-dir osmium==4.3.2 shapely==2.2.0 pmtiles==3.8.1 numpy==2.5.4 \
      pillow==13.0.0 contourpy==1.4.0 mapbox-vector-tile==2.2.0

RUN curl -fsSL -o /opt/planetiler.jar \
      "https://github.com/onthegomap/planetiler/releases/download/v${PLANETILER_VERSION}/planetiler.jar"

COPY --from=tippecanoe /usr/local/bin/tippecanoe /usr/local/bin/tile-join /usr/local/bin/
RUN tippecanoe --version && command -v tile-join

RUN useradd --create-home --uid 10001 builder
ENV PATH=/opt/venv/bin:$PATH PLANETILER_JAR=/opt/planetiler.jar HOME=/tmp
USER builder
WORKDIR /tmp
