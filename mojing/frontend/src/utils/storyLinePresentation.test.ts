// @ts-expect-error Node's native TypeScript runner requires the explicit extension.
import { findStoryLineDisplayLabel, storyLineDisplayLabel } from './storyLinePresentation.ts';

const cases: Array<[string, string | null | undefined, string]> = [
  ['main', '旧主线名称', '主线剧情'],
  ['branch_1', null, '未命名故事线'],
  ['branch_2', 'branch_2', '未命名故事线'],
  ['branch_3', '重新生成 · 从消息 82 分叉', '重新生成的故事线'],
  ['branch_4', 'character · 从消息 #16 分叉', '角色回复后的故事线'],
  ['branch_5', '沈砚 · 从消息 31 分叉', '沈砚后的故事线'],
  ['branch_6', '雨夜重逢', '雨夜重逢'],
];

for (const [branchId, label, expected] of cases) {
  const actual = storyLineDisplayLabel(branchId, label);
  if (actual !== expected) {
    throw new Error(`故事线名称不符合预期：${branchId} 得到“${actual}”，预期“${expected}”`);
  }
}

const found = findStoryLineDisplayLabel(
  [{ branch_id: 'branch_7', label: '已编辑 · 从消息 44 分叉' }],
  'branch_7',
);

if (found !== '编辑后的故事线') {
  throw new Error(`故事线查找应复用统一显示规则，实际得到“${found}”`);
}
