# Build the dengine CLI binary from workspace root
FROM rust:latest AS builder

WORKDIR /build

# Install the protobuf compiler
RUN apt-get update && apt-get install -y protobuf-compiler

# Disable sccache: .cargo/config.toml sets rustc-wrapper=sccache for local dev,
# but sccache is not available in CI/Docker.
ENV RUSTC_WRAPPER=""

# Copy d-engine Rust workspace (passed via --build-context rust-workspace=../d-engine)
COPY --from=rust-workspace . .

# client-usage-standalone is excluded from the workspace; build from its own directory
WORKDIR /build/examples/client-usage-standalone
RUN cargo build --release

# Use the JDK 21 base image (official ARM64 image)
FROM eclipse-temurin:21-jdk-jammy

# Install Leiningen
RUN apt-get update && \
    apt-get install -y \
    curl \
    git \
    gcc \
    build-essential \
    openssh-server \
    telnet \
    procps \
    net-tools \
    lsof \
    gnuplot \
    graphviz \
    vim && \
    curl -L https://raw.githubusercontent.com/technomancy/leiningen/stable/bin/lein > /usr/local/bin/lein && \
    chmod +x /usr/local/bin/lein && \
    lein self-install && \
    apt-get clean && \
    rm -rf /var/lib/apt/lists/*

# Install the Rust toolchain
RUN curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y
ENV PATH="/root/.cargo/bin:${PATH}"


# Configure SSH Environment
COPY sshkeys /root/.ssh
RUN chmod 600 /root/.ssh/id_rsa && \
    mkdir /var/run/sshd && \
    echo 'root:root' | chpasswd && \
    sed -i 's/#PermitRootLogin prohibit-password/PermitRootLogin yes/' /etc/ssh/sshd_config

# Set the time zone (Asia/Shanghai)
RUN ln -sf /usr/share/zoneinfo/Asia/Shanghai /etc/localtime && \
    echo "Asia/Shanghai" > /etc/timezone

# Copy project files
WORKDIR /app
COPY java-src java-src
COPY src src
COPY project.clj project.clj

# Install Clojure dependencies and compile Java stubs
RUN lein deps && lein javac

# Copy the compiled Rust binary
COPY --from=builder /build/examples/client-usage-standalone/target/release/client-usage-standalone-demo /usr/local/bin/

EXPOSE 22
CMD ["/bin/sh", "-c", "/usr/sbin/sshd -D && eval $(ssh-agent -s) && ssh-add /root/.ssh/id_rsa"]
