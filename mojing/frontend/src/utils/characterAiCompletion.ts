import type { Character } from '../types';

export function mergeCharacterAiPersona(
  current: Partial<Character> | null,
  requested: Partial<Character>,
  result: Record<string, unknown>,
): Partial<Character> | null {
  if (!current || current.id !== requested.id
    || (current.persona_prompt ?? '') !== (requested.persona_prompt ?? '')
    || typeof result.persona_prompt !== 'string' || !result.persona_prompt.trim()) return current;
  return { ...current, persona_prompt: result.persona_prompt };
}
