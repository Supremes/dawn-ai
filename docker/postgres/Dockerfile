FROM pgvector/pgvector:0.8.0-pg16

# Install zhparser build dependencies and compile from source
RUN apt-get update && apt-get install -y --no-install-recommends \
        build-essential \
        postgresql-server-dev-16 \
        git \
    && git clone https://github.com/amutu/zhparser.git /tmp/zhparser \
    && cd /tmp/zhparser \
    && make && make install \
    && cd / && rm -rf /tmp/zhparser \
    && apt-get purge -y build-essential postgresql-server-dev-16 git \
    && apt-get autoremove -y \
    && rm -rf /var/lib/apt/lists/*
