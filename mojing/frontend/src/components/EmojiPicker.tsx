import { useEffect, useMemo, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import UiIcon from './UiIcon';

const EMOJI_CATEGORIES: { label: string; emojis: string[] }[] = [
  { label: '笑脸', emojis: ['😀','😃','😄','😁','😆','😅','🤣','😂','🙂','😊','😇','🥰','😍','🤩','😘','😗','😚','😋','😛','😜','🤪','😝','🤑','🤗','🤭','🫣','🤫','🤔','🫡','🤐','🤨','😐','😑','😶','😏','😒','🙄','😬','😮','😯','😲','😳','🥺','😢','😭','😤','😠','😡','🤬'] },
  { label: '手势', emojis: ['👍','👎','👊','✊','🤛','🤜','👏','🙌','👐','🤲','🤝','🙏','✌️','🤟','🤘','🤙','💪','🖕','✋','🤚','👋','🤳','💅','👀','🧠','👅','👄','💋'] },
  { label: '心', emojis: ['❤️','🧡','💛','💚','💙','💜','🖤','🤍','🤎','💔','❣️','💕','💞','💓','💗','💖','💘','💝'] },
  { label: '特殊', emojis: ['✨','🌟','⭐','🔥','💦','💨','💫','🎉','🎊','🎈','🎁','🏆','👑','🚀','💀','☠️','💩','🤡','👹','👺','👻','👽','👾','🤖'] },
  { label: '动物', emojis: ['😺','😸','😹','😻','😼','😽','🙀','😿','😾','🐶','🐱','🐭','🐹','🐰','🦊','🐻','🐼','🐨','🐯','🦁','🐮','🐷','🐸','🐵','🐔','🐧','🐦','🐤','🐣','🐥','🦆','🦅','🦉','🦇','🐺','🐗','🐴','🦄','🐝','🐛','🦋','🐌','🐞','🐜','🦟','🦗','🦂','🐢','🐍','🦎','🦖','🦕','🐙','🦑','🦐','🦞','🦀','🐡','🐠','🐟','🐬','🐳','🐋','🦈'] },
  { label: '食物', emojis: ['🍏','🍎','🍐','🍊','🍋','🍌','🍉','🍇','🍓','🫐','🍈','🍒','🍑','🥭','🍍','🥥','🥝','🍅','🍆','🥑','🥦','🥬','🥒','🌽','🥕','🧄','🧅','🥔','🍠','🥐','🍞','🥖','🥨','🧀','🥚','🍳','🧈','🥞','🧇','🥓','🥩','🍗','🍖','🌭','🍔','🍟','🍕','🫓','🥪','🥙','🧆','🌮','🌯','🥗','🥘','🫕','🥫','🍝','🍜','🍲','🍛','🍣','🍱','🥟','🦪','🍤','🍙','🍚','🍘','🍥','🥠','🥮','🍢','🍡','🍧','🍨','🍦','🥧','🧁','🍰','🎂','🍮','🍭','🍬','🍫','🍿','🍩','🍪','🌰','🥜','🍯'] },
];

type Props = {
  onSelect: (emoji: string) => void;
  onClose: (restoreFocus?: boolean) => void;
};

export default function EmojiPicker({ onSelect, onClose }: Props) {
  const [search, setSearch] = useState('');
  const [catIdx, setCatIdx] = useState(0);
  const pickerRef = useRef<HTMLDivElement>(null);
  const onCloseRef = useRef(onClose);
  onCloseRef.current = onClose;

  const filtered = useMemo(() => {
    if (!search.trim()) return EMOJI_CATEGORIES[catIdx]?.emojis || [];
    const q = search.trim().toLowerCase();
    const all = EMOJI_CATEGORIES.flatMap((c) => c.emojis);
    return all.filter((e) => e.includes(q));
  }, [search, catIdx]);

  useEffect(() => {
    const picker = pickerRef.current;
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        event.preventDefault();
        event.stopPropagation();
        onCloseRef.current();
        return;
      }
      if (event.key !== 'Tab' || !picker) return;
      const focusable = Array.from(picker.querySelectorAll<HTMLElement>(
        'button:not(:disabled), input:not(:disabled), select:not(:disabled), textarea:not(:disabled), [href], [tabindex]:not([tabindex="-1"])',
      )).filter((element) => (
        element.getAttribute('aria-hidden') !== 'true'
        && element.getClientRects().length > 0
        && window.getComputedStyle(element).visibility !== 'hidden'
      ));
      if (focusable.length === 0) return;
      const first = focusable[0];
      const last = focusable[focusable.length - 1];
      if (!picker.contains(document.activeElement)) {
        event.preventDefault();
        (event.shiftKey ? last : first).focus();
      } else if (event.shiftKey && document.activeElement === first) {
        event.preventDefault();
        last.focus();
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault();
        first.focus();
      }
    };
    document.addEventListener('keydown', handleKeyDown);
    return () => document.removeEventListener('keydown', handleKeyDown);
  }, []);

  return (
    <>
      {createPortal(
        <div className="emoji-overlay" aria-hidden="true" onClick={() => onClose()} />,
        document.body,
      )}
      <div ref={pickerRef} id="emoji-picker" className="emoji-picker" role="dialog" aria-modal="true" aria-label="选择表情">
        <div className="emoji-picker-header">
          <span>选择表情</span>
          <button type="button" className="emoji-close" onClick={() => onClose()} aria-label="关闭表情选择器" title="关闭">
            <UiIcon name="close" />
          </button>
        </div>
        <input
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          placeholder="搜索表情..."
          aria-label="搜索表情"
          style={{ width: '100%', boxSizing: 'border-box', padding: '6px 8px', marginBottom: 4, fontSize: '0.78rem', border: '1px solid var(--line)', borderRadius: 4, background: 'var(--input-bg)', color: 'var(--text)' }}
          autoFocus
        />
        <div style={{ display: 'flex', gap: 2, flexWrap: 'wrap', marginBottom: 4, padding: '2px 0' }}>
          {EMOJI_CATEGORIES.map((cat, i) => (
            <button type="button" key={cat.label} onClick={() => { setCatIdx(i); setSearch(''); }}
              aria-pressed={i === catIdx}
              style={{ fontSize: '0.7rem', padding: '2px 6px', background: i === catIdx ? 'var(--accent)' : 'var(--surface)', border: '1px solid var(--line)', borderRadius: 4, cursor: 'pointer', color: i === catIdx ? '#fff' : 'var(--text)' }}>
              {cat.label}
            </button>
          ))}
        </div>
        <div className="emoji-grid">
          {filtered.map((emoji) => (
            <button type="button" key={emoji} className="emoji-item" onClick={() => { onSelect(emoji); onClose(false); }}>{emoji}</button>
          ))}
        </div>
      </div>
    </>
  );
}
