import { useCallback } from 'react'
import { newIdempotencyKey } from '../api/ApiClient'
import { useSessionStorageState } from './useSessionStorageState'

export function useIdempotencyKey(scope: string) {
  const storageKey = `arl.idempotency.${scope}`
  const [key, setKey] = useSessionStorageState(storageKey, newIdempotencyKey(scope))
  const renew = useCallback(() => setKey(newIdempotencyKey(scope)), [scope, setKey])
  return { key, renew }
}
