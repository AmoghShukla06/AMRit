# AMRit backend: vision-fallback proxy (holds the OpenRouter key), shared cache, anonymous event store.
FROM python:3.12-slim
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
WORKDIR /srv
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY app.py schema.sql schema_pg.sql ./
# SQLite lives on a mounted volume so events, the shared cache and learned brands survive restarts
ENV AMRIT_DB=/data/amrit_backend.db
RUN mkdir -p /data
EXPOSE 8080
CMD ["sh", "-c", "uvicorn app:app --host 0.0.0.0 --port ${PORT:-8080}"]
