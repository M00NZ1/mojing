import { useEffect, useRef, useState } from 'react';
import { api } from '../api/client';
import type { Participant } from '../types';
import { storyLineDisplayLabel } from '../utils/storyLinePresentation';
import InlineQueryError, { type RefreshableQuery } from './InlineQueryError';
import UiIcon from './UiIcon';

type Props = {
  participantsQuery: RefreshableQuery<Participant[]>;
  charactersQuery: RefreshableQuery<{ id: number; name: string }[]>;
  branches: { branch_id: string; label?: string; message_count: number }[];
  activeBranchId: string;
  onAddParticipant: (characterId: number) => void;
  onRemoveParticipant: (characterId: number, characterName: string) => void;
  addingParticipant: boolean;
  removingCharacterId?: number;
  onSwitchBranch: (branchId: string) => void;
  onSearch: () => void;
  onWorldSettings: () => void;
  onOpenBranchTree: () => void;
  onImportChat: () => void;
  onExportSessionArchive: () => void;
  onExportChatHtml: () => void;
  importingChat: boolean;
  exportingSessionArchive: boolean;
  exportingChat: boolean;
  onClose: (restoreFocus?: boolean) => void;
};

type MenuActionIconName = 'search' | 'world' | 'stories' | 'import' | 'archive' | 'document';

function MenuActionIcon({ name }: { name: MenuActionIconName }) {
  return <UiIcon name={name} className="chat-menu-action-icon" />;
}

