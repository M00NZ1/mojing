export type SettingConflictEntry = {
  id: number;
  title: string;
  entry_type: string;
  summary?: string;
};

export type SettingConflict = {
  message: string;
  entries: SettingConflictEntry[];
};

export type SettingConflictResult = {
  conflicts: SettingConflict[];
  message?: string;
};

function asRecord(value: unknown): Record<string, unknown> | null {
  return value && typeof value === 'object' ? value as Record<string, unknown> : null;
}

function parseEntry(value: unknown): SettingConflictEntry | null {
  const record = asRecord(value);
  if (!record) return null;
  const id = Number(record.id);
  const title = typeof record.title === 'string' ? record.title.trim() : '';
  const entryType = typeof record.entry_type === 'string' ? record.entry_type.trim() : '';
  if (!Number.isSafeInteger(id) || id <= 0 || !title || !entryType) return null;
  const summary = typeof record.summary === 'string' ? record.summary.trim() : '';
  return { id, title, entry_type: entryType, summary: summary || undefined };
}

export function normalizeSettingConflictResult(value: unknown): SettingConflictResult {
  const record = asRecord(value);
  const rawConflicts = Array.isArray(record?.conflicts) ? record.conflicts : [];
  const conflicts = rawConflicts.map((rawConflict) => {
    const conflict = asRecord(rawConflict);
    const rawEntries = Array.isArray(conflict?.entries) ? conflict.entries : [];
    const seenIds = new Set<number>();
    const entries = rawEntries
      .map(parseEntry)
      .filter((entry): entry is SettingConflictEntry => {
        if (!entry || seenIds.has(entry.id)) return false;
        seenIds.add(entry.id);
        return true;
      });
    return {
      message: typeof conflict?.message === 'string' && conflict.message.trim()
        ? conflict.message.trim()
        : '发现一项需要确认的设定',
      entries,
    };
  });
  return {
    conflicts,
    message: typeof record?.message === 'string' && record.message.trim()
      ? record.message.trim()
      : undefined,
  };
}

export function encyclopediaEntryPath(encyclopediaId: number, entry: SettingConflictEntry): string {
  const search = new URLSearchParams({
    encId: String(encyclopediaId),
    category: entry.entry_type,
    entryId: String(entry.id),
  });
  return `/encyclopedia?${search.toString()}`;
}
