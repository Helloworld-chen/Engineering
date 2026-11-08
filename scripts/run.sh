#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
docker compose up -d --build
printf '\n校园集市：http://localhost:5173\n后端：http://localhost:8080/api/v1/session\n演示账号：buyer / seller / other / admin / support\n演示密码：Campus123!\n'
