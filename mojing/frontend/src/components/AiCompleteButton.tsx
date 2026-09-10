import React, { useEffect, useRef, useState } from 'react';

import { api } from '../api/client';
import { useToast } from '../hooks/useToast';
import { friendlyFetchError } from '../utils/userFacingError';
import UiIcon from './UiIcon';
import WorldResultText from './WorldResultText';
import './AiCompleteButton.css';

interface AiCompleteButtonProps {
  targetType: 'encyclopedia_entry' | 'character' | 'world_template';
  targetData: Record<string, unknown>;
  fieldsToComplete?: string[];
  onCompleted: (result: Record<string, unknown>) => boolean | void;
  label?: string;
}

const AiCompleteButton: React.FC<AiCompleteButtonProps> = ({
  targetType,
  targetData,
  fieldsToComplete,
  onCompleted,
  label = 'AI 补全',
}) => {
  const [loading, setLoading] = useState(false);
  const [feedback, setFeedback] = useState<{ text: string; error: boolean } | null>(null);
  const [reference, setReference] = useState<string | null>(null);
  const requestRef = useRef<AbortController | null>(null);
  useEffect(() => () => {
    requestRef.current?.abort();
    requestRef.current = null;
  }, []);
  const { showToast } = useToast();

  const handleClick = async () => {
    if (requestRef.current) return;
    const request = new AbortController();
    requestRef.current = request;
    setLoading(true);
    setFeedback(null);
    try {
      const data = await api.post('/ai/complete', {
        target_type: targetType,
        target_data: targetData,
        fields_to_complete: fieldsToComplete,
        extra_context: '',
      }, request.signal) as Record<string, unknown>;
      if (request.signal.aborted || requestRef.current !== request) return;

      const serverError = data.error;
      if (typeof serverError === 'string' && serverError.trim()) {
        setFeedback({ text: serverError.trim(), error: true });
        return;
      }

      const completedFields = (data.completed_fields as Record<string, unknown> | undefined) ?? {};
      const result: Record<string, unknown> = {};
      for (const [key, value] of Object.entries(completedFields)) {
        if (!key.startsWith('_')) {
          result[key] = value;
        }
      }
      const n = Object.keys(result).length;
      if (n === 0) {
        setFeedback({ text: '未收到可写入的内容，请补充信息后重试。', error: true });
        return;
      }
      if (onCompleted(result) === false) {
        setReference(typeof result.persona_prompt === 'string' ? result.persona_prompt : JSON.stringify(result, null, 2));
        setFeedback({ text: '当前人设已修改，补全结果未覆盖草稿', error: false });
      } else {
        setReference(null);
        showToast(`补全完成，已填入 ${n} 个字段`, 'success');
      }
    } catch (err) {
      if (!request.signal.aborted && requestRef.current === request) setFeedback({ text: friendlyFetchError(err), error: true });
    } finally {
      if (requestRef.current === request) {
        requestRef.current = null;
        setLoading(false);
      }
    }
  };

  return (
    <>
    <button
      type="button"
      className="btn btn-sm ai-complete-btn"
      onClick={loading ? () => {
        requestRef.current?.abort();
        requestRef.current = null;
        setLoading(false);
        setFeedback({ text: '已停止补全，编辑内容已保留。', error: false });
      } : handleClick}
    >
      {loading ? (
        <><UiIcon name="loading" className="ui-icon-loading" /><span>停止补全</span></>
      ) : (
        <><UiIcon name="sparkles" /><span>{label}</span></>
      )}
    </button>
    {(feedback || reference) && <div className="ai-complete-feedback">
      {feedback && <p role={feedback.error ? 'alert' : 'status'} className={feedback.error ? 'ai-complete-error' : undefined}>{feedback.text}</p>}
      {reference && <details className="ai-complete-reference">
        <summary>查看本次补全参考</summary>
        <WorldResultText key={reference} label="补全参考人设" text={reference} />
      </details>}
      {feedback?.error && <button type="button" className="btn btn-ghost btn-sm" disabled={loading} onClick={handleClick}>重试补全</button>}
    </div>}
    </>
  );
};

export default AiCompleteButton;
