# Builds the lazyfs FUSE binary at /opt/jepsen/lazyfs, matching the paths
# jepsen.lazyfs (bundled with the jepsen library) expects on a "remote" node.
# We build here instead of via jepsen.lazyfs/install! because the node
# containers have no apt lists / internet access at runtime.
#
# The resulting /opt/jepsen/lazyfs directory is shared with node1-5 via the
# lazyfs_bin named volume (see docker-compose.yml): Docker seeds an empty
# named volume from the image's content at first mount, so no explicit copy
# step is needed.
FROM debian:bookworm-slim

RUN apt-get update && apt-get install -y --no-install-recommends \
    g++ cmake make git ca-certificates libfuse3-dev pkg-config && \
    rm -rf /var/lib/apt/lists/*

# Version must match jepsen.lazyfs's `commit` var so paths/behavior line up.
RUN git clone https://github.com/dsrhaslab/lazyfs.git /opt/jepsen/lazyfs && \
    cd /opt/jepsen/lazyfs && \
    git checkout 0.2.0 && \
    git clean -fdx && \
    cd libs/libpcache && ./build.sh && \
    cd ../../lazyfs && ./build.sh

CMD ["true"]
