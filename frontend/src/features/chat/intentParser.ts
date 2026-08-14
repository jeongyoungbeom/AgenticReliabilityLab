import type { TargetProfile } from '../../api/targetProfile'

export type WorkbenchIntent =
  | 'TARGET_PROFILE_DRAFT'
  | 'SELECT_TARGET'
  | 'SELECT_CANDIDATES'
  | 'OPEN_BATCH_APPROVAL'
  | 'SELECT_ANALYSIS_CONFIGURATIONS'
  | 'OPEN_ANALYSIS_RESULT'
  | 'UNSUPPORTED'

export interface ParsedIntent {
  intent: WorkbenchIntent
  response: string
  targetSystemId?: string
}

export function parseWorkbenchIntent(input: string, profiles: TargetProfile[]): ParsedIntent {
  const normalized = input.trim().toLocaleLowerCase()
  if (containsUnsafeExecutionRequest(normalized)) {
    return {
      intent: 'UNSUPPORTED',
      response: 'Chat은 URL, HTTP 명령, Shell·Docker 명령, 코드·설정 변경을 실행하거나 승인할 수 없습니다. 등록된 화면의 버튼을 사용하세요.',
    }
  }

  const matchedTarget = profiles.find((profile) => normalized.includes(profile.targetSystemId.toLocaleLowerCase()))
  if (matchedTarget) {
    return {
      intent: 'SELECT_TARGET',
      targetSystemId: matchedTarget.targetSystemId,
      response: `'${matchedTarget.targetSystemId}' Target을 선택했습니다. 실행은 다음 화면의 명시적 버튼과 승인으로만 가능합니다.`,
    }
  }
  if (normalized.includes('프로필') || normalized.includes('yaml') || normalized.includes('등록')) {
    return { intent: 'TARGET_PROFILE_DRAFT', response: 'Target Profile YAML 초안 화면을 열었습니다. 검증과 Draft 저장은 아직 Target 요청을 보내지 않습니다.' }
  }
  if (normalized.includes('승인')) {
    return { intent: 'OPEN_BATCH_APPROVAL', response: 'Batch 승인 화면을 열었습니다. 승인 모달은 Chat이 아닌 화면의 승인 버튼으로만 열 수 있습니다.' }
  }
  if (normalized.includes('후보') || normalized.includes('테스트') || normalized.includes('batch')) {
    return { intent: 'SELECT_CANDIDATES', response: '등록된 GET 후보 선택 화면을 열었습니다. 원하는 후보를 직접 체크한 뒤 Batch를 생성하세요.' }
  }
  if (normalized.includes('결과') || normalized.includes('root cause') || normalized.includes('원인')) {
    return { intent: 'OPEN_ANALYSIS_RESULT', response: '분석 결과 화면을 열었습니다. 원인 가설과 개선 제안은 완료된 분석 결과에서만 요청할 수 있습니다.' }
  }
  if (normalized.includes('분석') || normalized.includes('single') || normalized.includes('multi') || normalized.includes('gpt') || normalized.includes('qwen')) {
    return { intent: 'SELECT_ANALYSIS_CONFIGURATIONS', response: '분석 조합 선택 화면을 열었습니다. Single/Multi와 모델 조합을 직접 체크하면 선택한 항목만 실행합니다.' }
  }
  return { intent: 'UNSUPPORTED', response: '지원하는 요청은 Profile 초안, 등록 Target 선택, 안전 GET 후보, Batch 승인, 분석 조합, 분석 결과 열기입니다.' }
}

function containsUnsafeExecutionRequest(input: string): boolean {
  return ['http://', 'https://', 'curl', 'shell', 'docker', 'kubectl', 'sql', '코드 수정', '배포', 'deploy', 'header', 'token'].some((term) => input.includes(term))
}
