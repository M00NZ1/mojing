import type { StorySimulationFormValues } from '../components/StorySimulationForm';

export const STORY_DRAFT_STORAGE_KEY = 'mojing:story-simulation-draft:v1';
export const initialStoryValues: StorySimulationFormValues = {
  premise: '', direction: '', tone: '有画面感、人物动机清楚、适合连续长篇创作',
  chapter_count: 2, template_id: '', encyclopedia_id: '', character_ids: [],
};
export type StoryDraftStatus = 'saved' | 'unavailable' | 'conflict' | 'unreadable';

export function newStoryRequestId(): string {
  return globalThis.crypto?.randomUUID?.() ?? 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (letter) => {
    const value = Math.floor(Math.random() * 16);
    return (letter === 'x' ? value : (value & 3) | 8).toString(16);
  });
}

export function readStoryDraft(): { values: StorySimulationFormValues; raw: string | null | undefined; status: StoryDraftStatus } {
  let raw: string | null | undefined;
  try {
    raw = window.localStorage.getItem(STORY_DRAFT_STORAGE_KEY);
    const saved = raw === null ? {} : JSON.parse(raw);
    if (!saved || typeof saved !== 'object' || Array.isArray(saved)) throw new Error('Invalid story draft');
    return { raw, status: 'saved', values: {
      premise: typeof saved.premise === 'string' ? saved.premise : initialStoryValues.premise,
      direction: typeof saved.direction === 'string' ? saved.direction : initialStoryValues.direction,
      tone: typeof saved.tone === 'string' ? saved.tone : initialStoryValues.tone,
      chapter_count: [1, 2, 3].includes(Number(saved.chapter_count)) ? Number(saved.chapter_count) : 2,
      template_id: typeof saved.template_id === 'string' ? saved.template_id : '',
      encyclopedia_id: typeof saved.encyclopedia_id === 'string' ? saved.encyclopedia_id : '',
      character_ids: Array.isArray(saved.character_ids) ? saved.character_ids.filter((id: unknown) => Number.isSafeInteger(id) && Number(id) > 0) : [],
      ...(typeof saved.request_id === 'string' && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(saved.request_id) ? { request_id: saved.request_id } : {}),
    } };
  } catch {
    return { values: initialStoryValues, raw, status: raw === undefined ? 'unavailable' : 'unreadable' };
  }
}

async function withDraftLock<T>(action: () => T): Promise<T> {
  if (typeof navigator !== 'undefined' && navigator.locks) {
    return navigator.locks.request(STORY_DRAFT_STORAGE_KEY, { ifAvailable: true }, (lock) => {
      if (!lock) throw new Error('Story draft storage is busy');
      return action();
    });
  }
  return action();
}

export async function saveStoryDraft(values: StorySimulationFormValues, expectedRaw: string | null | undefined): Promise<{ status: StoryDraftStatus; raw?: string | null }> {
  try {
    return await withDraftLock(() => {
      const current = window.localStorage.getItem(STORY_DRAFT_STORAGE_KEY);
      if (current !== (expectedRaw ?? null)) return { status: 'conflict', raw: current };
      const revision = globalThis.crypto?.randomUUID?.() ?? `${Date.now()}-${Math.random()}`;
      const raw = JSON.stringify({ ...values, storage_revision: revision });
      window.localStorage.setItem(STORY_DRAFT_STORAGE_KEY, raw);
      return { status: 'saved', raw };
    });
  } catch { return { status: 'unavailable' }; }
}

export async function clearSubmittedStoryDraft(expectedRaw: string | undefined): Promise<void> {
  if (expectedRaw === undefined) return;
  try {
    await withDraftLock(() => {
      if (window.localStorage.getItem(STORY_DRAFT_STORAGE_KEY) === expectedRaw) window.localStorage.removeItem(STORY_DRAFT_STORAGE_KEY);
    });
  } catch { /* A completed session remains usable when browser storage is unavailable. */ }
}
