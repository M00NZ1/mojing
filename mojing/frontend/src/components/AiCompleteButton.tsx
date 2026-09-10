import React, { useEffect, useRef, useState } from 'react';

import { api } from '../api/client';
import { useToast } from '../hooks/useToast';
import { friendlyFetchError } from '../utils/userFacingError';
import UiIcon from './UiIcon';

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
        showToast(`${serverError.trim()}\uFF08Web\uFF09`, 'error');
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
        showToast('未收到可写入的字段，请检查是否已填写部分信息或稍后重试（Web）', 'warn');
        return;
      }
      if (onCompleted(result) === false) {
        showToast('当前人设已修改，补全结果未覆盖草稿', 'warn');
      } else {
        showToast(`AI 补全完成，已填入 ${n} 个字段（Web）`, 'success');
      }
    } catch (err) {
      if (!request.signal.aborted && requestRef.current === request) showToast(friendlyFetchError(err), 'error');
    } finally {
      if (requestRef.current === request) {
        requestRef.current = null;
        setLoading(false);
      }
    }
  };

  return (
    <button
      type="button"
      className="btn btn-sm ai-complete-btn"
      onClick={loading ? () => {
        requestRef.current?.abort();
        requestRef.current = null;
        setLoading(false);
      } : handleClick}
    >
      {loading ? (
        <><UiIcon name="loading" className="ui-icon-loading" /><span>停止补全</span></>
      ) : (
        <><UiIcon name="sparkles" /><span>{label}</span></>
      )}
    </button>
  );
};

export default AiCompleteButton;
