#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
mkdir -p .local-work
suffix=$(python3 -c 'import uuid; print(uuid.uuid4().hex[:12])')
recovery_db="campus_restart_$suffix"
recovery_container="campus-restart-$suffix"
created_db=0
cleanup() {
 docker rm -f "$recovery_container" >/dev/null 2>&1 || true
 if [ "$created_db" = 1 ]; then
  docker compose exec -T db mysql -uroot -proot_local -e "DROP DATABASE \`$recovery_db\`; REVOKE ALL ON \`$recovery_db\`.* FROM 'campus'@'%';" >/dev/null 2>&1 || true
 fi
}
trap cleanup EXIT INT TERM
docker compose up -d --wait db
docker compose exec -T db mysql -uroot -proot_local -e "CREATE DATABASE \`$recovery_db\`; GRANT ALL ON \`$recovery_db\`.* TO 'campus'@'%';"
created_db=1
docker run --rm -v "$PWD/backend:/work" -v campus-market-maven:/root/.m2 -w /work maven:3.9-eclipse-temurin-21 mvn -q -DskipTests package
start_backend() {
 docker run -d --name "$recovery_container" -p 127.0.0.1:8081:8080 -v "$PWD/backend/target/market-1.0.0.jar:/app/app.jar:ro" --network campus-market_default -e "DB_URL=jdbc:mysql://db:3306/$recovery_db?connectionTimeZone=UTC" -e UPLOAD_DIR=/tmp/uploads maven:3.9-eclipse-temurin-21 java -jar /app/app.jar >/dev/null
 ready=0
 for attempt in $(seq 1 40); do
  if curl -fsS http://localhost:8081/api/v1/session >/dev/null 2>&1; then ready=1; break; fi
  sleep 1
 done
 if [ "$ready" != 1 ]; then docker logs "$recovery_container"; exit 1; fi
}
start_backend
python3 scripts/recovery-smoke.py http://localhost:8081 prepare .local-work/restart-state.json
docker stop "$recovery_container" >/dev/null
docker rm "$recovery_container" >/dev/null
# Model elapsed downtime in the durable demo clock while no application process exists.
docker compose exec -T db mysql -uroot -proot_local "$recovery_db" -e 'UPDATE clock_state SET offset_millis=172820000 WHERE id=1;'
start_backend
python3 scripts/recovery-smoke.py http://localhost:8081 verify .local-work/restart-state.json
