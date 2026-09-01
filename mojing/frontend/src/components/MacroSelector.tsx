import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';

import { api } from '../api/client';
import { copyText } from '../utils/clipboard';

type Props = {
  /** 剪贴板不可用时回退为追加（旧行为） */
  onInsert: (macro: string) => void;
  onCopied?: (macro: string) => void;
};

export default function MacroSelector({ onInsert, onCopied }: Props) {
  const { data: macros } = useQuery({
    queryKey: ['macros'],
    queryFn: () => api.listMacros(),
    staleTime: 600_000,
  });

  const [open, setOpen] = useState(false);

  async function copyOrInsert(macro: string) {
    try {
      await copyText(macro);
      onCopied?.(macro);
      setOpen(false);
      return;
    } catch {
      /* fall through to the documented insert behavior */
    }
    onInsert(macro);
    setOpen(false);
  }

  return (
    <div style={{ position: 'relative' }}>
      <button
        type="button"
        className="ghost-button inline-button"
        onClick={() => setOpen((prev) => !prev)}
      >
        复制宏变量
      </button>
      {open && macros && (
        <div
          style={{
            position: 'absolute',
            top: '100%',
            left: 0,
            zIndex: 100,
            background: 'var(--surface-2)',
            border: '1px solid var(--line)',
            borderRadius: 12,
            padding: 8,
            minWidth: 240,
            marginTop: 4,
          }}
        >
          <div style={{ fontSize: '0.75rem', color: 'var(--text-2)', marginBottom: 6 }}>
            点击复制到剪贴板，再粘贴到人设任意位置（与 Android 审计一致）。
          </div>
          {macros.map((item) => (
            <button
              key={item.macro}
              type="button"
              className="ghost-button inline-button"
              style={{
                display: 'block',
                width: '100%',
                textAlign: 'left',
                padding: '6px 8px',
                marginBottom: 2,
                fontSize: '0.85rem',
              }}
              onClick={() => void copyOrInsert(item.macro)}
            >
              <code style={{ color: 'var(--accent-1)', marginRight: 8 }}>{item.macro}</code>
              <span>{item.label}</span>
              <br />
              <small style={{ opacity: 0.6 }}>{item.description}</small>
            </button>
          ))}
        </div>
      )}
    </div>
  );
}
