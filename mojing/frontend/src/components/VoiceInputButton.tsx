import React, { useCallback, useEffect, useRef, useState } from 'react';

import { api } from '../api/client';
import { useToast } from '../hooks/useToast';
import UiIcon from './UiIcon';

interface VoiceInputButtonProps {
  onTranscribed: (text: string) => void;
  disabled?: boolean;
}

const VoiceInputButton: React.FC<VoiceInputButtonProps> = ({ onTranscribed, disabled }) => {
  const { showToast } = useToast();
  const [isRecording, setIsRecording] = useState(false);
  const [isUploading, setIsUploading] = useState(false);
  const mediaRecorderRef = useRef<MediaRecorder | null>(null);
  const chunksRef = useRef<Blob[]>([]);
  const holdingRef = useRef(false);
  const startingRef = useRef(false);

  const startRecording = useCallback(async () => {
    if (startingRef.current || isUploading || mediaRecorderRef.current?.state === 'recording') return;
    startingRef.current = true;
    let stream: MediaStream | null = null;
    try {
      const acquiredStream = await navigator.mediaDevices.getUserMedia({ audio: true });
      stream = acquiredStream;
      if (!holdingRef.current) {
        acquiredStream.getTracks().forEach((track) => track.stop());
        return;
      }

      const recorderOptions = MediaRecorder.isTypeSupported('audio/webm') ? { mimeType: 'audio/webm' } : undefined;
      const mediaRecorder = new MediaRecorder(acquiredStream, recorderOptions);
      mediaRecorderRef.current = mediaRecorder;
      chunksRef.current = [];

      mediaRecorder.ondataavailable = (event) => {
        if (event.data.size > 0) {
          chunksRef.current.push(event.data);
        }
      };

      mediaRecorder.onstop = async () => {
        mediaRecorderRef.current = null;
        acquiredStream.getTracks().forEach((track) => track.stop());
        const blob = new Blob(chunksRef.current, { type: mediaRecorder.mimeType || 'audio/webm' });
        if (blob.size === 0) {
          setIsRecording(false);
          return;
        }

        setIsRecording(false);
        setIsUploading(true);

        try {
          const formData = new FormData();
          formData.append('file', blob, 'recording.webm');
          const data = await api.voiceTranscribe(formData) as { text?: string };
          if (data.text?.trim()) {
            onTranscribed(data.text.trim());
          } else {
            showToast('\u8bed\u97f3\u8f6c\u5f55\u672a\u8fd4\u56de\u6587\u5b57', 'warn');
          }
        } catch (error) {
          console.error('语音转录失败:', error);
          showToast('语音转录失败，请检查网络与后端服务', 'error');
        } finally {
          setIsUploading(false);
        }
      };

      mediaRecorder.start();
      setIsRecording(true);
    } catch (error) {
      stream?.getTracks().forEach((track) => track.stop());
      console.error('录音启动失败:', error);
      showToast('无法访问麦克风，请在浏览器设置中允许本站点使用麦克风', 'error');
    } finally {
      startingRef.current = false;
    }
  }, [isUploading, onTranscribed, showToast]);

  const stopRecording = useCallback(() => {
    holdingRef.current = false;
    if (mediaRecorderRef.current && mediaRecorderRef.current.state === 'recording') {
      mediaRecorderRef.current.stop();
    }
  }, []);

  const beginRecording = useCallback(() => {
    if (disabled || isUploading) return;
    holdingRef.current = true;
    void startRecording();
  }, [disabled, isUploading, startRecording]);

  useEffect(() => () => {
    holdingRef.current = false;
    if (mediaRecorderRef.current?.state === 'recording') mediaRecorderRef.current.stop();
  }, []);

  const stateLabel = isUploading ? '正在转写语音' : isRecording ? '松开并转写语音' : '按住录音';

  return (
    <button
      type="button"
      className={`chat-inputbar-btn voice-input-btn ${isRecording ? 'recording' : ''}`}
      disabled={disabled || isUploading}
      onPointerDown={(event) => {
        if (event.button !== 0) return;
        event.preventDefault();
        event.currentTarget.setPointerCapture(event.pointerId);
        beginRecording();
      }}
      onPointerUp={(event) => {
        event.preventDefault();
        stopRecording();
      }}
      onPointerCancel={stopRecording}
      onLostPointerCapture={stopRecording}
      onKeyDown={(event) => {
        if ((event.key === ' ' || event.key === 'Enter') && !event.repeat) {
          event.preventDefault();
          beginRecording();
        }
      }}
      onKeyUp={(event) => {
        if (event.key === ' ' || event.key === 'Enter') {
          event.preventDefault();
          stopRecording();
        }
      }}
      onBlur={stopRecording}
      onContextMenu={(event) => event.preventDefault()}
      title={stateLabel}
      aria-label={stateLabel}
      aria-pressed={isRecording}
    >
      <UiIcon name={isUploading ? 'loading' : 'microphone'} className={isUploading ? 'ui-icon-loading' : undefined} />
    </button>
  );
};

export default VoiceInputButton;
