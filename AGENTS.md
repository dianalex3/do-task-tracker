# Repository notes

- `frontend/` and `backend/` are separate apps. `compose.yaml` starts only PostgreSQL and Ktor; start Vite separately. Vite proxies `/api` to `127.0.0.1:8080`.
- Frontend checks: `cd frontend && npm ci && npm run build` (`build` runs TypeScript then Vite).
- Backend checks: `cd backend && ./gradlew build` (JDK 17; wrapper pins Gradle 8.13). There are currently no backend tests (`test` is `NO-SOURCE`).
- Docker is not installed in the current dev environment. A Homebrew PostgreSQL 16 cluster is available as an alternative; see the non-Docker section in `README.md`.
- For a full local run, configure `.env` from `.env.example` before `docker compose up --build -d`; see `README.md`. `docker compose down -v` deletes the local PostgreSQL data volume.
- Database schema changes belong in a new versioned Flyway SQL migration under `backend/src/main/resources/db/migration/`; startup migrates and seeds the two users/categories from `.env`.
- Keep API contracts aligned with `frontend/src/api.ts`: task-list responses are `{ tasks: [...] }`, and task status/priority values are uppercase. Every mutating API request requires the CSRF token from `GET /api/auth/csrf`.
- Server deployment uses `compose.server.yaml` and prebuilt artifacts (`backend/build/install/do-backend`, `frontend/dist`); it is not built from source on the server. Only nginx is exposed (port 80). `deploy/backup.sh` performs the PostgreSQL backup.
