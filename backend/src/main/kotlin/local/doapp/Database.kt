package local.doapp

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.mindrot.jbcrypt.BCrypt
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

object Database {
    private lateinit var pool: HikariDataSource

    fun start() {
        val url = env("DATABASE_URL", "jdbc:postgresql://localhost:5432/do_db")
        pool = HikariDataSource(HikariConfig().apply {
            jdbcUrl = url
            username = env("DATABASE_USER", "do_user")
            password = env("DATABASE_PASSWORD", "")
            maximumPoolSize = 8
            driverClassName = "org.postgresql.Driver"
        })
        Flyway.configure().dataSource(pool).locations("classpath:db/migration").load().migrate()
        seed()
    }

    fun <T> use(block: (Connection) -> T): T = pool.connection.use(block)

    private fun seed() = use { db ->
        db.autoCommit = false
        try {
            val owner = seedUser(db, "SEED_OWNER_NAME", "Владимир", "SEED_OWNER_EMAIL", "owner@example.local", "SEED_OWNER_PASSWORD")
            val diana = seedUser(db, "SEED_DIANA_NAME", "Диана", "SEED_DIANA_EMAIL", "diana@example.local", "SEED_DIANA_PASSWORD")
            var workspaceId = db.prepareStatement("SELECT id FROM workspaces ORDER BY created_at LIMIT 1").use { statement ->
                statement.executeQuery().use { rs -> if (rs.next()) rs.getObject(1, UUID::class.java) else null }
            }
            if (workspaceId == null) {
                workspaceId = UUID.randomUUID()
                db.prepareStatement("INSERT INTO workspaces(id, name) VALUES (?, ?)").use {
                    it.setObject(1, workspaceId); it.setString(2, "Наши задачи"); it.executeUpdate()
                }
            }
            listOf(owner to "owner", diana to "member").forEach { (userId, role) ->
                db.prepareStatement("INSERT INTO workspace_members(workspace_id, user_id, role) VALUES (?, ?, ?) ON CONFLICT DO NOTHING").use {
                    it.setObject(1, workspaceId); it.setObject(2, userId); it.setString(3, role); it.executeUpdate()
                }
            }
            listOf("Дом" to "green", "Работа" to "blue", "Личное" to "violet").forEach { (name, color) ->
                db.prepareStatement("INSERT INTO categories(id, workspace_id, name, color) VALUES (?, ?, ?, ?) ON CONFLICT (workspace_id, name) DO NOTHING").use {
                    it.setObject(1, UUID.randomUUID()); it.setObject(2, workspaceId); it.setString(3, name); it.setString(4, color); it.executeUpdate()
                }
            }
            db.commit()
        } catch (error: Throwable) {
            db.rollback()
            throw error
        } finally { db.autoCommit = true }
    }

    private fun seedUser(db: Connection, nameKey: String, defaultName: String, emailKey: String, defaultEmail: String, passwordKey: String): UUID {
        val name = env(nameKey, defaultName)
        val email = env(emailKey, defaultEmail).trim().lowercase()
        val password = System.getenv(passwordKey)?.takeIf(String::isNotBlank)
            ?: error("$passwordKey must be set; configure it in .env")
        require(password.length >= 10) { "$passwordKey must contain at least 10 characters" }
        db.prepareStatement("SELECT id FROM users WHERE email = ?").use { statement ->
            statement.setString(1, email)
            statement.executeQuery().use { rs -> if (rs.next()) return rs.getObject(1, UUID::class.java) }
        }
        val id = UUID.randomUUID()
        db.prepareStatement("INSERT INTO users(id, name, email, password_hash) VALUES (?, ?, ?, ?)").use {
            it.setObject(1, id); it.setString(2, name); it.setString(3, email); it.setString(4, BCrypt.hashpw(password, BCrypt.gensalt(12))); it.executeUpdate()
        }
        return id
    }

    fun findUser(email: String): SeededUser? = use { db ->
        db.prepareStatement("SELECT id, name, email, password_hash FROM users WHERE email = ?").use { statement ->
            statement.setString(1, email.trim().lowercase())
            statement.executeQuery().use { rs -> if (rs.next()) SeededUser(rs.getObject("id", UUID::class.java), rs.getString("name"), rs.getString("email"), rs.getString("password_hash")) else null }
        }
    }

    fun createSession(userId: UUID, tokenHash: String) = use { db ->
        db.prepareStatement("INSERT INTO sessions(token_hash, user_id, expires_at) VALUES (?, ?, ?)").use {
            it.setString(1, tokenHash); it.setObject(2, userId); it.setTimestamp(3, Timestamp.from(Instant.now().plusSeconds(60L * 60 * 24 * 14))); it.executeUpdate()
        }
    }

