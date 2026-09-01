import type { ReactNode } from 'react';

export type UiIconName =
  | 'archive'
  | 'attachment'
  | 'back'
  | 'book'
  | 'bookmark'
  | 'branch'
  | 'chat'
  | 'close'
  | 'copy'
  | 'cost'
  | 'delete'
  | 'document'
  | 'edit'
  | 'grid'
  | 'import'
  | 'loading'
  | 'menu'
  | 'microphone'
  | 'more'
  | 'narrator'
  | 'plus'
  | 'panel'
  | 'person'
  | 'quote'
  | 'regenerate'
  | 'search'
  | 'settings'
  | 'smile'
  | 'sparkles'
  | 'stop'
  | 'stories'
  | 'summary'
  | 'volume'
  | 'warning'
  | 'world';

type Props = {
  name: UiIconName;
  className?: string;
};

export default function UiIcon({ name, className }: Props) {
  let content: ReactNode;

  switch (name) {
    case 'archive':
      content = <><path d="M4 8h16v12H4zM3 4h18v4H3z" /><path d="M9 12h6" /></>;
      break;
    case 'attachment':
      content = <path d="m8.5 12.8 6.8-6.8a3.2 3.2 0 0 1 4.5 4.5l-8.6 8.6a5 5 0 0 1-7.1-7.1l8.2-8.2M7.4 15.6l8.2-8.2" />;
      break;
    case 'back':
      content = <><path d="m10 5-7 7 7 7" /><path d="M3 12h18" /></>;
      break;
    case 'book':
      content = <><path d="M4 5.5c2.8-.8 5.5-.2 8 1.8v12c-2.5-2-5.2-2.6-8-1.8zM20 5.5c-2.8-.8-5.5-.2-8 1.8v12c2.5-2 5.2-2.6 8-1.8z" /></>;
      break;
    case 'bookmark':
      content = <path d="M7 4h10v16l-5-3.2L7 20z" />;
      break;
    case 'branch':
    case 'stories':
      content = <><circle cx="7" cy="5" r="2" /><circle cx="17" cy="12" r="2" /><circle cx="7" cy="19" r="2" /><path d="M7 7v10M9 7c0 3 2.5 5 6 5" /></>;
      break;
    case 'chat':
      content = <path d="M5 5h14v11H10l-5 4z" />;
      break;
    case 'close':
      content = <path d="m6 6 12 12M18 6 6 18" />;
      break;
    case 'copy':
      content = <><rect x="8" y="8" width="11" height="11" rx="2" /><path d="M16 8V6a2 2 0 0 0-2-2H6a2 2 0 0 0-2 2v8a2 2 0 0 0 2 2h2" /></>;
      break;
    case 'cost':
      content = <><ellipse cx="12" cy="6" rx="7" ry="3" /><path d="M5 6v5c0 1.7 3.1 3 7 3s7-1.3 7-3V6M5 11v5c0 1.7 3.1 3 7 3s7-1.3 7-3v-5" /></>;
      break;
    case 'delete':
      content = <><path d="M5 7h14M9 7V4h6v3M7 7l1 13h8l1-13" /><path d="M10 11v5M14 11v5" /></>;
      break;
    case 'document':
    case 'narrator':
      content = <><path d="M6 3h8l4 4v14H6zM14 3v5h5" /><path d="M9 12h6M9 16h6" /></>;
      break;
    case 'edit':
      content = <><path d="m14.5 5.5 4 4L9 19l-4.5.5L5 15z" /><path d="m12.5 7.5 4 4" /></>;
      break;
    case 'grid':
      content = <><rect x="4" y="4" width="6" height="6" rx="1" /><rect x="14" y="4" width="6" height="6" rx="1" /><rect x="4" y="14" width="6" height="6" rx="1" /><rect x="14" y="14" width="6" height="6" rx="1" /></>;
      break;
    case 'import':
      content = <><path d="M12 3v11m-4-4 4 4 4-4" /><path d="M5 17v3h14v-3" /></>;
      break;
    case 'loading':
      content = <><circle cx="12" cy="12" r="8.5" opacity="0.25" /><path d="M12 3.5a8.5 8.5 0 0 1 8.5 8.5" /></>;
      break;
    case 'menu':
      content = <path d="M5 7h14M5 12h14M5 17h14" />;
      break;
    case 'microphone':
      content = <><rect x="9" y="3" width="6" height="12" rx="3" /><path d="M5.5 11.5a6.5 6.5 0 0 0 13 0M12 18v3M8.5 21h7" /></>;
      break;
    case 'more':
      content = <><circle cx="5" cy="12" r="1" fill="currentColor" stroke="none" /><circle cx="12" cy="12" r="1" fill="currentColor" stroke="none" /><circle cx="19" cy="12" r="1" fill="currentColor" stroke="none" /></>;
      break;
    case 'plus':
      content = <path d="M12 5v14M5 12h14" />;
      break;
    case 'panel':
      content = <><rect x="3" y="4" width="18" height="16" rx="2" /><path d="M14 4v16M17 8h1M17 12h1M17 16h1" /></>;
      break;
    case 'person':
      content = <><circle cx="12" cy="8" r="4" /><path d="M4.5 21a7.5 7.5 0 0 1 15 0" /></>;
      break;
    case 'quote':
      content = <><path d="M5 6h14v10H9l-4 3z" /><path d="M9 10h6M9 13h4" /></>;
      break;
    case 'regenerate':
      content = <><path d="M19 8V4l-2 2a8 8 0 1 0 2.2 8" /><path d="M15 4h4v4" /></>;
      break;
    case 'search':
      content = <><circle cx="10.5" cy="10.5" r="5.5" /><path d="m15 15 4.5 4.5" /></>;
      break;
    case 'settings':
      content = <><path d="M4 7h7M15 7h5M4 17h4M12 17h8" /><circle cx="13" cy="7" r="2" /><circle cx="10" cy="17" r="2" /></>;
      break;
    case 'smile':
      content = <><circle cx="12" cy="12" r="9" /><path d="M8.5 14.5c1 1.4 2.1 2 3.5 2s2.5-.6 3.5-2" /><path d="M9 9h.01M15 9h.01" strokeWidth="2.4" /></>;
      break;
    case 'sparkles':
      content = <><path d="m12 3 1.2 3.8L17 8l-3.8 1.2L12 13l-1.2-3.8L7 8l3.8-1.2zM18.5 14l.7 2.3 2.3.7-2.3.7-.7 2.3-.7-2.3-2.3-.7 2.3-.7zM5.5 13l.7 2.3 2.3.7-2.3.7L5.5 19l-.7-2.3-2.3-.7 2.3-.7z" /></>;
      break;
    case 'stop':
      content = <rect x="6" y="6" width="12" height="12" rx="1.5" fill="currentColor" stroke="none" />;
      break;
    case 'summary':
      content = <><path d="M6 3h8l4 4v14H6zM14 3v5h5" /><path d="M9 12h6M9 16h4" /></>;
      break;
    case 'volume':
      content = <><path d="M4 10v4h4l5 4V6L8 10z" /><path d="M16 9a4 4 0 0 1 0 6M18.5 6.5a7.5 7.5 0 0 1 0 11" /></>;
      break;
    case 'warning':
      content = <><path d="M12 3 2.8 20h18.4z" /><path d="M12 9v5M12 17.5h.01" strokeWidth="2" /></>;
      break;
    case 'world':
      content = <><circle cx="12" cy="12" r="8.5" /><path d="M3.8 12h16.4M12 3.5c2.2 2.3 3.3 5.1 3.3 8.5S14.2 18.2 12 20.5M12 3.5C9.8 5.8 8.7 8.6 8.7 12s1.1 6.2 3.3 8.5" /></>;
      break;
    default:
      content = null;
  }

  return (
    <svg
      className={className ? `ui-icon ${className}` : 'ui-icon'}
      viewBox="0 0 24 24"
      aria-hidden="true"
      focusable="false"
    >
      {content}
    </svg>
  );
}
