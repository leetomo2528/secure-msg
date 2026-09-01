# Backend image
FROM python:3.12-slim AS backend
# Unprivileged runtime user. The uid/gid are pinned rather than auto-assigned
# because the production volume (secure-msg_backend-data) was created and
# populated by root: the deploy has to chown it to exactly this id once, and a
# drifting id would take the relay down on the next rebuild.
RUN groupadd --system --gid 10001 securemsg && \
    useradd --system --uid 10001 --gid 10001 --no-create-home \
            --shell /usr/sbin/nologin securemsg
WORKDIR /app
COPY requirements.txt .
RUN python -m pip install --no-cache-dir --upgrade pip==26.2 && \
    pip install --no-cache-dir -r requirements.txt
COPY . .
# SQLite creates securemsg.db-wal and -shm NEXT TO the database, so the
# directory itself must be writable — write access to the db file is not
# enough. /app stays root-owned and is only ever read by the runtime user.
# An empty named volume inherits this ownership when Docker initializes it;
# one that already holds data keeps whatever the host dir has (see README).
RUN mkdir -p /data && chown securemsg:securemsg /data
ENV SECUREMSG_ENV=production \
    PYTHONDONTWRITEBYTECODE=1
EXPOSE 5000
USER securemsg
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
    CMD ["python", "-c", "import urllib.request; urllib.request.urlopen('http://127.0.0.1:5000/health', timeout=3).read()"]
CMD ["gunicorn", "-k", "gthread", "-w", "1", "--threads", "100", \
     "--bind", "0.0.0.0:5000", "--timeout", "120", "app:app"]
