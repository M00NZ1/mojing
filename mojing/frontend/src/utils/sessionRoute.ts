import type { SessionItem } from '../types';

export function sessionChatPath(session: Pick<SessionItem, 'id' | 'last_message_branch_id'>): string {
  const branchId = session.last_message_branch_id?.trim();
  const base = `/chat/${session.id}`;
  return branchId && branchId !== 'main' ? `${base}?branch=${encodeURIComponent(branchId)}` : base;
}
