# do. — локальный семейный таск-трекер

Минималистичный веб-трекер для двух участников: задачи, категории, приоритеты, сроки, поиск и назначение ответственного. Frontend — React/TypeScript/Vite, API — Kotlin/Ktor, база — PostgreSQL с миграциями Flyway.

## Требования

- Docker Engine и Docker Compose plugin (для PostgreSQL и API).
- Node.js 18+ и npm.
- Порты `5173`, `5432` и `8080` должны быть свободны. Сервисы публикуются только на `127.0.0.1`.

## Первый запуск

1. В корне проекта создайте локальный файл настроек и задайте уникальные пароли (не короче 10 символов):

   ```sh
   cp .env.example .env
   ```

   Измените `POSTGRES_PASSWORD`, `SEED_OWNER_PASSWORD`, `SEED_DIANA_PASSWORD` и `SESSION_SECRET` (не менее 32 символов). Файл `.env` исключён из Git.

2. Соберите и запустите PostgreSQL и Ktor API:

   ```sh
   docker compose up --build -d
   docker compose ps
   curl http://127.0.0.1:8080/api/health
   ```

   При первом старте Flyway создаст таблицы, приложение добавит двух участников и категории «Дом», «Работа», «Личное». Email и пароли пользователей берутся из `.env`.

3. В другом терминале запустите frontend:

   ```sh
   cd frontend
   npm install
   npm run dev
   ```

   Откройте адрес Vite, обычно <http://127.0.0.1:5173>.

### Вариант без Docker (PostgreSQL из Homebrew)

Если Docker не установлен, можно использовать локальный PostgreSQL 16 (macOS/Homebrew):

```sh
brew install postgresql@16
/opt/homebrew/opt/postgresql@16/bin/pg_ctl -D /opt/homebrew/var/postgresql@16 -l .pg.log start
psql -h 127.0.0.1 -d postgres -c "CREATE ROLE do_user LOGIN PASSWORD 'пароль_из_.env';" -c "CREATE DATABASE do_db OWNER do_user;"
```

Роль и базу создают один раз. Затем из корня проекта:

```sh
set -a; . ./.env; set +a
cd backend
DATABASE_URL="jdbc:postgresql://127.0.0.1:5432/$POSTGRES_DB" DATABASE_USER="$POSTGRES_USER" DATABASE_PASSWORD="$POSTGRES_PASSWORD" ./gradlew run
```

## Деплой на сервер (Ubuntu + Docker)

Серверная конфигурация — `compose.server.yaml`: PostgreSQL и Ktor доступны только внутри Docker-сети, наружу опубликован только nginx на порту 80. Образы собираются из локально собранных артефактов, поэтому на сервере не нужны Gradle и Node.js.

```sh
# локально: собрать артефакты
(cd backend && ./gradlew installDist)
(cd frontend && npm ci && npm run build)

# на сервере: /opt/do-task-tracker должен содержать
#   backend/build/install/do-backend, frontend/dist, nginx/, deploy/,
#   compose.server.yaml, .dockerignore и .env (chmod 600)
cd /opt/do-task-tracker
docker compose -f compose.server.yaml up -d --build
docker compose -f compose.server.yaml ps
curl http://127.0.0.1/api/health
```

После изменений в коде повторите локальную сборку, скопируйте обновлённые артефакты и выполните `docker compose -f compose.server.yaml up -d --build`.

Резервные копии: `deploy/backup.sh` (на сервере — `/opt/do-task-tracker/backup.sh`) создаёт gzip-дамп в `backups/` и удаляет копии старше 7 дней. Пример cron-задания — `/etc/cron.d/do-task-tracker-backup`:

```cron
30 3 * * * root /opt/do-task-tracker/backup.sh >> /var/log/do-backup.log 2>&1
```

Проверка восстановления выполняется в отдельную временную базу, чтобы не затрагивать рабочую:

```sh
docker compose -f compose.server.yaml exec -T db psql -U "$POSTGRES_USER" -d postgres -c "CREATE DATABASE do_restore_test"
gunzip -c backups/do-*.sql.gz | docker compose -f compose.server.yaml exec -T db psql -U "$POSTGRES_USER" -d do_restore_test
docker compose -f compose.server.yaml exec -T db psql -U "$POSTGRES_USER" -d postgres -c "DROP DATABASE do_restore_test"
```

## Остановка и сброс

Остановить контейнеры, сохранив задачи:

```sh
docker compose down
```

Остановить контейнеры и **удалить локальные данные PostgreSQL**:

```sh
docker compose down -v
```

При следующем запуске база и тестовые пользователи будут созданы заново из `.env`.

## Проверки и API

- Health: `GET /api/health`.
- Авторизация: `POST /api/auth/login`, `POST /api/auth/logout`, `GET /api/auth/me`.
- Задачи: `GET/POST /api/tasks`, `GET/PATCH/DELETE /api/tasks/{id}`, `PATCH /api/tasks/{id}/complete|reopen`.
- Справочники: `GET /api/categories`, `GET /api/workspace`, `GET /api/workspace/members`.
- Для записи клиент сначала получает CSRF-токен через `GET /api/auth/csrf` и отправляет его в `X-CSRF-Token`. Сессия — opaque-токен в HttpOnly cookie; в БД хранится его peppered SHA-256 хеш. Пароли хешируются BCrypt.
- Запросы к задачам всегда ограничиваются workspace текущего участника. Нельзя передать workspace ID от клиента.

При изменении deployment-конфигурации для production используйте HTTPS, установите `APP_ENV=production` (включает флаг `Secure` у cookies) и не публикуйте БД/API наружу. Публичная регистрация не предусмотрена.
