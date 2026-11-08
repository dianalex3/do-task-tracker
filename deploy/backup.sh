#!/bin/sh
set -eu
cd /opt/do-task-tracker
. ./.env
mkdir -p backups
file="backups/do-$(date +%Y%m%d-%H%M).sql.gz"
docker compose -f compose.server.yaml exec -T db pg_dump -U "$POSTGRES_USER" "$POSTGRES_DB" | gzip > "$file"
find backups -name 'do-*.sql.gz' -mtime +7 -delete
echo "backup created: $file"
