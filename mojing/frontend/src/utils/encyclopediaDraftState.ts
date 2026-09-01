import type { EncyclopediaEntryDraft } from '../types';

export interface EncyclopediaLibraryDraft {
  editingId: number | null;
  name: string;
  description: string;
  genreTags: string;
  worldPrompt: string;
  gameplayMode: string;
  antiCheatPrompt: string;
}

export function encyclopediaEntryDraftSnapshot(draft: EncyclopediaEntryDraft | null): string {
  return draft ? JSON.stringify(draft) : '';
}

export function mergeEncyclopediaEntryDraftSnapshot(
  snapshot: string,
  savedFields: Partial<EncyclopediaEntryDraft>,
): string {
  if (!snapshot) return encyclopediaEntryDraftSnapshot(savedFields);
  try {
    return encyclopediaEntryDraftSnapshot({
      ...(JSON.parse(snapshot) as EncyclopediaEntryDraft),
      ...savedFields,
    });
  } catch {
    return snapshot;
  }
}

export function encyclopediaLibraryDraftSnapshot(draft: EncyclopediaLibraryDraft): string {
  return JSON.stringify(draft);
}
