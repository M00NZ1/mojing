type StoryLineLike = {
  branch_id: string;
  label?: string | null;
};

const LEGACY_SOURCE_LABEL = /^(?:(.+?)\s*·\s*)?从消息\s*#?\d+\s*分叉$/u;

function legacySourcePrefixLabel(prefix: string | undefined): string {
  const normalized = prefix?.trim().toLowerCase() ?? '';
  if (!normalized) return '未命名故事线';
  if (normalized === '重新生成') return '重新生成的故事线';
  if (normalized === '已编辑') return '编辑后的故事线';
  if (normalized === 'user') return '用户消息后的故事线';
  if (normalized === 'narrator' || normalized === 'system') return '旁白后的故事线';
  if (normalized === 'character') return '角色回复后的故事线';
  return `${prefix?.trim()}后的故事线`;
}

export function storyLineDisplayLabel(branchId: string, label?: string | null): string {
  const normalizedBranchId = branchId.trim() || 'main';
  if (normalizedBranchId === 'main') return '主线剧情';

  const normalizedLabel = label?.trim() ?? '';
  if (!normalizedLabel || normalizedLabel === normalizedBranchId) return '未命名故事线';

  const legacyMatch = normalizedLabel.match(LEGACY_SOURCE_LABEL);
  if (legacyMatch) return legacySourcePrefixLabel(legacyMatch[1]);
  return normalizedLabel;
}

export function findStoryLineDisplayLabel(items: StoryLineLike[], branchId: string): string {
  const item = items.find((candidate) => candidate.branch_id === branchId);
  return storyLineDisplayLabel(branchId, item?.label);
}
