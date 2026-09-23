const GENERATED_STORY_KEY = 'mojing:unsaved-generated-story:v1';

export type UnsavedGeneratedStory = { requestId: string; text: string };

export function readUnsavedGeneratedStory(): UnsavedGeneratedStory | null {
  try {
    const raw = window.localStorage.getItem(GENERATED_STORY_KEY);
    if (!raw) return null;
    const value: unknown = JSON.parse(raw);
    if (!value || typeof value !== 'object') return null;
    const story = value as { requestId?: unknown; text?: unknown };
    return typeof story.requestId === 'string' && typeof story.text === 'string' && story.text.trim()
      ? { requestId: story.requestId, text: story.text } : null;
  } catch { return null; }
}

export function saveUnsavedGeneratedStory(story: UnsavedGeneratedStory): boolean {
  try {
    const existing = window.localStorage.getItem(GENERATED_STORY_KEY);
    if (existing && readUnsavedGeneratedStory()?.requestId !== story.requestId) return false;
    window.localStorage.setItem(GENERATED_STORY_KEY, JSON.stringify(story));
    return true;
  } catch { return false; }
}

export function clearUnsavedGeneratedStory(requestId: string): void {
  try {
    if (readUnsavedGeneratedStory()?.requestId === requestId) window.localStorage.removeItem(GENERATED_STORY_KEY);
  } catch { /* A failed cleanup must not hide the generated text already shown. */ }
}
