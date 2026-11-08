package local.doapp

import kotlinx.serialization.Serializable

@Serializable data class ApiError(val error: ErrorBody)
@Serializable data class ErrorBody(val code: String, val message: String)
@Serializable data class UserDto(val id: String, val name: String, val email: String)
@Serializable data class LoginRequest(val email: String, val password: String)
@Serializable data class LoginResponse(val user: UserDto)
@Serializable data class CsrfResponse(val token: String)
@Serializable data class CategoryDto(val id: String, val name: String, val color: String)
@Serializable data class MemberDto(val id: String, val name: String, val email: String)
@Serializable data class WorkspaceDto(val id: String, val name: String)
@Serializable data class TaskDto(
    val id: String,
    val title: String,
    val description: String?,
    val status: String,
    val priority: String,
    val category: CategoryDto?,
    val assignee: MemberDto?,
    val dueDate: String?,
    val createdAt: String,
    val updatedAt: String,
    val completedAt: String?
)
@Serializable data class TaskRequest(
    val title: String,
    val description: String? = null,
    val categoryId: String? = null,
    val assigneeId: String? = null,
    val priority: String = "NORMAL",
    val dueDate: String? = null
)
@Serializable data class TaskListResponse(val tasks: List<TaskDto>)
