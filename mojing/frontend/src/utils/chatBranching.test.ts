// @ts-expect-error Node's native TypeScript runner requires the explicit extension.
import { getRegenerationBranchPoint } from './chatBranching.ts';

const historicalCharacterReply = {
  id: 142,
  parent_message_id: 141,
  branch_id: 'main',
};

const historicalPoint = getRegenerationBranchPoint(
  historicalCharacterReply,
  'branch_current',
);

if (historicalPoint.sourceMessageId !== 141) {
  throw new Error('角色回复重新生成必须从它记录的父消息分叉，不能依赖父消息是否位于当前窗口');
}

if (historicalPoint.parentBranchId !== 'branch_current') {
  throw new Error('重新生成的新分支必须归属于用户当前查看的故事线');
}

const orphanPoint = getRegenerationBranchPoint({
  id: 9,
  parent_message_id: null,
  branch_id: 'main',
}, '');

if (orphanPoint.sourceMessageId !== 9 || orphanPoint.parentBranchId !== 'main') {
  throw new Error('没有父消息的旧数据应安全回退到当前消息和它所属的故事线');
}
