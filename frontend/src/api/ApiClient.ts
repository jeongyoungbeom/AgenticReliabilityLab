export type AccessRole = 'viewer' | 'profileEditor' | 'executor'

export type AccessTokens = Record<AccessRole, string>

export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
  ) {
    super(message)
  }
}

export class ApiClient {
  constructor(private readonly tokens: AccessTokens) {}

  get<T>(path: string, role: AccessRole = 'viewer'): Promise<T> {
    return this.request<T>(path, { method: 'GET' }, role)
  }

  post<T>(
    path: string,
    body: unknown,
    role: AccessRole,
    idempotencyKey?: string,
    extraHeaders?: HeadersInit,
  ): Promise<T> {
    const headers = new Headers(extraHeaders)
    headers.set('Content-Type', 'application/json')
    if (idempotencyKey) headers.set('Idempotency-Key', idempotencyKey)
    return this.request<T>(
      path,
      { method: 'POST', headers, body: JSON.stringify(body) },
      role,
    )
  }

  put<T>(path: string, body: unknown, role: AccessRole, extraHeaders?: HeadersInit): Promise<T> {
    const headers = new Headers(extraHeaders)
    headers.set('Content-Type', 'application/json')
    return this.request<T>(
      path,
      { method: 'PUT', headers, body: JSON.stringify(body) },
      role,
    )
  }

  delete<T>(path: string, role: AccessRole, extraHeaders?: HeadersInit): Promise<T> {
    return this.request<T>(path, { method: 'DELETE', headers: extraHeaders }, role)
  }

  private async request<T>(path: string, init: RequestInit, role: AccessRole): Promise<T> {
    const headers = new Headers(init.headers)
    const token = this.tokens[role].trim()
    if (token) headers.set('Authorization', `Bearer ${token}`)

    const response = await fetch(path, { ...init, headers })
    if (!response.ok) throw await toApiError(response)
    return (await response.json()) as T
  }
}

async function toApiError(response: Response): Promise<ApiError> {
  const fallback = `Request failed with HTTP ${response.status}`
  try {
    const problem = (await response.json()) as { code?: string; message?: string }
    return new ApiError(response.status, problem.code ?? 'HTTP_ERROR', problem.message ?? fallback)
  } catch {
    return new ApiError(response.status, 'HTTP_ERROR', fallback)
  }
}

export function newIdempotencyKey(scope: string): string {
  const id = globalThis.crypto?.randomUUID?.() ?? `${Date.now()}-${Math.random().toString(36).slice(2)}`
  return `${scope}-${id}`
}
