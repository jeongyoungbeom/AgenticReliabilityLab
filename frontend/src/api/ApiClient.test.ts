import { describe, expect, it } from 'vitest'
import { ApiError, formatApiError } from './ApiClient'

const systemDiagnosis = {
  stage: 'SYSTEM' as const,
  summary: '요청을 안전하게 완료하지 못했습니다.',
  likelyCause: 'ARL 내부 상태 또는 현재 실행 조건을 확인해야 합니다.',
  nextAction: '기술 정보를 확인하고, 필요한 설정을 바로잡은 뒤 다시 시도하세요.',
  technicalDetail: 'code=QUICK_OPENAPI_NOT_FOUND · HTTP 400',
}

describe('formatApiError', () => {
  it('keeps the actionable server message and the code for a code without a mapped diagnosis', () => {
    const serverMessage = '등록한 URL에서 지원되는 Swagger/OpenAPI 문서를 찾지 못했습니다. 허용 경로: /v3/api-docs'

    const text = formatApiError(new ApiError(400, 'QUICK_OPENAPI_NOT_FOUND', serverMessage, systemDiagnosis))

    expect(text).toContain(systemDiagnosis.nextAction)
    expect(text).toContain(serverMessage)
    expect(text).toContain('QUICK_OPENAPI_NOT_FOUND')
  })

  it('leads with the Korean guidance and does not repeat a message equal to the summary', () => {
    const diagnosis = { ...systemDiagnosis, stage: 'PREFLIGHT' as const, summary: 'Target에 연결하지 못했습니다.' }

    const text = formatApiError(new ApiError(502, 'TARGET_UNREACHABLE', 'Target에 연결하지 못했습니다.', diagnosis))

    expect(text.startsWith('Target에 연결하지 못했습니다. 예상 원인:')).toBe(true)
    expect(text.match(/Target에 연결하지 못했습니다/g)).toHaveLength(1)
  })

  it('falls back to code and message when the server sent no diagnosis', () => {
    expect(formatApiError(new ApiError(500, 'HTTP_ERROR', 'Request failed with HTTP 500'))).toBe(
      'HTTP_ERROR: Request failed with HTTP 500',
    )
  })
})
