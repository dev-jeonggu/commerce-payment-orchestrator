FROM docker.elastic.co/logstash/logstash:8.13.0

# PostgreSQL JDBC 드라이버 설치 (payment_logs JDBC 입력에 필요)
RUN curl -fsSL \
    https://jdbc.postgresql.org/download/postgresql-42.7.3.jar \
    -o /usr/share/logstash/drivers/postgresql.jar
