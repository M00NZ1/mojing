import { useEffect, useRef, useState } from 'react';

const defaults = { characterId: null as number | null, generateWorldType: '修仙', generateTheme: '', generateTone: '偏严谨、可长期推进', generateExtra: '', generateLabel: '', autoSaveGeneratedWorld: false, importSourceFilename: 'world.txt', importSourceText: '', importCategoryHint: 'DND', autoSaveImportedWorld: false };
type Draft = typeof defaults;

export function useWorldDraft() {
  const [draft, setDraft] = useState<Draft>(defaults);
  const [ready, setReady] = useState(false);
  const [error, setError] = useState('');
  const [saving, setSaving] = useState(false);
  const database = useRef<IDBDatabase | null>(null);
  const revision = useRef(0);
  const storedRevision = useRef(0);
  const lastSaved = useRef<Draft>(defaults);
  useEffect(() => {
    let active = true;
    const failed = () => { if (active) { setError('浏览器草稿暂不可用，离开前请复制保留输入内容。'); setReady(true); } };
    let request: IDBOpenDBRequest;
    try { request = indexedDB.open('mojing-creation-drafts', 1); } catch { failed(); return; }
    request.onupgradeneeded = () => request.result.createObjectStore('drafts');
    request.onerror = failed;
    request.onblocked = () => { if (active) { active = false; setError('草稿数据库被其他页面占用，离开前请复制保留输入内容。'); setReady(true); } };
    request.onsuccess = () => {
      const db = request.result;
      if (!active) { db.close(); return; }
      const read = db.transaction('drafts').objectStore('drafts').get('world');
      read.onerror = () => { db.close(); failed(); };
      read.onsuccess = () => {
        if (!active) { db.close(); return; }
        const stored = read.result;
        if (stored && (stored.version !== 1 || !stored.values || typeof stored.values !== 'object' || Array.isArray(stored.values))) { db.close(); failed(); return; }
        if (stored?.revision !== undefined && (!Number.isSafeInteger(stored.revision) || stored.revision < 0)) { db.close(); failed(); return; }
        const loaded = { ...defaults };
        for (const key of Object.keys(defaults) as (keyof Draft)[]) {
          const value = stored?.values[key];
          if (value === undefined) continue;
          const valid = key === 'characterId' ? value === null || (Number.isInteger(value) && value > 0) : typeof value === typeof defaults[key];
          if (!valid) { db.close(); failed(); return; }
          if (key === 'characterId') { if (value === null || (Number.isInteger(value) && value > 0)) loaded.characterId = value; }
          else if (typeof value === typeof defaults[key]) Object.assign(loaded, { [key]: value });
        }
        database.current = db;
        storedRevision.current = stored?.revision ?? 0;
        lastSaved.current = loaded;
        setDraft(loaded);
        setReady(true);
      };
    };
    return () => { active = false; database.current?.close(); database.current = null; };
  }, []);
  useEffect(() => {
    if (!ready || !database.current || draft === lastSaved.current) return;
    const current = ++revision.current;
    setSaving(true);
    let failureMessage = '草稿保存失败，离开前请复制保留输入内容。';
    const failed = () => { if (current === revision.current) { setSaving(false); setError(failureMessage); } };
    try {
      const transaction = database.current.transaction('drafts', 'readwrite');
      const store = transaction.objectStore('drafts');
      const read = store.get('world');
      let nextRevision = storedRevision.current;
      read.onsuccess = () => {
        const saved = read.result;
        if ((saved?.revision ?? 0) !== storedRevision.current || (saved && saved.version !== 1)) {
          failureMessage = '另一页面已更新草稿，未覆盖其内容。当前输入仍在本页，请先复制保留。';
          transaction.abort();
          return;
        }
        nextRevision = storedRevision.current + 1;
        try { store.put({ version: 1, revision: nextRevision, values: draft }, 'world'); }
        catch { transaction.abort(); }
      };
      transaction.oncomplete = () => {
        storedRevision.current = nextRevision;
        lastSaved.current = draft;
        if (current === revision.current) { setSaving(false); setError(''); }
      };
      transaction.onerror = failed;
      transaction.onabort = failed;
    } catch { failed(); }
  }, [draft, ready]);
  const setField = <K extends keyof Draft>(key: K, value: Draft[K]) => setDraft((current) => ({ ...current, [key]: value }));
  return { draft, setField, ready, saving, error };
}
