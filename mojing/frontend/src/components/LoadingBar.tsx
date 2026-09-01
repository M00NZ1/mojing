import { useEffect, useState } from 'react';
import { useIsFetching, useIsMutating } from '@tanstack/react-query';

export default function GlobalLoadingBar() {
  const isFetching = useIsFetching();
  const isMutating = useIsMutating();
  const [visible, setVisible] = useState(false);
  const [width, setWidth] = useState(0);

  const loading = isFetching > 0 || isMutating > 0;

  useEffect(() => {
    if (loading) {
      setVisible(true);
      setWidth(60);
      const timer = setTimeout(() => setWidth(90), 300);
      return () => clearTimeout(timer);
    } else {
      setWidth(100);
      const timer = setTimeout(() => {
        setVisible(false);
        setWidth(0);
      }, 400);
      return () => clearTimeout(timer);
    }
  }, [loading]);

  if (!visible && width === 0) return null;

  return (
    <div
      style={{
        position: 'fixed', top: 0, left: 0, right: 0, zIndex: 99999,
        height: 3, background: 'transparent',
        transition: 'opacity 0.3s',
        opacity: visible ? 1 : 0,
      }}
    >
      <div
        style={{
          height: '100%',
          width: `${width}%`,
          background: 'linear-gradient(90deg, var(--accent), var(--accent-2))',
          transition: 'width 0.3s ease-in-out',
          borderRadius: '0 2px 2px 0',
          boxShadow: '0 0 8px var(--accent)',
        }}
      />
    </div>
  );
}
