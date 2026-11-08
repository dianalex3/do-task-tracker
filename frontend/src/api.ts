export type User = { id: string; name: string; email: string }
export type Category = { id: string; name: string; color: string }
export type Task = {
  id: string
  title: string
  description: string | null
  status: 'ACTIVE' | 'COMPLETED'
  priority: 'NORMAL' | 'HIGH'
  category: Category | null
  assignee: User | null
  dueDate: string | null
  createdAt: string
  updatedAt: string
  completedAt: string | null
}
export type TaskInput = {
  title: string
  description: string | null
  categoryId: string | null
  assigneeId: string | null
  priority: 'NORMAL' | 'HIGH'
  dueDate: string | null
}

let csrfToken = ''

export async function initializeCsrf() {
  const response = await fetch('/api/auth/csrf', { credentials: 'same-origin' })
  const body = await response.json()
  csrfToken = body.token as string
}

export async function api<T>(path: string, options: RequestInit = {}): Promise<T> {
  const method = (options.method ?? 'GET').toUpperCase()
  const headers = new Headers(options.headers)
  if (options.body) headers.set('Content-Type', 'application/json')
  if (method !== 'GET' && method !== 'HEAD') headers.set('X-CSRF-Token', csrfToken)
  const response = await fetch(`/api${path}`, { ...options, headers, credentials: 'same-origin' })
  if (response.status === 204) return undefined as T
  const result = await response.json().catch(() => ({}))
  if (!response.ok) throw new Error(result?.error?.message ?? 'Не удалось выполнить запрос')
  return result as T
}
