import { useState } from 'react';

import { api } from '../api/client';
import { useToast } from '../hooks/useToast';
import { friendlyFetchError } from '../utils/userFacingError';
import UiIcon from './UiIcon';

export default function ImageGenerateButton({ prompt, onImageGenerated }: { prompt: string; onImageGenerated?: (url: string) => void }) {
  const { showToast } = useToast();
  const [loading, setLoading] = useState(false);
  const [imageUrl, setImageUrl] = useState('');
  const [error, setError] = useState('');

  async function handleGenerate() {
    if (!prompt.trim()) {
      showToast('请先输入生图描述（Web）', 'warn');
      return;
    }
    setLoading(true);
    setError('');
    setImageUrl('');
    try {
      const data = await api.post('/images/generate', {
        prompt,
        size: '1024x1024',
        quality: 'standard',
      }) as { urls?: string[]; error?: string };
      if (data.error) {
        const msg = data.error;
        setError(msg);
        showToast(`${msg}\uFF08Web\uFF09`, 'error');
        return;
      }
      if (data.urls && data.urls.length > 0) {
        setImageUrl(data.urls[0]);
        onImageGenerated?.(data.urls[0]);
        showToast('插图已生成（Web）', 'success');
      } else {
        const msg = data.error || '生图失败';
        setError(msg);
        showToast(`${msg}（Web）`, 'error');
      }
    } catch (e: unknown) {
      const msg = friendlyFetchError(e);
      setError(msg);
      showToast(msg, 'error');
    } finally {
      setLoading(false);
    }
  }

  return (
    <div style={{ marginTop: 8 }}>
      <button type="button" className="btn btn-ghost btn-sm" onClick={handleGenerate} disabled={loading || !prompt.trim()} title="用 AI 生成插图">
        {loading ? (
          <><UiIcon name="loading" className="ui-icon-loading" /><span>生成中</span></>
        ) : (
          <><UiIcon name="sparkles" /><span>生成插图</span></>
        )}
      </button>
      {error && <p className="text-error" style={{ fontSize: '0.78rem', marginTop: 4 }}>{error}</p>}
      {imageUrl && (
        <div style={{ marginTop: 8, borderRadius: 'var(--radius-sm)', overflow: 'hidden', maxWidth: 300 }}>
          <img src={imageUrl} alt="AI 生成插图" style={{ width: '100%', display: 'block' }} />
        </div>
      )}
    </div>
  );
}
