FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    SYNC_DATABASE_URL=sqlite:////data/listen-sync.db

# Override for slow networks, e.g. --build-arg PIP_INDEX_URL=https://mirrors.aliyun.com/pypi/simple/
ARG PIP_INDEX_URL=https://pypi.org/simple

WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir --index-url "$PIP_INDEX_URL" -r requirements.txt
COPY app ./app

RUN mkdir -p /data
VOLUME ["/data"]
EXPOSE 8000

CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000", "--workers", "1"]
