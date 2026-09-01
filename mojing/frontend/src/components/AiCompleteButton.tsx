import React, { useState } from 'react';

import { api } from '../api/client';
import { useToast } from '../hooks/useToast';
import { friendlyFetchError } from '../utils/userFacingError';
import UiIcon from './UiIcon';

interface AiCompleteButtonProps {
  targetType: 'encyclopedia_entry' | 'character' | 'world_template';
  targetData: Record<string, unknown>;
  fieldsToComplete?: string[];
  onCompleted: (result: Record<string, unknown>) => void;
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
  const { showToast } = useToast();

  const handleClick = async () => {
    setLoading(true);
    try {
      const data = await api.post('/ai/complete', {
        target_type: targetType,
        target_data: targetData,
        fields_to_complete: fieldsToComplete,
        extra_context: '',
      }) as Record<string, unknown>;

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
      onCompleted(result);
      showToast(`AI 补全完成，已填入 ${n} 个字段（Web）`, 'success');
    } catch (err) {
      showToast(friendlyFetchError(err), 'error');
    } finally {
      setLoading(false);
    }
  };

  return (
    <button
      type="button"
      className="btn btn-sm ai-complete-btn"
      onClick={handleClick}
      disabled={loading}
    >
      {loading ? (
        <><UiIcon name="loading" className="ui-icon-loading" /><span>生成中…</span></>
      ) : (
        <><UiIcon name="sparkles" /><span>{label}</span></>
      )}
    </button>
  );
};

export default AiCompleteButton;
