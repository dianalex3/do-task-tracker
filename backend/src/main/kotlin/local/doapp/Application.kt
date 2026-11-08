package local.doapp

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.*
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json
import org.mindrot.jbcrypt.BCrypt
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

private const val SESSION_COOKIE = "do_session"
private const val CSRF_COOKIE = "do_csrf"
private val random = SecureRandom()

fun main() {
    require((System.getenv("SESSION_SECRET") ?: "").length >= 32) { "SESSION_SECRET must contain at least 32 characters" }
    Database.start()
    embeddedServer(Netty, port = (System.getenv("PORT") ?: "8080").toInt(), host = "0.0.0.0", module = Application::module).start(wait = true)
}

fun Application.module() {
    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = true }) }
    install(StatusPages) {
        exception<CsrfException> { call, _ -> call.respond(HttpStatusCode.Forbidden, ApiError(ErrorBody("CSRF_FAILED", "Обновите страницу и повторите действие"))) }
        exception<Throwable> { call, cause ->
            when (cause) {
                is IllegalArgumentException, is BadRequestException -> call.respond(HttpStatusCode.BadRequest, ApiError(ErrorBody("VALIDATION_ERROR", cause.message ?: "Некорректные данные")))
                else -> {
                    call.application.log.error("Request failed", cause)
                    call.respond(HttpStatusCode.InternalServerError, ApiError(ErrorBody("INTERNAL_ERROR", "Внутренняя ошибка сервера")))
                }
            }
        }
    }
    routing {
        route("/api") {
            get("/health") { call.respond(mapOf("status" to "ok")) }
            get("/auth/csrf") {
                val token = randomToken()
                call.response.headers.append(HttpHeaders.SetCookie, "$CSRF_COOKIE=$token; Path=/; SameSite=Strict; Max-Age=86400${if (isProduction()) "; Secure" else ""}")
                call.respond(CsrfResponse(token))
            }
            post("/auth/login") {
                call.requireCsrf()
                val input = call.receive<LoginRequest>()
                val user = Database.findUser(input.email)
                if (user == null || !BCrypt.checkpw(input.password, user.passwordHash)) {
                    call.respond(HttpStatusCode.Unauthorized, ApiError(ErrorBody("INVALID_CREDENTIALS", "Неверный email или пароль")))
                    return@post
                }
                call.request.cookies[SESSION_COOKIE]?.let { Database.deleteSession(sha256(it)) }
                val sessionToken = randomToken()
                Database.createSession(user.id, sha256(sessionToken))
                call.response.headers.append(HttpHeaders.SetCookie, "$SESSION_COOKIE=$sessionToken; Path=/; HttpOnly; SameSite=Lax; Max-Age=1209600${if (isProduction()) "; Secure" else ""}")
                call.respond(LoginResponse(UserDto(user.id.toString(), user.name, user.email)))
            }
            post("/auth/logout") {
                call.requireCsrf()
                call.request.cookies[SESSION_COOKIE]?.let { Database.deleteSession(sha256(it)) }
                call.response.headers.append(HttpHeaders.SetCookie, "$SESSION_COOKIE=; Path=/; HttpOnly; SameSite=Lax; Max-Age=0${if (isProduction()) "; Secure" else ""}")
                call.respond(HttpStatusCode.NoContent)
            }
            get("/auth/me") {
                val user = call.currentUser()
                if (user == null) call.respond(HttpStatusCode.Unauthorized, ApiError(ErrorBody("UNAUTHENTICATED", "Войдите, чтобы продолжить")))
                else call.respond(UserDto(user.id, user.name, user.email))
            }
            get("/tasks") {
                val user = call.requireUser() ?: return@get
                val tasks = Database.tasks(user.id, call.request.queryParameters["status"], call.request.queryParameters["categoryId"], call.request.queryParameters["assigneeId"], call.request.queryParameters["q"])
                call.respond(TaskListResponse(tasks))
            }
            post("/tasks") {
                call.requireCsrf()
                val user = call.requireUser() ?: return@post
                val task = Database.saveTask(user.id, null, call.receive<TaskRequest>())
                if (task == null) call.respond(HttpStatusCode.Forbidden, ApiError(ErrorBody("FORBIDDEN", "Нет доступа к пространству"))) else call.respond(HttpStatusCode.Created, task)
            }
            get("/tasks/{id}") {
                val user = call.requireUser() ?: return@get
                val id = parseId(call.parameters["id"]) ?: run { call.respond(HttpStatusCode.BadRequest, ApiError(ErrorBody("VALIDATION_ERROR", "Некорректный идентификатор задачи"))); return@get }
                val task = Database.task(user.id, id)
                if (task == null) call.respond(HttpStatusCode.NotFound, ApiError(ErrorBody("NOT_FOUND", "Задача не найдена"))) else call.respond(task)
            }
            patch("/tasks/{id}") {
                call.requireCsrf()
                val user = call.requireUser() ?: return@patch
                val id = parseId(call.parameters["id"]) ?: run { call.respond(HttpStatusCode.BadRequest, ApiError(ErrorBody("VALIDATION_ERROR", "Некорректный идентификатор задачи"))); return@patch }
                val task = Database.saveTask(user.id, id, call.receive<TaskRequest>())
                if (task == null) call.respond(HttpStatusCode.NotFound, ApiError(ErrorBody("NOT_FOUND", "Задача не найдена"))) else call.respond(task)
            }
            delete("/tasks/{id}") {
                call.requireCsrf()
                val user = call.requireUser() ?: return@delete
                val id = parseId(call.parameters["id"]) ?: run { call.respond(HttpStatusCode.BadRequest, ApiError(ErrorBody("VALIDATION_ERROR", "Некорректный идентификатор задачи"))); return@delete }
                if (!Database.deleteTask(user.id, id)) call.respond(HttpStatusCode.NotFound, ApiError(ErrorBody("NOT_FOUND", "Задача не найдена"))) else call.respond(HttpStatusCode.NoContent)
            }
            patch("/tasks/{id}/complete") { call.setTaskStatus(true, call.parameters["id"]) }
            patch("/tasks/{id}/reopen") { call.setTaskStatus(false, call.parameters["id"]) }
            get("/categories") {
                val user = call.requireUser() ?: return@get
                call.respond(Database.categories(user.id))
            }
            get("/workspace") {
                val user = call.requireUser() ?: return@get
                val workspace = Database.workspace(user.id)
                if (workspace == null) call.respond(HttpStatusCode.Forbidden, ApiError(ErrorBody("FORBIDDEN", "Нет доступа к пространству")))
                else call.respond(WorkspaceDto(workspace.first.toString(), workspace.second))
            }
            get("/workspace/members") {
                val user = call.requireUser() ?: return@get
                call.respond(Database.members(user.id))
            }
        }
    }
}

