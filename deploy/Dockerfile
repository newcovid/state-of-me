FROM python:3.13-slim
WORKDIR /app
COPY server/requirements.txt /app/requirements.txt
RUN pip install --no-cache-dir -r requirements.txt
COPY server/ /app/server/
ENV SOM_DB=/data/state.db SOM_CONFIG=/config/config.json
USER 10001:10001
EXPOSE 8080
CMD ["uvicorn", "server.app:app", "--host", "0.0.0.0", "--port", "8080", "--no-access-log"]
