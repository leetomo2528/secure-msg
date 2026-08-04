# Backend image
FROM python:3.12-slim AS backend
WORKDIR /app
COPY requirements.txt .
RUN python -m pip install --no-cache-dir --upgrade pip==26.2 && \
    pip install --no-cache-dir -r requirements.txt
COPY . .
ENV SECUREMSG_ENV=production
EXPOSE 5000
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
    CMD ["python", "-c", "import urllib.request; urllib.request.urlopen('http://127.0.0.1:5000/health', timeout=3).read()"]
CMD ["gunicorn", "-k", "gthread", "-w", "1", "--threads", "100", \
     "--bind", "0.0.0.0:5000", "--timeout", "120", "app:app"]
