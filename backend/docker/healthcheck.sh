#!/usr/bin/env bash
set -euo pipefail

host="${MOON_HEALTHCHECK_HOST:-127.0.0.1}"
port="${SERVER_PORT:-8080}"

exec 3<>"/dev/tcp/${host}/${port}"
printf 'GET /readyz HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n' >&3
IFS=' ' read -r protocol status_code _ <&3

[[ "$protocol" == HTTP/* && "$status_code" == "200" ]]
