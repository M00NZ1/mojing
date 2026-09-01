import type { Message } from '../types';

export type RegenerationBranchPoint = {
  sourceMessageId: number;
  parentBranchId: string;
};

export function getRegenerationBranchPoint(
  message: Pick<Message, 'id' | 'parent_message_id' | 'branch_id'>,
  activeBranchId: string,
): RegenerationBranchPoint {
  const parentMessageId = message.parent_message_id;
  return {
    sourceMessageId: typeof parentMessageId === 'number' && parentMessageId > 0
      ? parentMessageId
      : message.id,
    parentBranchId: activeBranchId.trim() || message.branch_id || 'main',
  };
}
