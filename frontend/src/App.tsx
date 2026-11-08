import { useCallback, useEffect, useMemo, useState, type FormEvent } from 'react'
import { CalendarDays, Check, CheckCheck, ChevronDown, CircleHelp, ClipboardList, LoaderCircle, LogOut, Plus, Search, Sparkles, SquarePen, Trash2, X } from 'lucide-react'
import { api, initializeCsrf, type Category, type Task, type TaskInput, type User } from './api'

type Filter = 'ALL' | 'ACTIVE' | 'COMPLETED'

function App() {
  const [user, setUser] = useState<User | null>(null)
  const [initializing, setInitializing] = useState(true)
  const [loginEmail, setLoginEmail] = useState('')
  const [loginPassword, setLoginPassword] = useState('')
  const [loginBusy, setLoginBusy] = useState(false)
  const [loginError, setLoginError] = useState('')
  const [tasks, setTasks] = useState<Task[]>([])
  const [categories, setCategories] = useState<Category[]>([])
  const [members, setMembers] = useState<User[]>([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')
  const [filter, setFilter] = useState<Filter>('ACTIVE')
  const [categoryFilter, setCategoryFilter] = useState('')
  const [assigneeFilter, setAssigneeFilter] = useState('')
  const [query, setQuery] = useState('')
  const [quickTitle, setQuickTitle] = useState('')
  const [quickBusy, setQuickBusy] = useState(false)
  const [editing, setEditing] = useState<Task | null | 'new'>(null)
  const [toast, setToast] = useState('')

  const refresh = useCallback(async () => {
    setLoading(true)
    setError('')
    try {
      const [taskResult, categoryResult, memberResult] = await Promise.all([
        api<{ tasks: Task[] }>('/tasks'),
        api<Category[]>('/categories'),
        api<User[]>('/workspace/members'),
      ])
      setTasks(taskResult.tasks)
      setCategories(categoryResult)
      setMembers(memberResult)
    } catch (e) {
      setError(errorMessage(e))
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    initializeCsrf().then(() => api<User>('/auth/me')).then(setUser)
      .catch(() => setUser(null)).finally(() => setInitializing(false))
  }, [])

  useEffect(() => { if (user) void refresh() }, [user, refresh])

  useEffect(() => {
    if (!toast) return
    const timer = window.setTimeout(() => setToast(''), 2400)
    return () => window.clearTimeout(timer)
  }, [toast])

  const visibleTasks = useMemo(() => tasks.filter(task => {
    if (filter !== 'ALL' && task.status !== filter) return false
    if (categoryFilter && task.category?.id !== categoryFilter) return false
    if (assigneeFilter === 'unassigned' && task.assignee) return false
    if (assigneeFilter && assigneeFilter !== 'unassigned' && task.assignee?.id !== assigneeFilter) return false
    return !query || task.title.toLocaleLowerCase('ru').includes(query.trim().toLocaleLowerCase('ru'))
  }), [tasks, filter, categoryFilter, assigneeFilter, query])
  const activeCount = tasks.filter(task => task.status === 'ACTIVE').length
  const doneCount = tasks.length - activeCount

  async function login(event: FormEvent) {
    event.preventDefault()
    setLoginBusy(true)
    setLoginError('')
    try {
      const result = await api<{ user: User }>('/auth/login', {
        method: 'POST', body: JSON.stringify({ email: loginEmail, password: loginPassword }),
      })
      setUser(result.user)
    } catch (e) { setLoginError(errorMessage(e)) }
    finally { setLoginBusy(false) }
  }

  async function logout() {
    try { await api('/auth/logout', { method: 'POST' }) } catch { /* End local session view even if the network is unavailable. */ }
    setUser(null)
    setTasks([])
  }

  async function quickAdd(event: FormEvent) {
    event.preventDefault()
    const title = quickTitle.trim()
    if (!title || quickBusy) return
    setQuickBusy(true)
    try {
      await api<Task>('/tasks', { method: 'POST', body: JSON.stringify({ title }) })
      setQuickTitle('')
      setToast('Задача добавлена')
      await refresh()
    } catch (e) { setError(errorMessage(e)) }
    finally { setQuickBusy(false) }
  }

  async function toggleTask(task: Task) {
    try {
      await api(`/tasks/${task.id}/${task.status === 'ACTIVE' ? 'complete' : 'reopen'}`, { method: 'PATCH' })
      await refresh()
    } catch (e) { setError(errorMessage(e)) }
  }

  async function removeTask(task: Task) {
    if (!window.confirm(`Удалить задачу «${task.title}»?`)) return
    try {
      await api(`/tasks/${task.id}`, { method: 'DELETE' })
      setToast('Задача удалена')
      await refresh()
    } catch (e) { setError(errorMessage(e)) }
  }

  async function saveTask(input: TaskInput) {
    const isNew = editing === 'new'
    const path = isNew ? '/tasks' : `/tasks/${(editing as Task).id}`
    await api(path, { method: isNew ? 'POST' : 'PATCH', body: JSON.stringify(input) })
    setEditing(null)
    setToast(isNew ? 'Задача добавлена' : 'Изменения сохранены')
    await refresh()
  }

  if (initializing) return <div className="splash"><div className="brand">do<span>.</span></div><LoaderCircle className="spin" size={22} /></div>

  if (!user) return (
    <main className="login-page">
      <section className="login-card">
        <div className="brand">do<span>.</span></div>
        <div className="login-eyebrow"><Sparkles size={14} /> ВАШЕ ОБЩЕЕ ПРОСТРАНСТВО</div>
        <h1>Меньше держать<br />в голове.</h1>
        <p className="muted">Войдите, чтобы увидеть задачи и планы на сегодня.</p>
        <form onSubmit={login} className="login-form">
          <label>Email<input autoComplete="username" type="email" value={loginEmail} onChange={e => setLoginEmail(e.target.value)} placeholder="you@example.com" required /></label>
          <label>Пароль<input autoComplete="current-password" type="password" value={loginPassword} onChange={e => setLoginPassword(e.target.value)} placeholder="Ваш пароль" required /></label>
          {loginError && <div className="form-error">{loginError}</div>}
          <button className="primary-button full-button" disabled={loginBusy}>{loginBusy ? <LoaderCircle className="spin" size={17} /> : 'Войти'}<span>→</span></button>
        </form>
        <div className="login-note"><CircleHelp size={15} /> Доступ только для участников пространства</div>
      </section>
      <div className="login-art"><div className="art-orbit orbit-one"/><div className="art-orbit orbit-two"/><div className="art-note"><Check size={17}/><span>Закончить важное</span><small>Сегодня · Дом</small></div><div className="art-caption">Пространство для<br/>важных мелочей.</div></div>
    </main>
  )

  const dateLabel = new Intl.DateTimeFormat('ru-RU', { weekday: 'long', day: 'numeric', month: 'long' }).format(new Date())
  return (
    <div className="app-shell">
      <aside className="sidebar">
        <a className="brand brand-link" href="#top">do<span>.</span></a>
        <div className="workspace-pill"><span className="workspace-dot"/><span>Наши задачи</span><ChevronDown size={14}/></div>
        <div className="side-label">ПРОСТРАНСТВО</div>
        <nav className="side-nav">
          <button className="nav-item selected" onClick={() => setFilter('ACTIVE')}><ClipboardList size={18}/> Все задачи <span className="nav-count">{activeCount}</span></button>
        </nav>
        <div className="side-label categories-label">КАТЕГОРИИ</div>
        <nav className="side-nav category-nav">
          <button className={`nav-item ${!categoryFilter ? 'selected-soft' : ''}`} onClick={() => setCategoryFilter('')}><span className="category-dot all-dot"/>Все категории</button>
          {categories.map(category => <button key={category.id} className={`nav-item ${categoryFilter === category.id ? 'selected-soft' : ''}`} onClick={() => setCategoryFilter(category.id)}><span className={`category-dot ${category.color}`}/>{category.name}</button>)}
        </nav>
        <div className="sidebar-bottom"><div className="members-label">УЧАСТНИКИ</div><div className="member-stack">{members.map((member, index) => <span key={member.id} title={member.name} className={`avatar avatar-${index}`}>{initials(member.name)}</span>)}<span className="members-text">{members.map(m => m.name).join(' · ')}</span></div><button onClick={logout} className="logout-button"><LogOut size={16}/> Выйти</button></div>
      </aside>

      <main id="top" className="main-content">
        <header className="topbar"><div className="mobile-brand brand">do<span>.</span></div><div className="date-line">{dateLabel}</div><button className="user-menu" onClick={logout}><span className="avatar avatar-self">{initials(user.name)}</span><span className="user-name">{user.name}</span><ChevronDown size={15}/></button></header>
        <section className="content-wrap">
          <div className="greeting-row"><div><div className="overline">ХОРОШИЙ ДЕНЬ, ЧТОБЫ НАЧАТЬ</div><h1>Привет, {firstName(user.name)} <span className="wave">✳</span></h1><p className="greeting-sub">Спокойно разберёмся со всем по порядку.</p></div><div className="stats"><div className="stat-card"><span className="stat-number">{activeCount.toString().padStart(2, '0')}</span><span>осталось</span></div><span className="stat-divider"/><div className="stat-card done-stat"><span className="stat-number">{doneCount.toString().padStart(2, '0')}</span><span>выполнено</span></div></div></div>

          <form className="quick-add" onSubmit={quickAdd}><div className="plus-icon"><Plus size={19}/></div><input value={quickTitle} onChange={e => setQuickTitle(e.target.value)} placeholder="Добавить задачу..." aria-label="Название новой задачи"/><span className="enter-hint">↵</span><button disabled={!quickTitle.trim() || quickBusy} aria-label="Добавить задачу"><Plus size={19}/></button></form>
          {error && <div className="notice-error"><span>{error}</span><button onClick={() => setError('')} aria-label="Закрыть"><X size={16}/></button></div>}

          <div className="list-toolbar"><div className="filter-tabs">{([['ALL', 'Все'], ['ACTIVE', 'Активные'], ['COMPLETED', 'Готово']] as const).map(([key, label]) => <button key={key} onClick={() => setFilter(key)} className={filter === key ? 'tab active-tab' : 'tab'}>{label}{key === 'COMPLETED' && doneCount > 0 && <span className="tab-count">{doneCount}</span>}</button>)}</div><div className="toolbar-actions"><label className="search-box"><Search size={16}/><input value={query} onChange={e => setQuery(e.target.value)} placeholder="Поиск" /></label><select aria-label="Фильтр по ответственному" value={assigneeFilter} onChange={e => setAssigneeFilter(e.target.value)}><option value="">Все участники</option><option value="unassigned">Без ответственного</option>{members.map(member => <option key={member.id} value={member.id}>{member.name}</option>)}</select><button className="new-task-button" onClick={() => setEditing('new')}><Plus size={14}/><span>Новая задача</span></button></div></div>

          {loading ? <div className="list-state"><LoaderCircle className="spin" size={22}/><span>Загружаем задачи...</span></div> : visibleTasks.length === 0 ? <div className="empty-state"><div className="empty-icon"><CheckCheck size={25}/></div><h2>{tasks.length === 0 ? 'Пока здесь пусто' : filter === 'COMPLETED' ? 'Пока ничего не выполнено' : 'Ничего не нашлось'}</h2><p>{tasks.length === 0 ? 'Добавьте первую задачу — и она будет ждать здесь.' : 'Попробуйте изменить фильтры или поиск.'}</p>{tasks.length === 0 && <button className="text-button" onClick={() => document.querySelector<HTMLInputElement>('.quick-add input')?.focus()}>Добавить задачу <span>↗</span></button>}</div> : (
            <div className="task-list">{visibleTasks.map(task => <TaskRow key={task.id} task={task} onToggle={() => void toggleTask(task)} onEdit={() => setEditing(task)} onDelete={() => void removeTask(task)} />)}</div>
          )}
          {filter === 'ACTIVE' && activeCount === 0 && doneCount > 0 && <button className="show-done" onClick={() => setFilter('COMPLETED')}>Посмотреть выполненные задачи <span>→</span></button>}
          <footer className="page-footer"><span>Маленькие шаги — тоже прогресс.</span><span>do. <span className="footer-dot">✳</span></span></footer>
        </section>
      </main>
      {editing !== null && <TaskDialog task={editing === 'new' ? null : editing} categories={categories} members={members} onClose={() => setEditing(null)} onSave={saveTask}/>}
      {toast && <div className="toast"><Check size={16}/>{toast}</div>}
    </div>
  )
}

function TaskRow({ task, onToggle, onEdit, onDelete }: { task: Task; onToggle: () => void; onEdit: () => void; onDelete: () => void }) {
  const complete = task.status === 'COMPLETED'
  const overdue = task.dueDate && task.dueDate < new Date().toISOString().slice(0, 10) && !complete
  return <article className={`task-row ${complete ? 'task-completed' : ''}`}>
    <button className={`check-button ${complete ? 'checked' : ''}`} onClick={onToggle} aria-label={complete ? 'Вернуть в активные' : 'Отметить выполненной'}>{complete && <Check size={15}/>}</button>
    <div className="task-body"><button className="task-title" onClick={onEdit}>{task.title}</button>{task.description && <p className="task-description">{task.description}</p>}<div className="task-meta">{task.category && <span className={`category-tag ${task.category.color}`}><i/>{task.category.name}</span>}{task.priority === 'HIGH' && <span className="priority-tag">Высокий приоритет</span>}{task.dueDate && <span className={`due-tag ${overdue ? 'overdue' : ''}`}><CalendarDays size={13}/>{formatDate(task.dueDate)}</span>}{task.assignee && <span className="assignee-tag"><span className="mini-avatar">{initials(task.assignee.name)}</span>{firstName(task.assignee.name)}</span>}{!task.assignee && <span className="assignee-tag shared-tag">Общая</span>}</div></div>
    <div className="task-actions"><button onClick={onEdit} title="Редактировать" aria-label="Редактировать"><SquarePen size={16}/></button><button onClick={onDelete} title="Удалить" aria-label="Удалить"><Trash2 size={16}/></button></div>
  </article>
}

function TaskDialog({ task, categories, members, onClose, onSave }: { task: Task | null; categories: Category[]; members: User[]; onClose: () => void; onSave: (input: TaskInput) => Promise<void> }) {
  const [title, setTitle] = useState(task?.title ?? '')
  const [description, setDescription] = useState(task?.description ?? '')
  const [categoryId, setCategoryId] = useState(task?.category?.id ?? '')
  const [assigneeId, setAssigneeId] = useState(task?.assignee?.id ?? '')
  const [priority, setPriority] = useState<'NORMAL' | 'HIGH'>(task?.priority ?? 'NORMAL')
  const [dueDate, setDueDate] = useState(task?.dueDate ?? '')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  async function submit(event: FormEvent) {
    event.preventDefault()
    setBusy(true); setError('')
    try { await onSave({ title: title.trim(), description: description.trim() || null, categoryId: categoryId || null, assigneeId: assigneeId || null, priority, dueDate: dueDate || null }) }
    catch (e) { setError(errorMessage(e)) }
    finally { setBusy(false) }
  }
  return <div className="modal-backdrop" onMouseDown={e => { if (e.target === e.currentTarget) onClose() }}><section className="task-dialog" role="dialog" aria-modal="true" aria-labelledby="dialog-title"><div className="dialog-head"><div><div className="overline">{task ? 'ЗАДАЧА' : 'НОВАЯ ЗАДАЧА'}</div><h2 id="dialog-title">{task ? 'Изменить задачу' : 'Что нужно сделать?'}</h2></div><button className="icon-close" onClick={onClose} aria-label="Закрыть"><X size={20}/></button></div><form onSubmit={submit}>
    <label className="field-label">Название<input autoFocus maxLength={300} value={title} onChange={e => setTitle(e.target.value)} placeholder="Например, купить продукты" required /></label>
    <label className="field-label">Описание <span className="optional">необязательно</span><textarea rows={3} value={description} onChange={e => setDescription(e.target.value)} placeholder="Добавьте детали, если нужно" /></label>
    <div className="form-grid"><label className="field-label">Категория<select value={categoryId} onChange={e => setCategoryId(e.target.value)}><option value="">Без категории</option>{categories.map(category => <option key={category.id} value={category.id}>{category.name}</option>)}</select></label><label className="field-label">Ответственный<select value={assigneeId} onChange={e => setAssigneeId(e.target.value)}><option value="">Общая задача</option>{members.map(member => <option key={member.id} value={member.id}>{member.name}</option>)}</select></label></div>
    <div className="form-grid"><label className="field-label">Приоритет<select value={priority} onChange={e => setPriority(e.target.value as 'NORMAL' | 'HIGH')}><option value="NORMAL">Обычный</option><option value="HIGH">Высокий</option></select></label><label className="field-label">Срок <span className="optional">необязательно</span><input type="date" value={dueDate} onChange={e => setDueDate(e.target.value)} /></label></div>
    {error && <div className="form-error">{error}</div>}<div className="dialog-actions"><button type="button" className="secondary-button" onClick={onClose}>Отмена</button><button className="primary-button" disabled={busy || !title.trim()}>{busy ? <LoaderCircle className="spin" size={16}/> : task ? 'Сохранить' : 'Добавить задачу'}<span>→</span></button></div>
  </form></section></div>
}

function errorMessage(error: unknown) { return error instanceof Error ? error.message : 'Произошла ошибка. Попробуйте ещё раз.' }
function initials(name: string) { return name.trim().split(/\s+/).slice(0, 2).map(part => part[0]?.toUpperCase()).join('') }
function firstName(name: string) { return name.trim().split(/\s+/)[0] }
function formatDate(value: string) { return new Intl.DateTimeFormat('ru-RU', { day: 'numeric', month: 'short' }).format(new Date(`${value}T00:00:00`)) }

export default App