private suspend fun ApplicationCall.currentUser(): MemberDto? {
    val token = request.cookies[SESSION_COOKIE] ?: return null
    return Database.sessionUser(sha256(token))
}

private suspend fun ApplicationCall.requireUser(): AuthUser? {
    val token = request.cookies[SESSION_COOKIE]
    if (token == null) {
        respond(HttpStatusCode.Unauthorized, ApiError(ErrorBody("UNAUTHENTICATED", "Войдите, чтобы продолжить")))
        return null
    }
    val user = Database.sessionUser(sha256(token))
    if (user == null) {
        respond(HttpStatusCode.Unauthorized, ApiError(ErrorBody("UNAUTHENTICATED", "Сессия истекла")))
        return null
    }
    return AuthUser(UUID.fromString(user.id), user.name, user.email)
}

private suspend fun ApplicationCall.requireCsrf() {
    val cookie = request.cookies[CSRF_COOKIE]
    val header = request.headers["X-CSRF-Token"]
    if (cookie.isNullOrBlank() || header.isNullOrBlank() || !MessageDigest.isEqual(cookie.toByteArray(), header.toByteArray())) {
        throw CsrfException()
    }
}

private suspend fun ApplicationCall.setTaskStatus(completed: Boolean, rawId: String?) {
    requireCsrf()
    val user = requireUser() ?: return
    val id = parseId(rawId) ?: run { respond(HttpStatusCode.BadRequest, ApiError(ErrorBody("VALIDATION_ERROR", "Некорректный идентификатор задачи"))); return }
    if (!Database.setCompleted(user.id, id, completed)) respond(HttpStatusCode.NotFound, ApiError(ErrorBody("NOT_FOUND", "Задача не найдена"))) else respond(HttpStatusCode.NoContent)
}

private fun parseId(value: String?): UUID? = try { value?.let(UUID::fromString) } catch (_: IllegalArgumentException) { null }
private fun randomToken(): String = ByteArray(32).also(random::nextBytes).let(Base64.getUrlEncoder().withoutPadding()::encodeToString)
private fun sha256(value: String): String {
    val pepper = System.getenv("SESSION_SECRET") ?: error("SESSION_SECRET must be set")
    return MessageDigest.getInstance("SHA-256").digest("$pepper:$value".toByteArray()).joinToString("") { "%02x".format(it) }
}
private fun isProduction() = env("APP_ENV", "development") == "production"
private data class AuthUser(val id: UUID, val name: String, val email: String)
private class CsrfException : RuntimeException()
