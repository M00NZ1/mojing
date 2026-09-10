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

export type ChatQuoteDraft = {
  id: number;
  session_id: number;
  speaker_type: 'user' | 'character' | 'system' | 'narrator';
  character_name?: string | null;
  content: string;
};

const quoteKey = (sessionId: number) => `mojing:chat-quote:v1:${sessionId}`;

export function loadChatQuote(sessionId: number): ChatQuoteDraft | null {
  try {
    const raw = getLocalStorage()?.getItem(quoteKey(sessionId));
    if (!raw) return null;
    const quote = JSON.parse(raw) as ChatQuoteDraft;
    if (!quote || quote.session_id !== sessionId || !Number.isSafeInteger(quote.id) || quote.id <= 0
      || !['user', 'character', 'system', 'narrator'].includes(quote.speaker_type)
      || typeof quote.content !== 'string' || !quote.content.trim()) return null;
    return {
      id: quote.id, session_id: sessionId, speaker_type: quote.speaker_type,
      character_name: typeof quote.character_name === 'string' ? quote.character_name.slice(0, 120) : null,
      content: quote.content.split('\n')[0].slice(0, 120),
    };
  } catch { return null; }
}

export function saveChatQuote(sessionId: number, quote: ChatQuoteDraft | null): void {
  try {
    const storage = getLocalStorage();
    if (!quote) { storage?.removeItem(quoteKey(sessionId)); return; }
    if (quote.session_id !== sessionId) return;
    storage?.setItem(quoteKey(sessionId), JSON.stringify({
      id: quote.id, session_id: sessionId, speaker_type: quote.speaker_type,
      character_name: quote.character_name?.slice(0, 120) ?? null,
      content: quote.content.trim().split('\n')[0].slice(0, 120),
    }));
  } catch { /* Keep the selected quote usable when storage is unavailable. */ }
}
