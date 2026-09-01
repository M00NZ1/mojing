import type { Message } from '../types';

export function isPersistedMessageId(messageId: number | null | undefined): messageId is number {
  return typeof messageId === 'number' && Number.isInteger(messageId) && messageId > 0;
}

export function canCreateEntryFromMessage(messages: Pick<Message, 'id'>[], isGenerating: boolean): boolean {
  if (isGenerating || messages.length === 0) return false;
  return isPersistedMessageId(messages[messages.length - 1].id);
}
