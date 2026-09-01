const KEY = 'ds_pro_speaker_turn_mode';

export type SpeakerTurnMode = 'auto' | 'manual';

export function loadSpeakerTurnMode(): SpeakerTurnMode {
  try {
    const v = localStorage.getItem(KEY);
    return v === 'manual' ? 'manual' : 'auto';
  } catch {
    return 'auto';
  }
}

export function saveSpeakerTurnMode(mode: SpeakerTurnMode) {
  try {
    localStorage.setItem(KEY, mode);
  } catch {
    /* ignore */
  }
}
