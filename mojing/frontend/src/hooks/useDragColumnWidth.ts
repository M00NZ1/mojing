import { useCallback, useState } from 'react';

function readStored(storageKey: string, defaultPx: number, minPx: number, maxPx: number): number {
  try {
    const v = Number(localStorage.getItem(storageKey));
    if (!Number.isFinite(v)) return defaultPx;
    return Math.min(maxPx, Math.max(minPx, Math.round(v)));
  } catch {
    return defaultPx;
  }
}

/** 桌面侧栏：拖拽调整宽度，松手写入 localStorage；双击可恢复默认。 */
export function useDragColumnWidth(storageKey: string, defaultPx: number, minPx: number, maxPx: number) {
  const [width, setWidth] = useState(() => readStored(storageKey, defaultPx, minPx, maxPx));

  const clamp = useCallback((n: number) => Math.min(maxPx, Math.max(minPx, Math.round(n))), [minPx, maxPx]);

  const reset = useCallback(() => {
    setWidth(defaultPx);
    try {
      localStorage.setItem(storageKey, String(defaultPx));
    } catch {
      /* ignore */
    }
  }, [defaultPx, storageKey]);

  const beginDrag = useCallback(
    (e: React.MouseEvent, startWidth: number) => {
      if (e.button !== 0) return;
      e.preventDefault();
      const startX = e.clientX;
      let last = clamp(startWidth);
      const onMove = (ev: MouseEvent) => {
        const dx = ev.clientX - startX;
        last = clamp(startWidth + dx);
        setWidth(last);
      };
      const onUp = () => {
        window.removeEventListener('mousemove', onMove);
        window.removeEventListener('mouseup', onUp);
        document.body.style.removeProperty('cursor');
        document.body.style.removeProperty('user-select');
        try {
          localStorage.setItem(storageKey, String(last));
        } catch {
          /* ignore */
        }
      };
      document.body.style.cursor = 'col-resize';
      document.body.style.userSelect = 'none';
      window.addEventListener('mousemove', onMove);
      window.addEventListener('mouseup', onUp);
    },
    [clamp, storageKey],
  );

  return { width, setWidth: (n: number) => setWidth(clamp(n)), reset, beginDrag };
}
