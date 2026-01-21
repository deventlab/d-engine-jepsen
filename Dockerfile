FROM eclipse-temurin:21-jdk-jammy

RUN apt-get update && \
    apt-get install -y curl git openssh-client procps net-tools gnuplot && \
    curl -L https://raw.githubusercontent.com/technomancy/leiningen/stable/bin/lein > /usr/local/bin/lein && \
    chmod +x /usr/local/bin/lein && \
    lein self-install && \
    apt-get clean && rm -rf /var/lib/apt/lists/*

COPY sshkeys /root/.ssh
RUN chmod 600 /root/.ssh/id_rsa

RUN ln -sf /usr/share/zoneinfo/UTC /etc/localtime && \
    echo "UTC" > /etc/timezone

WORKDIR /app
COPY src src
COPY project.clj project.clj
RUN lein deps

CMD ["tail", "-f", "/dev/null"]