export default function ChatMenu({
  participantsQuery,
  charactersQuery,
  branches,
  activeBranchId,
  onAddParticipant,
  onRemoveParticipant,
  addingParticipant,
  removingCharacterId,
  onSwitchBranch,
  onSearch,
  onWorldSettings,
  onOpenBranchTree,
  onImportChat,
  onExportSessionArchive,
  onExportChatHtml,
  importingChat,
  exportingSessionArchive,
  exportingChat,
  onClose,
}: Props) {
  const [tab, setTab] = useState<'members' | 'branches'>('members');
  const menuRef = useRef<HTMLDivElement>(null);
  const onCloseRef = useRef(onClose);
  onCloseRef.current = onClose;
  const participants = participantsQuery.data ?? [];
  const participantsLoading = participantsQuery.isFetching;
  const participantsError = participantsQuery.error;
  const charactersLoading = charactersQuery.isFetching;
  const charactersError = charactersQuery.error;
  const availableCharacters = (charactersQuery.data ?? []).filter(
    (character) => !participants.some((participant) => participant.character.id === character.id),
  );
  const addParticipantLabel = participantsLoading || charactersLoading
    ? '正在加载角色…'
    : addingParticipant
      ? '正在添加…'
      : participantsError
        ? '会话角色加载失败'
        : charactersError
          ? '角色列表加载失败'
          : availableCharacters.length === 0
            ? '没有可添加角色'
            : '添加角色…';

  useEffect(() => {
    const menu = menuRef.current;
    const frame = window.requestAnimationFrame(() => {
      menu?.querySelector<HTMLElement>('[data-chat-menu-close]')?.focus();
    });
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        event.preventDefault();
        event.stopPropagation();
        onCloseRef.current();
        return;
      }
      if (event.key !== 'Tab' || !menu) return;
      const focusable = Array.from(menu.querySelectorAll<HTMLElement>(
        'button:not(:disabled), input:not(:disabled), select:not(:disabled), textarea:not(:disabled), [href], [tabindex]:not([tabindex="-1"])',
      )).filter((element) => (
        element.getAttribute('aria-hidden') !== 'true'
        && element.getClientRects().length > 0
        && window.getComputedStyle(element).visibility !== 'hidden'
      ));
      if (focusable.length === 0) return;
      const first = focusable[0];
      const last = focusable[focusable.length - 1];
      if (!menu.contains(document.activeElement)) {
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
    return () => {
      window.cancelAnimationFrame(frame);
      document.removeEventListener('keydown', handleKeyDown);
    };
  }, []);

  return (
    <>
      <div className="chat-menu-overlay" aria-hidden="true" onClick={() => onClose()} />
      <div ref={menuRef} id="chat-menu" className="chat-menu" role="dialog" aria-modal="true" aria-label="会话菜单">
        <div className="chat-menu-header">
          <div className="chat-menu-tabs">
            <button type="button"
              className={`chat-menu-tab ${tab === 'members' ? 'active' : ''}`}
              onClick={() => setTab('members')}
            >
              参与角色
            </button>
            <button type="button"
              className={`chat-menu-tab ${tab === 'branches' ? 'active' : ''}`}
              onClick={() => setTab('branches')}
            >
              故事线
            </button>
          </div>
          <button type="button" className="chat-menu-close" data-chat-menu-close onClick={() => onClose()} aria-label="关闭菜单" title="关闭菜单">
            <UiIcon name="close" />
          </button>
        </div>

        {tab === 'members' && (
          <div className="chat-menu-body">
            <div className="chat-menu-actions chat-menu-action-grid">
              <button type="button" className="chat-menu-action-btn" onClick={() => { onClose(false); onSearch(); }}>
                <MenuActionIcon name="search" />
                <span>搜索对话</span>
              </button>
              <button type="button" className="chat-menu-action-btn" onClick={() => { onClose(false); onWorldSettings(); }}>
                <MenuActionIcon name="world" />
                <span>世界设定</span>
              </button>
              <button type="button" className="chat-menu-action-btn" onClick={() => { onClose(false); onOpenBranchTree(); }}>
                <MenuActionIcon name="stories" />
                <span>故事线总览</span>
              </button>
              <button
                type="button"
                className="chat-menu-action-btn"
                disabled={importingChat}
                onClick={() => { onClose(); onImportChat(); }}
              >
                <MenuActionIcon name="import" />
                <span>{importingChat ? '正在导入…' : '导入聊天记录'}</span>
              </button>
              <button
                type="button"
                className="chat-menu-action-btn"
                disabled={exportingSessionArchive}
                onClick={() => { onClose(); onExportSessionArchive(); }}
              >
                <MenuActionIcon name="archive" />
                <span>{exportingSessionArchive ? '正在打包…' : '导出完整会话包'}</span>
              </button>
              <button
                type="button"
                className="chat-menu-action-btn"
                disabled={exportingChat}
                onClick={() => { onClose(); onExportChatHtml(); }}
              >
                <MenuActionIcon name="document" />
                <span>{exportingChat ? '正在导出…' : '导出当前故事线'}</span>
              </button>
            </div>
            <div className="chat-menu-member-tools">
              <strong>会话角色</strong>
              <select
                value=""
                disabled={participantsLoading || charactersLoading || addingParticipant || Boolean(participantsError) || Boolean(charactersError) || availableCharacters.length === 0}
                onChange={(event) => onAddParticipant(Number(event.target.value))}
                aria-label="添加会话角色"
              >
                <option value="" disabled>{addParticipantLabel}</option>
                {availableCharacters.map((character) => (
                  <option key={character.id} value={character.id}>{character.name}</option>
                ))}
              </select>
            </div>
            {Boolean(charactersError) && (
              <InlineQueryError
                message="角色列表加载失败"
                error={charactersError}
                retrying={charactersLoading}
                onRetry={() => { void charactersQuery.refetch(); }}
              />
            )}
            {Boolean(participantsError) && (
              <InlineQueryError
                message="会话角色加载失败"
                error={participantsError}
                retrying={participantsLoading}
                onRetry={() => { void participantsQuery.refetch(); }}
              />
            )}
            <div className="chat-menu-list">
              {participants.map((p) => (
                <div key={p.id} className="chat-menu-member">
                  <div className="chat-menu-member-avatar">
                    {p.character.avatar_image_path ? (
                      <div className="chat-msg-avatar" style={{ backgroundImage: `url(${api.mediaRefUrl(p.character.avatar_image_path)})`, backgroundSize: 'cover', backgroundPosition: 'center' }} />
                    ) : (
                      <div className="chat-msg-avatar" style={{ background: 'var(--accent)', display: 'flex', alignItems: 'center', justifyContent: 'center', color: '#fff', fontSize: '0.7rem' }}>{p.character.name[0]}</div>
                    )}
                  </div>
                  <span className="chat-menu-member-name">{p.character.name}</span>
                  <button
                    type="button"
                    className="chat-menu-remove"
                    disabled={removingCharacterId === p.character.id}
                    onClick={() => onRemoveParticipant(p.character.id, p.character.name)}
                    title="移出本会话"
                    aria-label={`将${p.character.name}移出本会话`}
                  >
                    <UiIcon name="close" />
                  </button>
                </div>
              ))}
              {participants.length === 0 && (
                <div className="chat-menu-empty">还没有角色，点击上方添加</div>
              )}
            </div>
          </div>
        )}

        {tab === 'branches' && (
          <div className="chat-menu-body">
            <div className="chat-menu-list">
              {branches.map((branch) => {
                const isActive = branch.branch_id === activeBranchId;
                const displayLabel = storyLineDisplayLabel(branch.branch_id, branch.label);
                return (
                  <button
                    type="button"
                    key={branch.branch_id}
                    className={`chat-menu-branch ${isActive ? 'active' : ''}`}
                    onClick={() => { onSwitchBranch(branch.branch_id); onClose(); }}
                    aria-current={isActive ? 'true' : undefined}
                    title={`切换到${displayLabel}`}
                  >
                    <div className="chat-menu-branch-name">{displayLabel}</div>
                    {isActive && <span className="chat-menu-current">当前</span>}
                    <div className="chat-menu-branch-count">{branch.message_count} 条</div>
                  </button>
                );
              })}
            </div>
          </div>
        )}
      </div>
    </>
  );
}
