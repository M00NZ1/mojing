import { useEffect, useMemo, useRef, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';

import { api } from '../api/client';
import type { SessionWorld } from '../types';

function normalizeSessionWorld(world: SessionWorld) {
  return {
    encyclopedia_id: world.encyclopedia_id ?? null,
    world_prompt: world.world_prompt ?? '',
    template_id: world.template_id ?? 'custom',
    gameplay_mode: world.gameplay_mode ?? '自由剧情',
    narrator_enabled: Boolean(world.narrator_enabled),
    narrator_name: world.narrator_name ?? '',
    choice_generation_enabled: Boolean(world.choice_generation_enabled),
    max_choice_count: world.max_choice_count ?? 3,
    suggested_choices: (world.suggested_choices_json ?? [])
      .map((item) => item.trim())
      .filter(Boolean)
      .join('\u0001'),
    anti_cheat_enabled: Boolean(world.anti_cheat_enabled),
    anti_cheat_prompt: world.anti_cheat_prompt ?? '',
  };
}

export function useSessionWorld(sessionId: number) {
  const queryClient = useQueryClient();
  const activeSaveRef = useRef<Promise<SessionWorld> | null>(null);
  const hydratedSessionIdRef = useRef<number | null>(null);
  const [encyclopediaId, setEncyclopediaId] = useState<number | null>(null);
  const [worldPrompt, setWorldPrompt] = useState('');
  const [worldTemplateId, setWorldTemplateId] = useState('custom');
  const [gameplayMode, setGameplayMode] = useState('自由剧情');
  const [narratorEnabled, setNarratorEnabled] = useState(false);
  const [narratorName, setNarratorName] = useState('旁白');
  const [choiceGenerationEnabled, setChoiceGenerationEnabled] = useState(true);
  const [maxChoiceCount, setMaxChoiceCount] = useState(3);
  const [suggestedChoicesText, setSuggestedChoicesText] = useState('');
  const [antiCheatEnabled, setAntiCheatEnabled] = useState(true);
  const [antiCheatPrompt, setAntiCheatPrompt] = useState('');
  const [editorBaseline, setEditorBaseline] = useState<ReturnType<typeof normalizeSessionWorld> | null>(null);

  const worldQuery = useQuery({
    queryKey: ['session-world', sessionId],
    queryFn: () => api.getSessionWorld(sessionId),
    enabled: Number.isFinite(sessionId),
  });

  const editorNormalized = useMemo(
    () => ({
      encyclopedia_id: encyclopediaId,
      world_prompt: worldPrompt,
      template_id: worldTemplateId,
      gameplay_mode: gameplayMode,
      narrator_enabled: narratorEnabled,
      narrator_name: narratorName,
      choice_generation_enabled: choiceGenerationEnabled,
      max_choice_count: maxChoiceCount,
      suggested_choices: suggestedChoicesText
        .split('\n')
        .map((item) => item.trim())
        .filter(Boolean)
        .join('\u0001'),
      anti_cheat_enabled: antiCheatEnabled,
      anti_cheat_prompt: antiCheatPrompt,
    }),
    [
      encyclopediaId,
      worldPrompt,
      worldTemplateId,
      gameplayMode,
      narratorEnabled,
      narratorName,
      choiceGenerationEnabled,
      maxChoiceCount,
      suggestedChoicesText,
      antiCheatEnabled,
      antiCheatPrompt,
    ],
  );

  const isWorldDirty =
    editorBaseline != null &&
    JSON.stringify(editorBaseline) !== JSON.stringify(editorNormalized);

  function hydrateWorldEditor(world: SessionWorld) {
    setEncyclopediaId(world.encyclopedia_id ?? null);
    setWorldPrompt(world.world_prompt);
    setWorldTemplateId(world.template_id);
    setGameplayMode(world.gameplay_mode);
    setNarratorEnabled(world.narrator_enabled);
    setNarratorName(world.narrator_name);
    setChoiceGenerationEnabled(world.choice_generation_enabled);
    setMaxChoiceCount(world.max_choice_count);
    setSuggestedChoicesText((world.suggested_choices_json ?? []).join('\n'));
    setAntiCheatEnabled(world.anti_cheat_enabled);
    setAntiCheatPrompt(world.anti_cheat_prompt);
    setEditorBaseline(normalizeSessionWorld(world));
    hydratedSessionIdRef.current = sessionId;
  }

  useEffect(() => {
    if (!worldQuery.data) return;
    const changingSession = hydratedSessionIdRef.current !== sessionId;
    if (!changingSession && isWorldDirty) return;
    hydrateWorldEditor(worldQuery.data);
  }, [sessionId, worldQuery.data, isWorldDirty]);

  const saveWorldMutation = useMutation({
    mutationFn: () =>
      api.updateSessionWorld(sessionId, {
        encyclopedia_id: encyclopediaId,
        world_prompt: worldPrompt,
        template_id: worldTemplateId,
        gameplay_mode: gameplayMode,
        narrator_enabled: narratorEnabled,
        narrator_name: narratorName,
        choice_generation_enabled: choiceGenerationEnabled,
        max_choice_count: maxChoiceCount,
        suggested_choices_json: suggestedChoicesText
          .split('\n')
          .map((item) => item.trim())
          .filter(Boolean),
        anti_cheat_enabled: antiCheatEnabled,
        anti_cheat_prompt: antiCheatPrompt,
      }),
    onSuccess: (saved) => {
      hydrateWorldEditor(saved);
      queryClient.setQueryData(['session-world', sessionId], saved);
    },
  });

  function saveWorld() {
    if (activeSaveRef.current) return activeSaveRef.current;
    const pending = saveWorldMutation.mutateAsync();
    activeSaveRef.current = pending;
    pending.then(
      () => {
        if (activeSaveRef.current === pending) activeSaveRef.current = null;
      },
      () => {
        if (activeSaveRef.current === pending) activeSaveRef.current = null;
      },
    );
    return pending;
  }

  async function prepareWorldForGeneration() {
    let current = worldQuery.data;
    if (!current) {
      const refreshed = await worldQuery.refetch();
      current = refreshed.data;
      if (!current) {
        if (refreshed.error instanceof Error) throw refreshed.error;
        throw new Error('会话世界配置尚未加载，请稍后重试');
      }
    }
    if (isWorldDirty || activeSaveRef.current) return saveWorld();
    return current;
  }

  return {
    worldPrompt,
    setWorldPrompt,
    encyclopediaId,
    setEncyclopediaId,
    worldTemplateId,
    setWorldTemplateId,
    gameplayMode,
    setGameplayMode,
    narratorEnabled,
    setNarratorEnabled,
    narratorName,
    setNarratorName,
    choiceGenerationEnabled,
    setChoiceGenerationEnabled,
    maxChoiceCount,
    setMaxChoiceCount,
    suggestedChoicesText,
    setSuggestedChoicesText,
    antiCheatEnabled,
    setAntiCheatEnabled,
    antiCheatPrompt,
    setAntiCheatPrompt,
    saveWorld,
    prepareWorldForGeneration,
    worldReady: Boolean(worldQuery.data),
    worldLoading: worldQuery.isLoading || (worldQuery.isFetching && !worldQuery.data),
    worldLoadError: worldQuery.error,
    retryWorldLoad: worldQuery.refetch,
    worldSaving: saveWorldMutation.isPending,
    worldSaveError: saveWorldMutation.error,
    isWorldDirty,
  };
}
