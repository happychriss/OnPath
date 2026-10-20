# OnPath cell builder (docs/design/map-cells.md, usage: tools/cells/README.md).
# Runtime only: the pipeline code is mounted from the repo at /onpath (read-only), data at /data.
#   docker build -t onpath-cell-builder -f tools/cells/Dockerfile tools/cells
FROM eclipse-temurin:21-jre-noble

ARG PLANETILER_VERSION=0.10.2

RUN apt-get update \
 && apt-get install -y --no-install-recommends osmium-tool python3 python3-venv git time curl ca-certificates \
 && rm -rf /var/lib/apt/lists/*

# same versions as the venv the region build uses on zo (2026-10)
RUN python3 -m venv /opt/venv \
 && /opt/venv/bin/pip install --no-cache-dir osmium==4.3.2 shapely==2.2.0 pmtiles==3.8.1 numpy==2.5.4 \
      pillow==13.0.0 contourpy==1.4.0 mapbox-vector-tile==2.2.0

RUN curl -fsSL -o /opt/planetiler.jar \
      "https://github.com/onthegomap/planetiler/releases/download/v${PLANETILER_VERSION}/planetiler.jar"

RUN useradd --create-home --uid 10001 builder
ENV PATH=/opt/venv/bin:$PATH PLANETILER_JAR=/opt/planetiler.jar HOME=/tmp
USER builder
WORKDIR /tmp
