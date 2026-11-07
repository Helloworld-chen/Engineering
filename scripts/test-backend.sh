#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
docker compose up -d --wait db
docker compose exec -T db mysql -uroot -proot_local -e "CREATE DATABASE IF NOT EXISTS campus_market_test; GRANT ALL ON campus_market_test.* TO 'campus'@'%';"
docker run --rm -v "$PWD/backend:/work" -v campus-market-maven:/root/.m2 -w /work --network campus-market_default -e DB_URL='jdbc:mysql://db:3306/campus_market_test?connectionTimeZone=UTC' maven:3.9-eclipse-temurin-21 mvn -B test "$@"
