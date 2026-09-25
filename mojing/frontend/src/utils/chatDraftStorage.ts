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

export type PendingChatSend = {
  clientMessageId: string;
  sessionId: number;
  branchId: string;
  content: string;
  input: string;
  quote: ChatQuoteDraft | null;
  files?: { name: string; size: number; type: string }[];
};

export function chatSendFilesMatch(files: File[], pendingFiles: NonNullable<PendingChatSend['files']>): boolean {
  return files.length === pendingFiles.length && files.every((file, index) =>
    file.name === pendingFiles[index].name && file.size === pendingFiles[index].size
    && file.type === pendingFiles[index].type);
}

const pendingSendKey = (sessionId: number) => `mojing:pending-chat-send:v1:${sessionId}`;

export function loadPendingChatSend(sessionId: number): PendingChatSend | null {
  try {
    const raw = getLocalStorage()?.getItem(pendingSendKey(sessionId));
    if (!raw) return null;
    const value = JSON.parse(raw) as PendingChatSend;
    if (!value || value.sessionId !== sessionId
      || !/^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(value.clientMessageId)
      || typeof value.branchId !== 'string' || !value.branchId
      || typeof value.content !== 'string'
      || typeof value.input !== 'string'
      || (value.files !== undefined && (!Array.isArray(value.files) || value.files.some((file) =>
        !file || typeof file.name !== 'string' || !Number.isSafeInteger(file.size) || file.size < 0
        || typeof file.type !== 'string')))
      || (!value.content.trim() && !value.files?.length)
      || (value.quote !== null && (typeof value.quote !== 'object' || value.quote.session_id !== sessionId))) return null;
    return value;
  } catch { return null; }
}

export function savePendingChatSend(send: PendingChatSend): boolean {
  try {
    const storage = getLocalStorage();
    if (!storage) return false;
    storage.setItem(pendingSendKey(send.sessionId), JSON.stringify(send));
    return true;
  } catch { return false; }
}

export function clearPendingChatSend(sessionId: number, clientMessageId: string): void {
  try {
    const storage = getLocalStorage();
    const saved = loadPendingChatSend(sessionId);
    if (saved?.clientMessageId === clientMessageId) storage?.removeItem(pendingSendKey(sessionId));
  } catch { /* A failed cleanup must not hide the saved message. */ }
}

export type PendingChatReply = {
  sessionId: number;
  branchId: string;
  userMessageId: number;
};

const pendingReplyKey = (sessionId: number) => `mojing:pending-chat-reply:v1:${sessionId}`;

export function loadPendingChatReply(sessionId: number): PendingChatReply | null {
  try {
    const raw = getLocalStorage()?.getItem(pendingReplyKey(sessionId));
    if (!raw) return null;
    const value = JSON.parse(raw) as PendingChatReply;
    if (value?.sessionId !== sessionId || typeof value.branchId !== 'string' || !value.branchId
      || !Number.isSafeInteger(value.userMessageId) || value.userMessageId <= 0) return null;
    return value;
  } catch { return null; }
}

export function savePendingChatReply(reply: PendingChatReply): boolean {
  try {
    const storage = getLocalStorage();
    if (!storage) return false;
    storage.setItem(pendingReplyKey(reply.sessionId), JSON.stringify(reply));
    return true;
  } catch { return false; }
}

export function clearPendingChatReply(sessionId: number, userMessageId: number): void {
  try {
    const storage = getLocalStorage();
    if (loadPendingChatReply(sessionId)?.userMessageId === userMessageId) {
      storage?.removeItem(pendingReplyKey(sessionId));
    }
  } catch { /* A failed cleanup must not hide a completed reply. */ }
}

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
