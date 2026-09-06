import { useEffect, useRef, useState } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { api } from '../api/client';
import type { Character } from '../types';
import InlineQueryError from './InlineQueryError';
import './CharacterImportDialog.css';

type Source = 'card' | 'portable' | 'url';

export default function CharacterImportDialog({ onClose, onOpen }: {
  onClose: () => void;
  onOpen: (character: Character) => void;
}) {
  const dialog = useRef<HTMLDialogElement>(null);
  const busy = useRef(false);
  const [source, setSource] = useState<Source>('card');
  const [file, setFile] = useState<File | null>(null);
  const [url, setUrl] = useState('');
  const client = useQueryClient();
  const run = useMutation({ mutationFn: async () => {
    if (source === 'url') return api.importCharacterFromUrl(url.trim());
    if (!file) throw new Error('请先选择文件');
    if (source === 'portable') return api.importCharacterPortable(file);
    return file.name.toLowerCase().endsWith('.png') ? api.importCharacterCard(file) : api.importCharacterCardJson(file);
  }, onSuccess: () => { void client.invalidateQueries({ queryKey: ['characters'] }); },
  onSettled: () => { busy.current = false; } });
  useEffect(() => {
    const previous = document.activeElement as HTMLElement | null;
    dialog.current?.showModal();
    return () => { previous?.focus(); };
  }, []);
  const submit = () => {
    if (busy.current) return;
    busy.current = true;
    run.mutate();
  };
  return <dialog ref={dialog} className="character-import-dialog" aria-labelledby="character-import-title"
    onCancel={(event) => { event.preventDefault(); if (!busy.current) onClose(); }}>
    <div className="character-import-heading"><div><p className="eyebrow">带来一个新故事</p><h2 id="character-import-title">导入角色</h2></div>
      <button type="button" className="btn btn-ghost btn-sm" disabled={run.isPending} onClick={onClose} aria-label="关闭导入">关闭</button></div>
    {run.data ? <div className="character-import-success" role="status"><h3>「{run.data.name}」已加入角色库</h3>
      <p>按创建时间排在前面，收藏的角色仍优先显示。现有角色和正在编辑的内容不会被覆盖。</p>
      <div className="button-row"><button type="button" className="btn btn-primary" onClick={() => onOpen(run.data)}>查看角色</button>
        <button type="button" className="btn btn-ghost" onClick={() => { run.reset(); setFile(null); setUrl(''); }}>继续导入</button></div>
    </div> : <form onSubmit={(event) => { event.preventDefault(); submit(); }}>
      <p className="hint">每次导入都会新建角色，同名资料自动区分。</p>
      <fieldset disabled={run.isPending}>
        <label htmlFor="character-import-source">资料格式</label>
        <select id="character-import-source" value={source} onChange={(event) => { setSource(event.target.value as Source); setFile(null); run.reset(); }}>
          <option value="card">角色卡 · PNG / JSON</option><option value="portable">墨境便携包 · JSON / TXT / DOCX</option><option value="url">PNG 角色卡链接</option>
        </select>
        {source === 'url' ? <><label htmlFor="character-import-url">文件链接</label><input id="character-import-url" type="url" required value={url} onChange={(event) => { setUrl(event.target.value); run.reset(); }} placeholder="https://…/character.png" /><p className="hint">填写可直接下载 PNG 角色卡的链接。</p></> : <>
          <label htmlFor="character-import-file">选择文件</label><input key={source} id="character-import-file" type="file" accept={source === 'card' ? '.png,.json' : '.json,.txt,.docx'} onChange={(event) => { setFile(event.target.files?.[0] || null); run.reset(); }} />
          <p className="hint">{source === 'card' ? '支持含角色设定的 PNG，以及酒馆 V2 JSON 角色卡。' : '支持墨境导出的便携包；不会导入访问密钥。'}</p>
        </>}
      </fieldset>
      {run.isError && <InlineQueryError message="导入未完成" error={run.error} retrying={run.isPending} onRetry={submit} />}
      <div className="character-import-footer"><button type="submit" className="btn btn-primary" disabled={run.isPending || (source === 'url' ? !url.trim() : !file)}>{run.isPending ? '正在导入…' : '导入为新角色'}</button>
        {run.isPending && <span className="hint" role="status">正在保存，请稍候。</span>}</div>
    </form>}
  </dialog>;
}
