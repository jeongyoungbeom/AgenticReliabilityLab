export type AccessRole = 'viewer' | 'profileEditor' | 'executor'

export type AccessTokens = Record<AccessRole, string>

export type FailureStage = 'CREDENTIALS' | 'PREFLIGHT' | 'EXECUTION' | 'CLEANUP' | 'RECOVERY' | 'CONFIGURATION' | 'SYSTEM'

export interface FailureDiagnosis {
  stage: FailureStage
  summary: string
  likelyCause: string
  nextAction: string
  technicalDetail: string
}

export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
    readonly diagnosis: FailureDiagnosis | null = null,
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
    const problem = (await response.json()) as { code?: string; message?: string; diagnosis?: FailureDiagnosis }
    return new ApiError(response.status, problem.code ?? 'HTTP_ERROR', problem.message ?? fallback, problem.diagnosis ?? null)
  } catch {
    return new ApiError(response.status, 'HTTP_ERROR', fallback)
  }
}

/**
 * Leads with the server's Korean action guidance, then keeps the server message and the stable code as
 * supporting detail. Only a handful of codes have a mapped diagnosis; for the rest the generic guidance says
 * "check the technical information", so the message and the code have to stay on screen for it to mean anything.
 */
export function formatApiError(error: ApiError): string {
  if (!error.diagnosis) return `${error.code}: ${error.message}`
  const { summary, likelyCause, nextAction, technicalDetail } = error.diagnosis
  const parts = [summary, `예상 원인: ${likelyCause}`, `다음 행동: ${nextAction}`]
  if (error.message && error.message !== summary) parts.push(`서버 메시지: ${error.message}`)
  if (technicalDetail) parts.push(`기술 정보: ${technicalDetail}`)
  return parts.join(' ')
}

export function newIdempotencyKey(scope: string): string {
  const id = globalThis.crypto?.randomUUID?.() ?? `${Date.now()}-${Math.random().toString(36).slice(2)}`
  return `${scope}-${id}`
}
