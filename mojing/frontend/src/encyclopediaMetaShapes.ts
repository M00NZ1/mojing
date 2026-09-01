/**
 * 百科各 entry_type 的 meta_json **顶级键**清单，与编辑端 schema 同步维护。
 * 用于校验、文档或代码生成；运行时仍以 `EncyclopediaMetaJson`（Record）为主。
 */
import type { EncyclopediaMetaJson, EncyclopediaSchemaEntryKey } from './types';

const SOURCE_KEYS = [
  'source_url',
  'source_page_title',
  'source_retrieved_at',
  'source_trust_level',
  'verification_status',
  'canon_scope',
  'canon_conflicts',
  'unknown_fields',
] as const;

const CUSTOM_KEY = 'custom_fields' as const;

/** 各 schema 类型在 meta_json 中出现的顶级键（含来源与自定义字段后缀） */
export const ENCYCLOPEDIA_META_TOP_KEYS: Record<EncyclopediaSchemaEntryKey, readonly string[]> = {
  world: [
    'alias',
    'core_rules',
    'world_laws',
    'era_background',
    'civilization_stage',
    'tech_magic_level',
    'world_map_structure',
    'cosmology',
    'timeline_summary',
    ...SOURCE_KEYS,
    CUSTOM_KEY,
  ],
  character: [
    'alias',
    'character_type',
    'race',
    'bloodline',
    'family',
    'gender',
    'age',
    'faction',
    'status_record',
    'appearance',
    'personality',
    'background',
    'growth_path',
    'abilities',
    'equipment',
    'relationships',
    ...SOURCE_KEYS,
    CUSTOM_KEY,
  ],
  location: [
    'alias',
    'location_type',
    'region',
    'controller',
    'status',
    'population',
    'landmarks',
    'resources',
    'travel_routes',
    'hazards',
    'local_rules',
    ...SOURCE_KEYS,
    CUSTOM_KEY,
  ],
  faction: [
    'alias',
    'faction_type',
    'founder',
    'founded_year',
    'leader',
    'headquarters',
    'status',
    'doctrine',
    'history',
    'war_records',
    'hierarchy',
    'departments',
    'members',
    'allies',
    'enemies',
    ...SOURCE_KEYS,
    CUSTOM_KEY,
  ],
  event: [
    'event_type',
    'time_label',
    'location',
    'participants',
    'causes',
    'process',
    'result',
    'impact',
    'world_status_changes',
    ...SOURCE_KEYS,
    CUSTOM_KEY,
  ],
  item: [
    'alias',
    'item_type',
    'rarity',
    'creator',
    'owner_records',
    'source_location',
    'appearance',
    'effects',
    'limitations',
    'cost',
    'history',
    ...SOURCE_KEYS,
    CUSTOM_KEY,
  ],
  skill: [
    'skill_type',
    'energy_system',
    'prerequisites',
    'cost_rules',
    'counter_relations',
    'effects',
    'side_effects',
    'stages',
    ...SOURCE_KEYS,
    CUSTOM_KEY,
  ],
  profession: [
    'system_type',
    'promotion_conditions',
    'growth_path',
    'class_transfer_path',
    'limitations',
    'skill_pool',
    'equipment_limitations',
    ...SOURCE_KEYS,
    CUSTOM_KEY,
  ],
  concept: [
    'alias',
    'concept_type',
    'definition',
    'scope',
    'mechanism',
    'limits',
    'examples',
    'counterexamples',
    ...SOURCE_KEYS,
    CUSTOM_KEY,
  ],
  timeline: [
    'calendar',
    'time_label',
    'time_order',
    'branch',
    'events',
    ...SOURCE_KEYS,
    CUSTOM_KEY,
  ],
};

export function encyclopediaMetaKeysForType(t: EncyclopediaSchemaEntryKey): readonly string[] {
  return ENCYCLOPEDIA_META_TOP_KEYS[t];
}

/**
 * 将 meta 限制为「已知 schema 类型」下的索引签名；不改变运行时值。
 * 在 `entry_type` 已收窄为 `EncyclopediaSchemaEntryKey` 时使用。
 */
export type EncyclopediaMetaBySchema = {
  [K in EncyclopediaSchemaEntryKey]: EncyclopediaMetaJson;
};
