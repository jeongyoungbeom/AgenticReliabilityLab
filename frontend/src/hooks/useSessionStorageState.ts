import { useEffect, useState } from 'react'

export function useSessionStorageState<T>(key: string, initialValue: T) {
  const [value, setValue] = useState<T>(() => readValue(key, initialValue))

  useEffect(() => {
    if (value === null || value === undefined) {
      sessionStorage.removeItem(key)
      return
    }
    sessionStorage.setItem(key, JSON.stringify(value))
  }, [key, value])

  return [value, setValue] as const
}

function readValue<T>(key: string, initialValue: T): T {
  try {
    const stored = sessionStorage.getItem(key)
    return stored ? (JSON.parse(stored) as T) : initialValue
  } catch {
    return initialValue
  }
}
