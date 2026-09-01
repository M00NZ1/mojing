import type { SystemStatus } from '../types';
import { COMPACT_LAYOUT_QUERY, useMediaQuery } from '../hooks/useMediaQuery';

type Props = {
  status?: SystemStatus;
  onCreateSession?: () => void;
  onGoToCharacters?: () => void;
  onGoToEncyclopedia?: () => void;
};

export default function GuidePanel({ status, onCreateSession, onGoToCharacters, onGoToEncyclopedia }: Props) {
  const isCompactLayout = useMediaQuery(COMPACT_LAYOUT_QUERY);
  return (
    <section className="guide-panel">
      <div className="card-header">
        <div>
          <p className="eyebrow">快速开始</p>
          <h2>三步上手</h2>
        </div>
        <span className="subtle-pill">{status?.database_ready ? '系统就绪' : '初始化中'}</span>
      </div>

      <ol className="guide-list" style={{ padding: 0, listStyle: 'none' }}>
        <li style={{ marginBottom: 16 }}>
          <div style={{ display: 'flex', gap: 12, alignItems: 'flex-start' }}>
            <div style={{ width: 28, height: 28, borderRadius: '50%', background: 'var(--accent)', color: '#fff', display: 'flex', alignItems: 'center', justifyContent: 'center', flexShrink: 0, fontSize: '0.82rem', fontWeight: 600 }}>1</div>
            <div>
              <strong style={{ fontSize: '0.9rem' }}>创建角色</strong>
              <p style={{ fontSize: '0.78rem', color: 'var(--text-2)', margin: '2px 0 6px' }}>先设定你想要对话的角色——给它起名、配模型、写人设。</p>
              <button type="button" className="btn btn-primary btn-sm" onClick={onGoToCharacters}>去创建角色</button>
            </div>
          </div>
        </li>
        <li style={{ marginBottom: 16 }}>
          <div style={{ display: 'flex', gap: 12, alignItems: 'flex-start' }}>
            <div style={{ width: 28, height: 28, borderRadius: '50%', background: 'var(--accent-2)', color: '#fff', display: 'flex', alignItems: 'center', justifyContent: 'center', flexShrink: 0, fontSize: '0.82rem', fontWeight: 600 }}>2</div>
            <div>
              <strong style={{ fontSize: '0.9rem' }}>创建对话</strong>
              <p style={{ fontSize: '0.78rem', color: 'var(--text-2)', margin: '2px 0 6px' }}>创建一个新对话会话，把角色加进来。</p>
              <button type="button" className="btn btn-primary btn-sm" onClick={onCreateSession}>开始新对话</button>
            </div>
          </div>
        </li>
        <li>
          <div style={{ display: 'flex', gap: 12, alignItems: 'flex-start' }}>
            <div style={{ width: 28, height: 28, borderRadius: '50%', background: 'var(--text-3)', color: '#fff', display: 'flex', alignItems: 'center', justifyContent: 'center', flexShrink: 0, fontSize: '0.82rem', fontWeight: 600 }}>3</div>
            <div>
              <strong style={{ fontSize: '0.9rem' }}>查阅世界观</strong>
              <p style={{ fontSize: '0.78rem', color: 'var(--text-2)', margin: '2px 0 6px' }}>
                {isCompactLayout
                  ? '浏览内置百科模板与资料库，或创建你自己的世界观。'
                  : '浏览内置多套百科资料库，或创建你自己的世界观。'}
              </p>
              <button type="button" className="btn btn-ghost btn-sm" style={{ marginRight: 6 }} onClick={onGoToEncyclopedia}>浏览百科</button>
            </div>
          </div>
        </li>
      </ol>

      {status && (
        <div style={{ marginTop: 16, paddingTop: 12, borderTop: '1px solid var(--line)', fontSize: '0.72rem', color: 'var(--text-2)' }}>
          <span style={{ marginRight: 12 }}>语音：{status.builtin_tts_ready ? '可用' : '未就绪'}</span>
          <span style={{ marginRight: 12 }}>克隆：{status.cloning_tts_ready ? '可用' : '未就绪'}</span>
          <span>Python: {status.python_version || '-'}</span>
        </div>
      )}
    </section>
  );
}
