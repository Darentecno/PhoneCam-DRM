FROM python:3.9-slim
RUN apt-get update && apt-get install -y ffmpeg && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY desktop/requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY desktop/ .
COPY core/ ./core/
CMD ["python", "main.py"]
