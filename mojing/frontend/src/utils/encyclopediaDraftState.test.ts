// @ts-expect-error Node's native TypeScript runner requires the explicit extension.
import { encyclopediaEntryDraftSnapshot, encyclopediaLibraryDraftSnapshot, mergeEncyclopediaEntryDraftSnapshot } from './encyclopediaDraftState.ts';

function expectEqual<T>(label: string, actual: T, expected: T) {
  if (actual !== expected) {
    throw new Error(`${label}: expected ${String(expected)}, received ${String(actual)}`);
  }
}

{
  const original = { id: 7, title: '雾港', content: '未修改正文', cover_image_path: 'old.webp' };
  const baseline = encyclopediaEntryDraftSnapshot(original);
  const coverSaved = mergeEncyclopediaEntryDraftSnapshot(baseline, { cover_image_path: 'new.webp' });

  expectEqual('same entry is clean', encyclopediaEntryDraftSnapshot(original), baseline);
  expectEqual(
    'persisted cover advances only its baseline field',
    coverSaved,
    encyclopediaEntryDraftSnapshot({ ...original, cover_image_path: 'new.webp' }),
  );
  expectEqual(
    'text edit remains different from merged baseline',
    encyclopediaEntryDraftSnapshot({ ...original, content: '尚未保存', cover_image_path: 'new.webp' }) === coverSaved,
    false,
  );
}

{
  const draft = {
    editingId: null,
    name: '',
    description: '',
    genreTags: '',
    worldPrompt: '',
    gameplayMode: '自由剧情',
    antiCheatPrompt: '',
  };
  const baseline = encyclopediaLibraryDraftSnapshot(draft);

  expectEqual('new library starts clean', encyclopediaLibraryDraftSnapshot(draft), baseline);
  expectEqual(
    'library name edit becomes dirty',
    encyclopediaLibraryDraftSnapshot({ ...draft, name: '雾港纪事' }) === baseline,
    false,
  );
}
