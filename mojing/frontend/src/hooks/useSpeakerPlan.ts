import { useState } from 'react';
import { useMutation } from '@tanstack/react-query';

import { api } from '../api/client';
import type { Participant } from '../types';

export function useSpeakerPlan(sessionId: number, participants: Participant[] | undefined) {
  const [speakerPlanReason, setSpeakerPlanReason] = useState('');
  const [plannedSpeakerNames, setPlannedSpeakerNames] = useState<string[]>([]);
  const [pendingRoundSpeakers, setPendingRoundSpeakers] = useState<string[]>([]);

  function namesFromCharacterIds(ids: number[]) {
    return ids
      .map((id) => participants?.find((p) => p.character.id === id)?.character.name)
      .filter((n): n is string => Boolean(n));
  }

  const speakerPlanMutation = useMutation({
    mutationFn: (payload: { user_message?: string; max_auto_speakers?: number; branch_id?: string }) =>
      api.getSpeakerPlan(sessionId, payload),
    onSuccess: (plan) => {
      setSpeakerPlanReason(plan.reason);
      const names = namesFromCharacterIds(plan.character_ids);
      setPlannedSpeakerNames(names);
      setPendingRoundSpeakers(names);
    },
  });

  function applySpeakerPlanEvent(reason: string, characterIds?: number[]) {
    setSpeakerPlanReason(reason);
    if (characterIds && characterIds.length > 0) {
      const names = namesFromCharacterIds(characterIds);
      setPlannedSpeakerNames(names);
      setPendingRoundSpeakers(names);
    }
  }

  function onCharacterMessageEnded(characterId: number | null | undefined) {
    if (!characterId) return;
    const name = participants?.find((p) => p.character.id === characterId)?.character.name;
    if (!name) return;
    setPendingRoundSpeakers((prev) => prev.filter((n) => n !== name));
  }

  function clearSpeakerPlan() {
    setSpeakerPlanReason('');
    setPlannedSpeakerNames([]);
    setPendingRoundSpeakers([]);
  }

  return {
    speakerPlanReason,
    plannedSpeakerNames,
    pendingRoundSpeakers,
    previewSpeakerPlan: (payload: { user_message?: string; max_auto_speakers?: number; branch_id?: string }) =>
      speakerPlanMutation.mutate(payload),
    applySpeakerPlanEvent,
    onCharacterMessageEnded,
    clearSpeakerPlan,
  };
}
