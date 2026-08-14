FROM eclipse-temurin:25-jdk-noble@sha256:735baf2edc6cd6485240144a84fa4db142b9a6f47b4eb4080f31058d200f9813

ARG TARGETARCH

RUN apt-get update \
    && DEBIAN_FRONTEND=noninteractive apt-get install --yes --no-install-recommends \
       ca-certificates curl git jq ripgrep unzip xxd zstd \
    && rm -rf /var/lib/apt/lists/*

RUN set -eux; \
    case "${TARGETARCH}" in \
      amd64) \
        lean_archive="lean-4.24.0-linux.tar.zst"; \
        lean_sha="b14f5e5159219dd1a1956c3b806813319f5e94ccd5bdfd56f54520609a5bb5ec" ;; \
      arm64) \
        lean_archive="lean-4.24.0-linux_aarch64.tar.zst"; \
        lean_sha="237f8ef43fb40d16681871fb04b2c56f023afc02a5a3877831d35dd493823c23" ;; \
      *) echo "Unsupported verification image architecture: ${TARGETARCH}" >&2; exit 2 ;; \
    esac; \
    curl --fail --location --silent --show-error --retry 8 --retry-delay 2 \
      --retry-max-time 1800 --retry-all-errors --continue-at - \
      --output "/tmp/${lean_archive}.part" \
      "https://github.com/leanprover/lean4/releases/download/v4.24.0/${lean_archive}"; \
    echo "${lean_sha}  /tmp/${lean_archive}.part" | sha256sum --check -; \
    mkdir -p /tmp/lean-unpacked; \
    tar --use-compress-program=unzstd -xf "/tmp/${lean_archive}.part" -C /tmp/lean-unpacked; \
    lean_dir="$(find /tmp/lean-unpacked -mindepth 1 -maxdepth 1 -type d | head -n 1)"; \
    test -n "${lean_dir}"; \
    mv "${lean_dir}" /opt/lean; \
    rm -rf /tmp/lean-unpacked "/tmp/${lean_archive}.part"; \
    /opt/lean/bin/lean --version | grep '4.24.0'

RUN set -eux; \
    case "${TARGETARCH}" in \
      amd64) \
        z3_archive="z3-4.15.2-x64-glibc-2.39.zip"; \
        z3_sha="85d2da1bf440fca3288874c2a06e23f96d09befcc21b5a7489fe0fa40444e685" ;; \
      arm64) \
        z3_archive="z3-4.15.2-arm64-glibc-2.34.zip"; \
        z3_sha="13ef5c1f91cae46c3de493cd1f98954331e4e7d0850bbbcf208b818d452bf99b" ;; \
      *) echo "Unsupported verification image architecture: ${TARGETARCH}" >&2; exit 2 ;; \
    esac; \
    curl --fail --location --silent --show-error --retry 8 --retry-delay 2 \
      --retry-max-time 900 --retry-all-errors --continue-at - \
      --output "/tmp/${z3_archive}.part" \
      "https://github.com/Z3Prover/z3/releases/download/z3-4.15.2/${z3_archive}"; \
    echo "${z3_sha}  /tmp/${z3_archive}.part" | sha256sum --check -; \
    unzip -q "/tmp/${z3_archive}.part" -d /tmp/z3-unpacked; \
    z3_dir="$(find /tmp/z3-unpacked -mindepth 1 -maxdepth 1 -type d | head -n 1)"; \
    test -n "${z3_dir}"; \
    mv "${z3_dir}" /opt/z3; \
    rm -rf /tmp/z3-unpacked "/tmp/${z3_archive}.part"; \
    /opt/z3/bin/z3 --version | grep '4.15.2'

ENV PATH="/opt/lean/bin:/opt/z3/bin:${PATH}"
WORKDIR /workspace