    fun sessionUser(tokenHash: String): MemberDto? = use { db ->
        db.prepareStatement("SELECT u.id, u.name, u.email FROM sessions s JOIN users u ON u.id=s.user_id WHERE s.token_hash=? AND s.expires_at > now()").use { statement ->
            statement.setString(1, tokenHash); statement.executeQuery().use { rs -> if (rs.next()) rs.member() else null }
        }
    }

    fun deleteSession(tokenHash: String) = use { db -> db.prepareStatement("DELETE FROM sessions WHERE token_hash=?").use { it.setString(1, tokenHash); it.executeUpdate() } }

    fun workspace(userId: UUID): Pair<UUID, String>? = use { db ->
        db.prepareStatement("SELECT w.id, w.name FROM workspaces w JOIN workspace_members m ON m.workspace_id=w.id WHERE m.user_id=? ORDER BY w.created_at LIMIT 1").use { statement ->
            statement.setObject(1, userId); statement.executeQuery().use { rs -> if (rs.next()) rs.getObject(1, UUID::class.java) to rs.getString(2) else null }
        }
    }

    fun members(userId: UUID): List<MemberDto> = use { db ->
        val workspace = workspaceId(db, userId) ?: return@use emptyList()
        db.prepareStatement("SELECT u.id, u.name, u.email FROM users u JOIN workspace_members m ON m.user_id=u.id WHERE m.workspace_id=? ORDER BY u.name").use { statement ->
            statement.setObject(1, workspace); statement.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.member()) } }
        }
    }

    fun categories(userId: UUID): List<CategoryDto> = use { db ->
        val workspace = workspaceId(db, userId) ?: return@use emptyList()
        db.prepareStatement("SELECT id, name, color FROM categories WHERE workspace_id=? ORDER BY name").use { statement ->
            statement.setObject(1, workspace); statement.executeQuery().use { rs -> buildList { while (rs.next()) add(CategoryDto(rs.getObject("id", UUID::class.java).toString(), rs.getString("name"), rs.getString("color"))) } }
        }
    }

    fun tasks(userId: UUID, status: String?, categoryId: String?, assigneeId: String?, q: String?): List<TaskDto> = use { db ->
        val workspace = workspaceId(db, userId) ?: return@use emptyList()
        val sql = StringBuilder("""
            SELECT t.id, t.title, t.description, t.status, t.priority, t.due_date, t.created_at, t.updated_at, t.completed_at,
                   c.id AS c_id, c.name AS c_name, c.color AS c_color,
                   u.id AS u_id, u.name AS u_name, u.email AS u_email
            FROM tasks t LEFT JOIN categories c ON c.id=t.category_id
            LEFT JOIN users u ON u.id=t.assignee_id
            WHERE t.workspace_id=?
        """.trimIndent())
        val values = mutableListOf<Any>(workspace)
        val normalizedStatus = status?.trim()?.uppercase()
        require(normalizedStatus.isNullOrEmpty() || normalizedStatus in setOf("ALL", "ACTIVE", "COMPLETED")) { "Некорректный статус" }
        if (!normalizedStatus.isNullOrEmpty() && normalizedStatus != "ALL") { sql.append(" AND t.status=?"); values.add(normalizedStatus) }
        if (!categoryId.isNullOrBlank()) { sql.append(" AND t.category_id=?"); values.add(UUID.fromString(categoryId)) }
        if (!assigneeId.isNullOrBlank()) {
            if (assigneeId == "unassigned") sql.append(" AND t.assignee_id IS NULL")
            else { sql.append(" AND t.assignee_id=?"); values.add(UUID.fromString(assigneeId)) }
        }
        if (!q.isNullOrBlank()) { sql.append(" AND t.title ILIKE ?"); values.add("%${q.trim()}%") }
        sql.append(" ORDER BY CASE WHEN t.status='ACTIVE' THEN 0 ELSE 1 END, t.due_date NULLS LAST, t.created_at DESC")
        db.prepareStatement(sql.toString()).use { statement ->
            values.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
            statement.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.task()) } }
        }
    }

    fun task(userId: UUID, id: UUID): TaskDto? = tasks(userId, null, null, null, null).firstOrNull { it.id == id.toString() }

    fun saveTask(userId: UUID, id: UUID?, input: TaskRequest): TaskDto? = use { db ->
        val workspace = workspaceId(db, userId) ?: return@use null
        val title = input.title.trim()
        require(title.isNotEmpty()) { "Название задачи обязательно" }
        require(title.length <= 300) { "Название не должно превышать 300 символов" }
        val priority = input.priority.uppercase()
        require(priority in setOf("NORMAL", "HIGH")) { "Некорректный приоритет" }
        require((input.description?.length ?: 0) <= 5_000) { "Описание не должно превышать 5000 символов" }
        val category = input.categoryId?.takeIf(String::isNotBlank)?.let(UUID::fromString)
        val assignee = input.assigneeId?.takeIf(String::isNotBlank)?.let(UUID::fromString)
        val dueDate = input.dueDate?.takeIf(String::isNotBlank)?.let(LocalDate::parse)
        if (category != null) db.prepareStatement("SELECT 1 FROM categories WHERE id=? AND workspace_id=?").use { it.setObject(1, category); it.setObject(2, workspace); require(it.executeQuery().next()) { "Категория не найдена" } }
        if (assignee != null) db.prepareStatement("SELECT 1 FROM workspace_members WHERE user_id=? AND workspace_id=?").use { it.setObject(1, assignee); it.setObject(2, workspace); require(it.executeQuery().next()) { "Участник не найден" } }
        if (id == null) {
            val newId = UUID.randomUUID()
            db.prepareStatement("INSERT INTO tasks(id, workspace_id, created_by, title, description, category_id, assignee_id, priority, due_date) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)").use {
                it.setObject(1, newId); it.setObject(2, workspace); it.setObject(3, userId); it.setString(4, title); it.setString(5, input.description?.trim()?.takeIf(String::isNotEmpty)); it.setObject(6, category); it.setObject(7, assignee); it.setString(8, priority); it.setObject(9, dueDate); it.executeUpdate()
            }
            task(userId, newId)
        } else {
            val updated = db.prepareStatement("UPDATE tasks SET title=?, description=?, category_id=?, assignee_id=?, priority=?, due_date=?, updated_at=now() WHERE id=? AND workspace_id=?").use {
                it.setString(1, title); it.setString(2, input.description?.trim()?.takeIf(String::isNotEmpty)); it.setObject(3, category); it.setObject(4, assignee); it.setString(5, priority); it.setObject(6, dueDate); it.setObject(7, id); it.setObject(8, workspace); it.executeUpdate()
            }
            if (updated == 0) null else task(userId, id)
        }
    }

    fun setCompleted(userId: UUID, id: UUID, completed: Boolean): Boolean = use { db ->
        val workspace = workspaceId(db, userId) ?: return@use false
        db.prepareStatement("UPDATE tasks SET status=?, completed_at=?, updated_at=now() WHERE id=? AND workspace_id=?").use {
            it.setString(1, if (completed) "COMPLETED" else "ACTIVE"); it.setTimestamp(2, if (completed) Timestamp.from(Instant.now()) else null); it.setObject(3, id); it.setObject(4, workspace); it.executeUpdate() > 0
        }
    }

    fun deleteTask(userId: UUID, id: UUID): Boolean = use { db ->
        val workspace = workspaceId(db, userId) ?: return@use false
        db.prepareStatement("DELETE FROM tasks WHERE id=? AND workspace_id=?").use { it.setObject(1, id); it.setObject(2, workspace); it.executeUpdate() > 0 }
    }

    private fun workspaceId(db: Connection, userId: UUID): UUID? = db.prepareStatement("SELECT workspace_id FROM workspace_members WHERE user_id=? ORDER BY workspace_id LIMIT 1").use {
        it.setObject(1, userId); it.executeQuery().use { rs -> if (rs.next()) rs.getObject(1, UUID::class.java) else null }
    }

    private fun ResultSet.member() = MemberDto(getObject("id", UUID::class.java).toString(), getString("name"), getString("email"))
    private fun ResultSet.task(): TaskDto {
        val category = getObject("c_id", UUID::class.java)?.let { CategoryDto(it.toString(), getString("c_name"), getString("c_color")) }
        val assignee = getObject("u_id", UUID::class.java)?.let { MemberDto(it.toString(), getString("u_name"), getString("u_email")) }
        return TaskDto(getObject("id", UUID::class.java).toString(), getString("title"), getString("description"), getString("status"), getString("priority"), category, assignee,
            getDate("due_date")?.toLocalDate()?.toString(), getTimestamp("created_at").toInstant().toString(), getTimestamp("updated_at").toInstant().toString(), getTimestamp("completed_at")?.toInstant()?.toString())
    }
}

data class SeededUser(val id: UUID, val name: String, val email: String, val passwordHash: String)
fun env(key: String, fallback: String): String = System.getenv(key)?.takeIf(String::isNotBlank) ?: fallback
