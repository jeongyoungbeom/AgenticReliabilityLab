import type { ReactNode } from 'react'

interface ConfirmationDialogProps {
  title: string
  confirmLabel: string
  busy?: boolean
  children: ReactNode
  onCancel: () => void
  onConfirm: () => void
}

export function ConfirmationDialog({ title, confirmLabel, busy = false, children, onCancel, onConfirm }: ConfirmationDialogProps) {
  return (
    <div className="dialog-backdrop" role="presentation">
      <section className="confirmation-dialog" role="dialog" aria-modal="true" aria-labelledby="confirmation-title">
        <p className="eyebrow">명시적 승인</p>
        <h2 id="confirmation-title">{title}</h2>
        <div className="dialog-content">{children}</div>
        <div className="button-row">
          <button type="button" className="secondary-button" onClick={onCancel} disabled={busy}>취소</button>
          <button type="button" onClick={onConfirm} disabled={busy}>{confirmLabel}</button>
        </div>
      </section>
    </div>
  )
}
