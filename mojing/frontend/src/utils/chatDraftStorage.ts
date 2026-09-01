const CHAT_DRAFT_STORAGE_PREFIX = 'mojing:chat-draft:v1:';

function chatDraftStorageKey(sessionId: number): string {
  return `${CHAT_DRAFT_STORAGE_PREFIX}${sessionId}`;
}

function getLocalStorage(): Storage | null {
  try {
    return typeof window === 'undefined' ? null : window.localStorage;
  } catch {
    return null;
  }
}

export function loadChatDraft(sessionId: number): string | null {
  const storage = getLocalStorage();
  if (!storage) return null;

  try {
    return storage.getItem(chatDraftStorageKey(sessionId));
  } catch {
    return null;
  }
}

export function saveChatDraft(sessionId: number, draft: string): void {
  const storage = getLocalStorage();
  if (!storage) return;

  try {
    const key = chatDraftStorageKey(sessionId);
    if (draft.length === 0) {
      storage.removeItem(key);
    } else {
      storage.setItem(key, draft);
    }
  } catch {
    // Storage may be unavailable or full; input must remain usable.
  }
}

export function clearChatDraft(sessionId: number): void {
  saveChatDraft(sessionId, '');
}
