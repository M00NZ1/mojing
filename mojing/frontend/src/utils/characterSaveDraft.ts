import type { Character } from '../types';

export function mergeSavedCharacterDraft(current: Partial<Character>, submitted: Partial<Character>, saved: Character): Partial<Character> {
  const changed = Object.fromEntries(Object.entries(current).filter(([key, value]) =>
    !['id', 'created_at', 'updated_at'].includes(key)
    && JSON.stringify(value) !== JSON.stringify(submitted[key as keyof Character])));
  return { ...saved, ...changed, id: saved.id };
}
