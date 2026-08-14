import type { AnalysisArchitecture, AnalysisConfiguration } from '../../api/analysis'

interface AnalysisConfigurationSelectorProps {
  selected: AnalysisConfiguration[]
  onChange: (configurations: AnalysisConfiguration[]) => void
}

const choices: AnalysisConfiguration[] = [
  { selectionKey: 'SINGLE:GPT_OSS', architecture: 'SINGLE', modelKey: 'GPT_OSS' },
  { selectionKey: 'SINGLE:QWEN', architecture: 'SINGLE', modelKey: 'QWEN' },
  { selectionKey: 'MULTI:GPT_OSS', architecture: 'MULTI', modelKey: 'GPT_OSS' },
  { selectionKey: 'MULTI:QWEN', architecture: 'MULTI', modelKey: 'QWEN' },
]

export function AnalysisConfigurationSelector({ selected, onChange }: AnalysisConfigurationSelectorProps) {
  const selectedKeys = new Set(selected.map((configuration) => configuration.selectionKey))

  function toggle(choice: AnalysisConfiguration) {
    const next = selectedKeys.has(choice.selectionKey)
      ? selected.filter((configuration) => configuration.selectionKey !== choice.selectionKey)
      : [...selected, choice]
    onChange(next)
  }

  return (
    <ul className="configuration-list">
      {choices.map((choice) => (
        <li key={choice.selectionKey}>
          <label>
            <input
              type="checkbox"
              checked={selectedKeys.has(choice.selectionKey)}
              onChange={() => toggle(choice)}
            />
            <span><strong>{label(choice.architecture)} · {choice.modelKey}</strong><small>{description(choice.architecture)}</small></span>
          </label>
        </li>
      ))}
    </ul>
  )
}

function label(architecture: AnalysisArchitecture): string {
  return architecture === 'SINGLE' ? 'Single agent' : 'Multi agent'
}

function description(architecture: AnalysisArchitecture): string {
  return architecture === 'SINGLE'
    ? '선택한 모델 한 개로 결과를 분석합니다.'
    : '선택한 모델로 Supervisor, Planner, Analyst, Reviewer 단계를 수행합니다.'
}
