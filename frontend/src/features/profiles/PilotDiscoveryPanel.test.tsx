import { render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiClient } from '../../api/ApiClient'
import type { PilotDiscovery } from '../../api/pilotDiscovery'
import { PilotDiscoveryPanel } from './PilotDiscoveryPanel'

describe('PilotDiscoveryPanel', () => {
  afterEach(() => vi.restoreAllMocks())

  it('allowlist 교집합과 네 기본 후보의 준비 상태를 함께 보여 준다', async () => {
    const api = new ApiClient({ viewer: '', profileEditor: '', executor: '' })
    vi.spyOn(api, 'get').mockResolvedValue(discovery() as never)

    render(<PilotDiscoveryPanel api={api} targetSystemId="sideproject-local" refreshKey={0} />)

    expect(await screen.findByText('Health와 상품 카탈로그 가용성')).toBeInTheDocument()
    expect(screen.getByText('판매자 상품 생성')).toBeInTheDocument()
    expect(screen.getByText('상품 생성 → 구매자 주문')).toBeInTheDocument()
    expect(screen.getByText('결제 성공 workflow')).toBeInTheDocument()
    expect(screen.getByText(/allowlist 밖 5개 제외/)).toBeInTheDocument()
    expect(screen.getAllByText(/미발견: POST \/api\/orders/)).toHaveLength(2)
  })
})

function discovery(): PilotDiscovery {
  const catalog = {
    method: 'GET', swaggerPath: '/products', executionPath: '/api/products', operationId: 'getProducts',
    authProfile: null, summary: '상품 목록',
  }
  const product = {
    method: 'POST', swaggerPath: '/products', executionPath: '/api/products', operationId: 'createProduct_1',
    authProfile: 'seller', summary: '상품 생성',
  }
  return {
    targetSystemId: 'sideproject-local', profileVersionId: 'profile-1', openApiPath: '/api-docs/product',
    snapshotId: 'snapshot-1', snapshotChecksum: 'abcdef1234567890', ignoredOperationCount: 5,
    discoveredOperations: [catalog, product],
    candidates: [
      { id: 'availability', title: 'Health와 상품 카탈로그 가용성', description: '', readiness: 'READY', operations: [catalog], missingOperations: [] },
      { id: 'product-create', title: '판매자 상품 생성', description: '', readiness: 'READY', operations: [product], missingOperations: [] },
      { id: 'order-workflow', title: '상품 생성 → 구매자 주문', description: '', readiness: 'NOT_READY', operations: [product], missingOperations: ['POST /api/orders'] },
      { id: 'payment-success', title: '결제 성공 workflow', description: '', readiness: 'NOT_READY', operations: [product], missingOperations: ['POST /api/orders', 'POST /api/payments/webhook'] },
    ],
  }
}
