import type { CSSProperties } from 'react';
import { useCallback, useDeferredValue, useEffect, useMemo, useRef, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useBeforeUnload, useBlocker, useLocation, useNavigate, useSearchParams } from 'react-router-dom';
import { api } from '../api/client';
import BatchGenerateDialog from '../components/BatchGenerateDialog';
import { confirmModal } from '../components/ConfirmModal';
import CreationHomeLink from '../components/CreationHomeLink';
import InlineQueryError from '../components/InlineQueryError';
import ExpandableTextArea from '../components/ExpandableTextArea';
import SedimentReviewPanel, { type SedimentReviewLocation } from '../components/SedimentReviewPanel';
import UiIcon, { type UiIconName } from '../components/UiIcon';
import { useToast } from '../hooks/useToast';
import { useDragColumnWidth } from '../hooks/useDragColumnWidth';
import { COMPACT_LAYOUT_QUERY, useMediaQuery } from '../hooks/useMediaQuery';
import { readMoJingStorage, writeMoJingStorage } from '../utils/mojingStorage';
import { copyText } from '../utils/clipboard';
import {
  encyclopediaEntryDraftSnapshot,
  encyclopediaLibraryDraftSnapshot,
  mergeEncyclopediaEntryDraftSnapshot,
} from '../utils/encyclopediaDraftState';
import type {
  EncyclopediaEntry,
  EncyclopediaEntryDraft,
  EncyclopediaMetaJson,
  EncyclopediaSchemaEntryKey,
  WorldEncyclopedia,
} from '../types';

/** 百科 meta_json 读取辅助，避免在表单里散落 `as any` */
function metaRecord(m: EncyclopediaMetaJson | undefined): Record<string, unknown> {
  return (m ?? {}) as Record<string, unknown>;
}
function metaStringArray(m: EncyclopediaMetaJson | undefined, key: string): string[] {
  const v = metaRecord(m)[key];
  return Array.isArray(v) ? v.map((x) => String(x)) : [];
}
function metaNumber(m: EncyclopediaMetaJson | undefined, key: string, defaultVal: number): number {
  const v = metaRecord(m)[key];
  return typeof v === 'number' && !Number.isNaN(v) ? v : defaultVal;
}
function metaString(m: EncyclopediaMetaJson | undefined, key: string, defaultVal: string): string {
  const v = metaRecord(m)[key];
  return typeof v === 'string' ? v : defaultVal;
}

/**
 * 是否展示百科条目编辑侧栏里的「内置名称生成器」（马尔可夫 / 东方分层等，走 `POST /encyclopedia/generate-names`）。
 * 为 `false` 时仅隐藏 UI 与入口，**不删**相关 state、请求与后端路由；原因：与批量新建、条目侧「AI 一键生成」在起名场景上重叠，避免双入口。
 * **Android 对齐**：`android/.../encyclopedia/EncyclopediaUiConfig.kt` 中 `SHOW_BUILTIN_NAME_GENERATOR_UI`（当前 Android 无该 UI，将来接入须读该开关）。
 */
const SHOW_BUILTIN_ENCYCLOPEDIA_NAME_GENERATOR_UI = false;

// ═══════════════════════════════════════════════
//  深度字段 Schema 定义 — 每种类型 15+ 字段
// ═══════════════════════════════════════════════

interface FieldDef {
  key: string;
  label: string;
  type: 'text' | 'textarea' | 'number' | 'select' | 'tags' | 'objects' | 'object' | 'rich';
  options?: string[];
  placeholder?: string;
  fields?: FieldDef[];
  addLabel?: string;
}

const CATEGORIES = [
  '世界观总览',
  '人物',
  '地点',
  '势力',
  '事件',
  '物品',
  '技能/法术',
  '职业/等级',
  '概念术语',
  '时间线'
];

const ENTRY_TYPE_ICONS: Record<EncyclopediaSchemaEntryKey, UiIconName> = {
  world: 'world',
  character: 'person',
  location: 'world',
  faction: 'person',
  event: 'stories',
  item: 'archive',
  skill: 'sparkles',
  profession: 'person',
  concept: 'book',
  timeline: 'stories',
};

function entryTypeIcon(type: string | null | undefined): UiIconName {
  return type && Object.prototype.hasOwnProperty.call(ENTRY_TYPE_ICONS, type)
    ? ENTRY_TYPE_ICONS[type as EncyclopediaSchemaEntryKey]
    : 'document';
}

const SOURCE_FIELDS: FieldDef[] = [
  { key: 'source_url', label: '来源网址', type: 'text' },
  { key: 'source_page_title', label: '来源页面标题', type: 'text' },
  { key: 'source_retrieved_at', label: '获取时间', type: 'text' },
  { key: 'source_trust_level', label: '来源性质', type: 'select', options: ['official', 'wiki', 'community', 'manual', 'unverified', 'template'] },
  { key: 'verification_status', label: '核对标记', type: 'select', options: ['verified', 'fetched', 'pending', 'manual_unverified', 'template'] },
  { key: 'canon_scope', label: '设定写到哪', type: 'text' },
  { key: 'canon_conflicts', label: '冲突版本', type: 'tags' },
  { key: 'unknown_fields', label: '其他字段', type: 'tags' },
];

const CUSTOM_FIELDS: FieldDef = {
  key: 'custom_fields',
  label: '自定义字段',
  type: 'objects',
  addLabel: '+ 添加自定义字段',
  fields: [
    { key: 'key', label: '字段键', type: 'text', placeholder: '例如 mana_cost' },
    { key: 'label', label: '显示名', type: 'text', placeholder: '例如 魔力消耗' },
    { key: 'value', label: '字段值', type: 'textarea' },
    { key: 'source', label: '来源/备注', type: 'text' },
  ],
};

const SOURCE_AND_CUSTOM_FIELDS = [...SOURCE_FIELDS, CUSTOM_FIELDS];

const ENTRY_SCHEMAS: Record<string, { label: string; icon: string; categories: string[]; fields: FieldDef[] }> = {
  world: {
    label: '世界观总览', icon: 'W', categories: ['世界观总览'],
    fields: [
      { key: 'alias', label: '别称', type: 'tags' },
      { key: 'core_rules', label: '基础规则', type: 'rich' },
      { key: 'world_laws', label: '世界法则', type: 'textarea' },
      { key: 'era_background', label: '时代背景', type: 'textarea' },
      { key: 'civilization_stage', label: '文明阶段', type: 'text' },
      { key: 'tech_magic_level', label: '科技/魔法发展水平', type: 'textarea' },
      { key: 'world_map_structure', label: '世界地图结构', type: 'textarea' },
      { key: 'cosmology', label: '维度/位面/宇宙结构', type: 'textarea' },
      { key: 'timeline_summary', label: '历史年表', type: 'rich' },
      ...SOURCE_AND_CUSTOM_FIELDS,
    ],
  },
  character: {
    label: '人物', icon: 'C', categories: ['人物'],
    fields: [
      { key: 'alias', label: '别名/称号', type: 'tags' },
      { key: 'character_type', label: '类型', type: 'select', options: ['主角', '配角', '反派', 'NPC', '重要历史人物', '神明/高阶存在'] },
      { key: 'race', label: '种族', type: 'text' },
      { key: 'bloodline', label: '血脉', type: 'text' },
      { key: 'family', label: '家族', type: 'text' },
      { key: 'gender', label: '性别', type: 'select', options: ['男', '女', '未知', '非适用'] },
      { key: 'age', label: '年龄', type: 'text' },
      { key: 'faction', label: '所属势力', type: 'text' },
      { key: 'status_record', label: '状态记录', type: 'textarea' },
      { key: 'appearance', label: '外貌描述', type: 'textarea' },
      { key: 'personality', label: '性格特征', type: 'textarea' },
      { key: 'background', label: '背景故事', type: 'rich' },
      { key: 'growth_path', label: '人物成长线', type: 'rich' },
      { key: 'abilities', label: '能力/绝技', type: 'tags' },
      { key: 'equipment', label: '装备/法宝', type: 'tags' },
      { key: 'relationships', label: '关系网', type: 'objects', addLabel: '+ 添加关系', fields: [{ key: 'target', label: '对象', type: 'text' }, { key: 'relation', label: '关系', type: 'text' }, { key: 'note', label: '说明', type: 'text' }] },
      ...SOURCE_AND_CUSTOM_FIELDS,
    ],
  },
  location: {
    label: '地点', icon: 'L', categories: ['地点'],
    fields: [
      { key: 'alias', label: '别称', type: 'tags' },
      { key: 'location_type', label: '类型', type: 'select', options: ['世界地图', '国家', '城市', '村镇', '区域', '建筑', '遗迹', '副本/地下城', '禁地', '秘境', '星球', '星系', '位面', '传送点/交通节点'] },
      { key: 'region', label: '所属区域', type: 'text' },
      { key: 'controller', label: '控制势力', type: 'text' },
      { key: 'status', label: '状态', type: 'text' },
      { key: 'population', label: '人口/规模', type: 'text' },
      { key: 'landmarks', label: '地标', type: 'tags' },
      { key: 'resources', label: '资源', type: 'tags' },
      { key: 'travel_routes', label: '路线', type: 'objects', addLabel: '+ 添加路线', fields: [{ key: 'target', label: '通往', type: 'text' }, { key: 'method', label: '方式', type: 'text' }, { key: 'risk', label: '风险', type: 'text' }] },
      { key: 'hazards', label: '危险', type: 'tags' },
      { key: 'local_rules', label: '本地规则', type: 'textarea' },
      ...SOURCE_AND_CUSTOM_FIELDS,
    ],
  },
  faction: {
    label: '势力', icon: 'F', categories: ['势力'],
    fields: [
      { key: 'alias', label: '别称', type: 'tags' },
      { key: 'faction_type', label: '类型', type: 'select', options: ['国家', '王国/帝国', '宗门', '教会', '公会', '公司', '军队', '学院', '家族', '黑帮', '叛军', '秘密组织'] },
      { key: 'founder', label: '创始人', type: 'text' },
      { key: 'founded_year', label: '创立时间', type: 'text' },
      { key: 'leader', label: '现任领袖', type: 'text' },
      { key: 'headquarters', label: '总部', type: 'text' },
      { key: 'status', label: '状态', type: 'select', options: ['兴盛', '衰落', '隐世', '灭亡', '分裂', '流亡'] },
      { key: 'doctrine', label: '宗旨/教义', type: 'textarea' },
      { key: 'history', label: '历史沿革', type: 'rich' },
      { key: 'war_records', label: '势力战争记录', type: 'textarea' },
      { key: 'hierarchy', label: '组织层级', type: 'objects', addLabel: '+ 添加层级', fields: [{ key: 'name', label: '称号', type: 'text' }, { key: 'count', label: '人数', type: 'number' }, { key: 'requirements', label: '晋升条件', type: 'text' }, { key: 'privileges', label: '权限/职责', type: 'text' }] },
      { key: 'departments', label: '下属机构', type: 'objects', addLabel: '+ 添加机构', fields: [{ key: 'name', label: '名称', type: 'text' }, { key: 'head', label: '负责人', type: 'text' }, { key: 'responsibility', label: '职责', type: 'text' }] },
      { key: 'members', label: '重要成员', type: 'objects', addLabel: '+ 添加成员', fields: [{ key: 'name', label: '姓名', type: 'text' }, { key: 'title', label: '职位', type: 'text' }, { key: 'note', label: '备注', type: 'text' }] },
      { key: 'allies', label: '联盟', type: 'tags' },
      { key: 'enemies', label: '敌对', type: 'tags' },
      ...SOURCE_AND_CUSTOM_FIELDS,
    ],
  },
  event: {
    label: '事件', icon: 'E', categories: ['事件'],
    fields: [
      { key: 'event_type', label: '类型', type: 'select', options: ['大事件', '历史事件', '战争', '灾难', '政变', '探索事件', '失踪事件', '神话事件', '主线剧情事件', '支线事件'] },
      { key: 'time_label', label: '时间标记', type: 'text' },
      { key: 'location', label: '发生地点', type: 'text' },
      { key: 'participants', label: '参与方', type: 'tags' },
      { key: 'causes', label: '起因', type: 'rich' },
      { key: 'process', label: '过程', type: 'rich' },
      { key: 'result', label: '结果', type: 'rich' },
      { key: 'impact', label: '影响', type: 'textarea' },
      { key: 'world_status_changes', label: '世界状态变更记录', type: 'textarea' },
      ...SOURCE_AND_CUSTOM_FIELDS,
    ],
  },
  item: {
    label: '物品', icon: 'I', categories: ['物品'],
    fields: [
      { key: 'alias', label: '别称', type: 'tags' },
      { key: 'item_type', label: '类型', type: 'select', options: ['普通物品', '武器', '防具', '饰品', '消耗品', '材料', '货币', '遗物', '神器', '禁忌物', '载具'] },
      { key: 'rarity', label: '稀有度', type: 'text' },
      { key: 'creator', label: '创造者', type: 'text' },
      { key: 'owner_records', label: '持有者记录', type: 'textarea' },
      { key: 'source_location', label: '道具来源', type: 'text' },
      { key: 'appearance', label: '外观', type: 'textarea' },
      { key: 'effects', label: '效果', type: 'rich' },
      { key: 'limitations', label: '限制', type: 'textarea' },
      { key: 'cost', label: '代价/消耗', type: 'text' },
      { key: 'history', label: '历史', type: 'rich' },
      ...SOURCE_AND_CUSTOM_FIELDS,
    ],
  },
  skill: {
    label: '技能/法术', icon: 'S', categories: ['技能/法术'],
    fields: [
      { key: 'skill_type', label: '类型', type: 'select', options: ['技能', '法术', '天赋', '被动能力', '职业能力', '血脉能力', '神术', '禁术', '科技能力'] },
      { key: 'energy_system', label: '能量体系', type: 'text' },
      { key: 'prerequisites', label: '学习条件', type: 'tags' },
      { key: 'cost_rules', label: '消耗规则', type: 'textarea' },
      { key: 'counter_relations', label: '克制关系', type: 'textarea' },
      { key: 'effects', label: '效果', type: 'rich' },
      { key: 'side_effects', label: '副作用/风险', type: 'textarea' },
      { key: 'stages', label: '技能树/阶段', type: 'objects', addLabel: '+ 添加阶段', fields: [{ key: 'name', label: '阶段名称', type: 'text' }, { key: 'requirement', label: '前置要求', type: 'text' }, { key: 'description', label: '效果', type: 'textarea' }] },
      ...SOURCE_AND_CUSTOM_FIELDS,
    ],
  },
  profession: {
    label: '职业/等级', icon: 'P', categories: ['职业/等级'],
    fields: [
      { key: 'system_type', label: '体系类型', type: 'select', options: ['职业', '职阶', '等级体系', '境界体系'] },
      { key: 'promotion_conditions', label: '晋升条件', type: 'textarea' },
      { key: 'growth_path', label: '成长路线', type: 'rich' },
      { key: 'class_transfer_path', label: '转职路线', type: 'rich' },
      { key: 'limitations', label: '职业限制', type: 'textarea' },
      { key: 'skill_pool', label: '职业技能池', type: 'tags' },
      { key: 'equipment_limitations', label: '职业装备限制', type: 'tags' },
      ...SOURCE_AND_CUSTOM_FIELDS,
    ],
  },
  concept: {
    label: '概念术语', icon: 'N', categories: ['概念术语'],
    fields: [
      { key: 'alias', label: '别称', type: 'tags' },
      { key: 'concept_type', label: '概念类型', type: 'select', options: ['专有名词', '世界概念', '魔法概念', '科技概念', '宗教概念', '政治概念', '地理概念', '历史概念', '文化概念', '禁忌概念', '黑话/术语表'] },
      { key: 'definition', label: '定义', type: 'rich' },
      { key: 'scope', label: '这个词用在哪', type: 'textarea' },
      { key: 'mechanism', label: '机制', type: 'rich' },
      { key: 'limits', label: '限制', type: 'textarea' },
      { key: 'examples', label: '例子', type: 'tags' },
      { key: 'counterexamples', label: '反例', type: 'tags' },
      ...SOURCE_AND_CUSTOM_FIELDS,
    ],
  },
  timeline: {
    label: '时间线', icon: 'T', categories: ['时间线'],
    fields: [
      { key: 'calendar', label: '历法', type: 'text' },
      { key: 'time_label', label: '时间标记', type: 'text' },
      { key: 'time_order', label: '排序值', type: 'number' },
      { key: 'branch', label: '时间线分支', type: 'text' },
      { key: 'events', label: '事件节点', type: 'objects', addLabel: '+ 添加节点', fields: [{ key: 'time', label: '时间', type: 'text' }, { key: 'title', label: '事件', type: 'text' }, { key: 'note', label: '说明', type: 'textarea' }] },
      ...SOURCE_AND_CUSTOM_FIELDS,
    ],
  },
};

// ═══════════════════════════════════════════════
//  字段渲染组件
// ═══════════════════════════════════════════════

function reorderObjectsList<T>(list: T[], from: number, to: number): T[] {
  if (from === to || from < 0 || to < 0 || from >= list.length || to > list.length) return list;
  const n = [...list];
  const [it] = n.splice(from, 1);
  n.splice(to, 0, it);
  return n;
}

function FieldRenderer({ field, value, onChange }: { field: FieldDef; value: any; onChange: (v: any) => void }) {
  const dragFromRef = useRef<number | null>(null);
  const [dragOverIdx, setDragOverIdx] = useState<number | null>(null);
  const [draggingIdx, setDraggingIdx] = useState<number | null>(null);
  if (field.type === 'textarea' || field.type === 'rich') {
    return <textarea rows={field.type === 'rich' ? 6 : 3} value={value ?? ''} onChange={(e) => onChange(e.target.value)} placeholder={field.placeholder} style={{ width: '100%' }} />;
  }
  if (field.type === 'number') {
    return <input type="number" value={value ?? ''} onChange={(e) => onChange(e.target.value ? Number(e.target.value) : '')} style={{ width: '100%' }} />;
  }
  if (field.type === 'select' && field.options) {
    return (
      <select value={value ?? ''} onChange={(e) => onChange(e.target.value)} style={{ width: '100%' }}>
        <option value="">-</option>
        {field.options.map((o) => <option key={o} value={o}>{o}</option>)}
      </select>
    );
  }
  if (field.type === 'tags') {
    const arr = Array.isArray(value) ? value : (value ? String(value).split(',').map((s: string) => s.trim()).filter(Boolean) : []);
    return (
      <div>
        <div style={{ display: 'flex', flexWrap: 'wrap', gap: 4, marginBottom: 4 }}>
          {arr.map((t: string, i: number) => (
            <span key={i} style={{ background: 'var(--surface)', borderRadius: 4, padding: '2px 8px', fontSize: '0.78rem', display: 'flex', alignItems: 'center', gap: 4 }}>
              {t}<button type="button" style={{ background: 'none', border: 'none', cursor: 'pointer', padding: 0, color: 'var(--text-2)' }} onClick={() => onChange(arr.filter((_: any, j: number) => j !== i))}>×</button>
            </span>
          ))}
        </div>
        <input placeholder="输入后按回车" style={{ width: '100%' }}
          onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); const v = (e.target as HTMLInputElement).value.trim(); if (v) { onChange([...arr, v]); (e.target as HTMLInputElement).value = ''; } } }} />
      </div>
    );
  }
  if (field.type === 'object' && field.fields) {
    const obj = value ?? {};
    return (
      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 8 }}>
        {field.fields.map((sf) => (
          <div key={sf.key}>
            <div style={{ fontSize: '0.78rem', color: 'var(--text-2)', marginBottom: 2 }}>{sf.label}</div>
            <FieldRenderer field={sf} value={obj[sf.key]} onChange={(v) => onChange({ ...obj, [sf.key]: v })} />
          </div>
        ))}
      </div>
    );
  }
  if (field.type === 'objects' && field.fields) {
    const list = Array.isArray(value) ? value : [];
    return (
      <div>
        <div className="hint" style={{ fontSize: '0.7rem', marginBottom: 6 }}>拖动左侧「⠿」调整顺序，或使用 ▲▼</div>
        {list.map((item: any, idx: number) => (
          <div
            key={idx}
            style={{
              padding: 8,
              marginBottom: 6,
              background: 'var(--surface)',
              borderRadius: 'var(--radius-sm)',
              position: 'relative',
              borderTop: dragOverIdx === idx ? '2px solid var(--accent)' : undefined,
              opacity: draggingIdx === idx ? 0.55 : 1,
            }}
            onDragOver={(e) => {
              e.preventDefault();
              e.dataTransfer.dropEffect = 'move';
              setDragOverIdx(idx);
            }}
            onDragLeave={() => setDragOverIdx((v) => (v === idx ? null : v))}
            onDrop={(e) => {
              e.preventDefault();
              const raw = e.dataTransfer.getData('text/plain');
              const from = parseInt(raw, 10);
              setDragOverIdx(null);
              setDraggingIdx(null);
              dragFromRef.current = null;
              if (Number.isNaN(from)) return;
              onChange(reorderObjectsList(list, from, idx));
            }}
          >
            <span
              draggable
              title="拖动排序"
              onDragStart={(e) => {
                dragFromRef.current = idx;
                setDraggingIdx(idx);
                e.dataTransfer.setData('text/plain', String(idx));
                e.dataTransfer.effectAllowed = 'move';
              }}
              onDragEnd={() => {
                dragFromRef.current = null;
                setDraggingIdx(null);
                setDragOverIdx(null);
              }}
              style={{
                position: 'absolute',
                left: 4,
                top: 6,
                cursor: 'grab',
                userSelect: 'none',
                fontSize: '0.85rem',
                color: 'var(--text-2)',
                lineHeight: 1,
              }}
              aria-hidden
            >
              ⠿
            </span>
            <div style={{ position: 'absolute', top: 4, right: 4, display: 'flex', gap: 2 }}>
              <button type="button" style={{ fontSize: '0.7rem', padding: '2px 4px', background: 'none', border: 'none', cursor: 'pointer', color: 'var(--text-2)' }}
                onClick={() => { if (idx === 0) return; const n = [...list]; [n[idx-1], n[idx]] = [n[idx], n[idx-1]]; onChange(n); }}>▲</button>
              <button type="button" style={{ fontSize: '0.7rem', padding: '2px 4px', background: 'none', border: 'none', cursor: 'pointer', color: 'var(--text-2)' }}
                onClick={() => { if (idx === list.length-1) return; const n = [...list]; [n[idx], n[idx+1]] = [n[idx+1], n[idx]]; onChange(n); }}>▼</button>
              <button type="button" style={{ background: 'none', border: 'none', cursor: 'pointer', color: 'var(--accent-3)' }}
                onClick={() => onChange(list.filter((_: any, i: number) => i !== idx))}>✕</button>
            </div>
            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 6, paddingLeft: 22 }}>
              {field.fields!.map((sf) => (
                <div key={sf.key}>
                  <div style={{ fontSize: '0.72rem', color: 'var(--text-2)' }}>{sf.label}</div>
                  <FieldRenderer field={sf} value={item[sf.key]} onChange={(v) => { const n = [...list]; n[idx] = { ...n[idx], [sf.key]: v }; onChange(n); }} />
                </div>
              ))}
            </div>
          </div>
        ))}
        <button type="button" className="btn btn-ghost btn-sm" onClick={() => { const empty: any = {}; field.fields!.forEach((f) => { empty[f.key] = ''; }); onChange([...list, empty]); }}>
          {field.addLabel || '+ 添加'}
        </button>
      </div>
    );
  }
  return (
    <input type={field.type === 'text' ? 'text' : field.type} value={value ?? ''} onChange={(e) => onChange(e.target.value)} placeholder={field.placeholder} style={{ width: '100%' }} />
  );
}

// ═══════════════════════════════════════════════
//  条目详情渲染（只读模式）
// ═══════════════════════════════════════════════

function EntryDetailView({ entry, entryType }: { entry: any; entryType: string }) {
  const schema = ENTRY_SCHEMAS[entryType];
  if (!schema) return <div className="guide-inline">{entry.content}</div>;
  const meta = entry.meta_json || {};
  const cover = (entry.cover_image_path || '').trim();

  return (
    <div style={{ maxWidth: 800 }}>
      <h2 style={{ fontSize: '1.4rem', marginBottom: 4 }}>{entry.title}</h2>
      <div className="encyclopedia-entry-kind">
        <UiIcon name={entryTypeIcon(entryType)} />
        <span>{schema.label} {entry.tags ? `| ${entry.tags}` : ''}</span>
      </div>
      {cover ? (
        <div style={{ marginBottom: 16 }}>
          <img
            alt=""
            src={api.storageUrl(`/storage/${cover}`)}
            style={{ maxWidth: 220, width: '100%', aspectRatio: '2 / 3', objectFit: 'cover', borderRadius: 8, border: '1px solid var(--line)', display: 'block' }}
          />
        </div>
      ) : null}
      {entry.summary && <p style={{ fontSize: '0.95rem', color: 'var(--text-2)', fontStyle: 'italic', marginBottom: 16, padding: '8px 12px', background: 'var(--surface)', borderRadius: 'var(--radius-sm)' }}>{entry.summary}</p>}
      {entry.content && <div style={{ lineHeight: 1.8, marginBottom: 20, whiteSpace: 'pre-wrap' }}>{entry.content}</div>}

      {schema.fields.filter((f) => meta[f.key] !== undefined && meta[f.key] !== null && meta[f.key] !== '' && !(Array.isArray(meta[f.key]) && meta[f.key].length === 0)).map((field) => {
        const val = meta[field.key];
        return (
          <div key={field.key} style={{ marginBottom: 16 }}>
            <div style={{ fontSize: '0.82rem', fontWeight: 600, color: 'var(--accent)', marginBottom: 6, borderBottom: '1px solid var(--line)', paddingBottom: 4 }}>{field.label}</div>
            {field.type === 'textarea' || field.type === 'rich' ? (
              <div style={{ fontSize: '0.88rem', lineHeight: 1.7, whiteSpace: 'pre-wrap' }}>{val}</div>
            ) : field.type === 'tags' ? (
              <div style={{ display: 'flex', flexWrap: 'wrap', gap: 4 }}>{(Array.isArray(val) ? val : []).map((t: string, i: number) => <span key={i} className="tag">{t}</span>)}</div>
            ) : field.type === 'objects' && Array.isArray(val) ? (
              <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 8 }}>
                {val.map((item: any, i: number) => (
                  <div key={i} style={{ padding: 10, background: 'var(--surface)', borderRadius: 'var(--radius-sm)' }}>
                    {field.fields?.map((sf) => item[sf.key] ? <div key={sf.key} style={{ marginBottom: 4, fontSize: '0.85rem' }}><span style={{ fontSize: '0.72rem', color: 'var(--text-2)' }}>{sf.label}: </span>{typeof item[sf.key] === 'string' ? item[sf.key] : JSON.stringify(item[sf.key])}</div> : null)}
                  </div>
                ))}
              </div>
            ) : field.type === 'object' && typeof val === 'object' ? (
              <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 8 }}>
                {field.fields?.filter((sf) => val[sf.key]).map((sf) => (
                  <div key={sf.key} style={{ padding: 8, background: 'var(--surface)', borderRadius: 'var(--radius-sm)' }}>
                    <div style={{ fontSize: '0.72rem', color: 'var(--text-2)' }}>{sf.label}</div>
                    <div style={{ fontSize: '0.85rem' }}>{val[sf.key]}</div>
                  </div>
                ))}
              </div>
            ) : (
              <div style={{ fontSize: '0.88rem' }}>{typeof val === 'string' ? val : JSON.stringify(val)}</div>
            )}
          </div>
        );
      })}
    </div>
  );
}

// ═══════════════════════════════════════════════
//  主页面
// ═══════════════════════════════════════════════

function SimpleRelationGraph({
  nodes,
  edges,
  highlightId,
  pickAId,
  pickBId,
  footerHint,
  onSelectNode,
}: {
  nodes: { id: number; title: string; entry_type: string }[];
  edges: { source: number; target: number; relation_type?: string; label?: string }[];
  highlightId?: number | null;
  /** 本库图谱「两点建关系」模式：起点 / 终点高亮 */
  pickAId?: number | null;
  pickBId?: number | null;
  footerHint?: string;
  onSelectNode?: (id: number) => void;
}) {
  const w = 560;
  const h = 320;
  const cx = w / 2;
  const cy = h / 2;
  const R = Math.min(w, h) * 0.32;
  const pos = new Map<number, { x: number; y: number }>();
  const n = Math.max(nodes.length, 1);
  nodes.forEach((node, i) => {
    const ang = (2 * Math.PI * i) / n - Math.PI / 2;
    pos.set(node.id, { x: cx + R * Math.cos(ang), y: cy + R * Math.sin(ang) });
  });
  return (
    <div style={{ overflow: 'auto' }}>
      <svg width={w} height={h} style={{ display: 'block', margin: '0 auto' }}>
        {edges.map((e, idx) => {
          const a = pos.get(e.source);
          const b = pos.get(e.target);
          if (!a || !b) return null;
          return (
            <line
              key={`e-${idx}-${e.source}-${e.target}`}
              x1={a.x}
              y1={a.y}
              x2={b.x}
              y2={b.y}
              stroke="var(--line)"
              strokeWidth={1.25}
            />
          );
        })}
        {nodes.map((node) => {
          const p = pos.get(node.id);
          if (!p) return null;
          const active = highlightId === node.id;
          const pickA = pickAId === node.id;
          const pickB = pickBId === node.id;
          const fill = pickB
            ? 'rgba(34, 197, 94, 0.22)'
            : pickA
              ? 'rgba(249, 115, 22, 0.22)'
              : active
                ? 'rgba(99, 102, 241, 0.22)'
                : 'var(--surface)';
          return (
            <g
              key={node.id}
              style={{ cursor: onSelectNode ? 'pointer' : 'default' }}
              onClick={() => onSelectNode?.(node.id)}
            >
              <circle
                cx={p.x}
                cy={p.y}
                r={active || pickA || pickB ? 22 : 18}
                fill={fill}
                stroke="var(--accent)"
                strokeWidth={1}
              />
              <title>{`${node.title} (${node.entry_type})`}</title>
              <text x={p.x} y={p.y + 4} textAnchor="middle" fontSize="10" fill="var(--text)">
                {(node.title || '?').slice(0, 4)}
              </text>
            </g>
          );
        })}
      </svg>
      <p className="hint" style={{ marginTop: 8 }}>{footerHint ?? '环形示意布局；线表示关系。点击节点打开对应条目。'}</p>
    </div>
  );
}

type DetailTab = 'content' | 'relations' | 'graph' | 'timeline' | 'sediment';

export default function EncyclopediaPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const location = useLocation();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const { showToast } = useToast();
  const encRouteValue = searchParams.get('encId')?.trim() ?? '';
  const categoryRouteValue = searchParams.get('category')?.trim() ?? '';
  const entryRouteValue = searchParams.get('entryId')?.trim() ?? '';
  const parsedEncId = Number(encRouteValue);
  const parsedEntryId = Number(entryRouteValue);
  const selectedEncId = Number.isSafeInteger(parsedEncId) && parsedEncId > 0 ? parsedEncId : null;
  const categoryKey = Object.prototype.hasOwnProperty.call(ENTRY_SCHEMAS, categoryRouteValue)
    ? categoryRouteValue as EncyclopediaSchemaEntryKey
    : null;
  const category = categoryKey ? ENTRY_SCHEMAS[categoryKey].label : '';
  const selectedEntryId = Number.isSafeInteger(parsedEntryId) && parsedEntryId > 0 ? parsedEntryId : null;
  const allowEncyclopediaNavigationRef = useRef(false);
  const setEncyclopediaRoute = useCallback((
    encyclopediaId: number | null,
    nextCategory: EncyclopediaSchemaEntryKey | null = null,
    entryId: number | null = null,
    replace = false,
    parentKey?: string,
    allowDirtyNavigation = false,
  ) => {
    const next = new URLSearchParams(searchParams);
    if (encyclopediaId == null) {
      next.delete('encId');
      next.delete('category');
      next.delete('entryId');
    } else {
      next.set('encId', String(encyclopediaId));
      if (nextCategory == null) {
        next.delete('category');
        next.delete('entryId');
      } else {
        next.set('category', nextCategory);
        if (entryId == null) next.delete('entryId');
        else next.set('entryId', String(entryId));
      }
    }
    if (next.toString() !== searchParams.toString()) {
      if (allowDirtyNavigation) allowEncyclopediaNavigationRef.current = true;
      const historyState = !replace ? { state: { encyclopediaParentKey: parentKey ?? searchParams.toString() } } : {};
      setSearchParams(next, { replace, ...historyState });
    }
  }, [searchParams, setSearchParams]);
  const routeSnapshotRef = useRef({
    key: searchParams.toString(),
    encyclopediaId: selectedEncId,
    category: categoryKey,
    entryId: selectedEntryId,
  });
  routeSnapshotRef.current = {
    key: searchParams.toString(),
    encyclopediaId: selectedEncId,
    category: categoryKey,
    entryId: selectedEntryId,
  };
  const [searchText, setSearchText] = useState('');
  const deferredEntrySearch = useDeferredValue(searchText.trim());
  const [activeTab, setActiveTab] = useState<DetailTab>('content');
  const isCompactLayout = useMediaQuery(COMPACT_LAYOUT_QUERY);
  const encCol1 = useDragColumnWidth('mojing_ui_enc_col_1', 280, 160, 560);
  const encCol2 = useDragColumnWidth('mojing_ui_enc_col_2', 200, 140, 420);
  const encCol3 = useDragColumnWidth('mojing_ui_enc_col_3', 260, 160, 520);
  const [showEntryForm, setShowEntryForm] = useState(false);
  const [editingEntry, setEditingEntry] = useState<EncyclopediaEntryDraft>({});
  const [entryFormBaseline, setEntryFormBaseline] = useState('');
  const saveEntrySubmittingRef = useRef(false);
  const entryFormRevisionRef = useRef(0);
  const entryCoverSubmittingRef = useRef(false);
  const focusEntryAfterSaveRef = useRef(false);
  const [showEncForm, setShowEncForm] = useState(false);
  const [encyclopediaFormBaseline, setEncyclopediaFormBaseline] = useState('');
  const saveEncyclopediaSubmittingRef = useRef(false);
  const encyclopediaFormRevisionRef = useRef(0);
  const [editingEncId, setEditingEncId] = useState<number | null>(null);
  const [newEncName, setNewEncName] = useState('');
  const [newEncDesc, setNewEncDesc] = useState('');
  const [newEncGenreTags, setNewEncGenreTags] = useState('');
  const [newEncWorldPrompt, setNewEncWorldPrompt] = useState('');
  const [newEncGameplayMode, setNewEncGameplayMode] = useState('自由剧情');
  const [newEncAntiCheatPrompt, setNewEncAntiCheatPrompt] = useState('');
  const [showNameGen, setShowNameGen] = useState(false);
  const [genStyle, setGenStyle] = useState('western');
  const [genType, setGenType] = useState('character');
  const [genCount, setGenCount] = useState(5);
  const [genResults, setGenResults] = useState<any[]>([]);
  const [genLoading, setGenLoading] = useState(false);
  const [genRefineLlm, setGenRefineLlm] = useState(false);
  const [genRefineNote, setGenRefineNote] = useState<string | null>(null);
  const [genStyles, setGenStyles] = useState<{id:string;label:string}[]>([]);
  const [genTypes, setGenTypes] = useState<{id:string;label:string}[]>([]);
  const [showSourceImport, setShowSourceImport] = useState(false);
  const [showWorldInfoImport, setShowWorldInfoImport] = useState(false);
  const [worldInfoImportText, setWorldInfoImportText] = useState('');
  const [sourceImportText, setSourceImportText] = useState('');
  const [sourceImportDryRun, setSourceImportDryRun] = useState(true);
  const [aiBatchGenerateRunning, setAiBatchGenerateRunning] = useState(false);
  const aiBatchGenerateSubmittingRef = useRef(false);
  const batchGenerateStatusRef = useRef<HTMLDivElement>(null);
  const [showBatchGenerateDialog, setShowBatchGenerateDialog] = useState(false);
  const [batchGenerateCount, setBatchGenerateCount] = useState('10');
  const [batchGenerateHint, setBatchGenerateHint] = useState('');
  const [batchIgnoreWeakAnchor, setBatchIgnoreWeakAnchor] = useState(false);
  const [encSearch, setEncSearch] = useState('');
  const [encLibraryTool, setEncLibraryTool] = useState<null | 'timeline' | 'graph' | 'sediment'>(null);
  const [sedimentLocation, setSedimentLocation] = useState<{ encyclopediaId: number; location: SedimentReviewLocation } | null>(null);
  useEffect(() => { setSedimentLocation(null); }, [selectedEncId]);
  /** 本库关系图谱：两点建关系（对齐 Android 手动选端点，避免误连） */
  const [graphRelPickMode, setGraphRelPickMode] = useState(false);
  const [graphRelA, setGraphRelA] = useState<number | null>(null);
  const [graphRelB, setGraphRelB] = useState<number | null>(null);
  const [graphRelType, setGraphRelType] = useState('关联');
  const [graphRelLabel, setGraphRelLabel] = useState('');
  const [tlTitle, setTlTitle] = useState('');
  const [tlTimeLabel, setTlTimeLabel] = useState('');
  const [tlOrder, setTlOrder] = useState(0);
  const [sourceImportResult, setSourceImportResult] = useState<any>(null);
  const [aiCompleting, setAiCompleting] = useState(false);
  const ENC_ENTRY_SIDEBAR_LAYOUT_KEY = 'mojing_enc_entry_sidebar_layout';
  const [entryListLayout, setEntryListLayout] = useState<'list' | 'grid'>(() => {
    try {
      return readMoJingStorage(ENC_ENTRY_SIDEBAR_LAYOUT_KEY) === 'grid' ? 'grid' : 'list';
    } catch {
      return 'list';
    }
  });
  const [entryCoverHint, setEntryCoverHint] = useState('');

  const entryFormSnapshot = useMemo(
    () => encyclopediaEntryDraftSnapshot(editingEntry),
    [editingEntry],
  );
  const encyclopediaFormSnapshot = useMemo(() => encyclopediaLibraryDraftSnapshot({
    editingId: editingEncId,
    name: newEncName,
    description: newEncDesc,
    genreTags: newEncGenreTags,
    worldPrompt: newEncWorldPrompt,
    gameplayMode: newEncGameplayMode,
    antiCheatPrompt: newEncAntiCheatPrompt,
  }), [
    editingEncId,
    newEncAntiCheatPrompt,
    newEncDesc,
    newEncGameplayMode,
    newEncGenreTags,
    newEncName,
    newEncWorldPrompt,
  ]);
  const isEntryFormDirty = showEntryForm && Boolean(entryFormBaseline) && entryFormSnapshot !== entryFormBaseline;
  const isEncyclopediaFormDirty = showEncForm
    && Boolean(encyclopediaFormBaseline)
    && encyclopediaFormSnapshot !== encyclopediaFormBaseline;
  const isEncyclopediaDraftDirty = isEntryFormDirty || isEncyclopediaFormDirty;
  const isEncyclopediaSavePending = saveEntrySubmittingRef.current || saveEncyclopediaSubmittingRef.current;

  const encyclopediaNavigationBlocker = useBlocker(({ currentLocation, nextLocation }) => {
    if (allowEncyclopediaNavigationRef.current) {
      allowEncyclopediaNavigationRef.current = false;
      return false;
    }
    return (isEncyclopediaDraftDirty || isEncyclopediaSavePending) && (
      currentLocation.pathname !== nextLocation.pathname
      || currentLocation.search !== nextLocation.search
    );
  });

  useEffect(() => {
    if (encyclopediaNavigationBlocker.state !== 'blocked') return;
    if (saveEntrySubmittingRef.current || saveEncyclopediaSubmittingRef.current) {
      showToast('正在保存，请稍候', 'warn');
      encyclopediaNavigationBlocker.reset();
      return;
    }
    let active = true;
    void confirmModal(
      '百科修改尚未保存',
      '离开后会丢失当前修改。确认放弃修改并离开吗？',
    ).then((leave) => {
      if (!active || encyclopediaNavigationBlocker.state !== 'blocked') return;
      if (leave) {
        discardAllDraftForms();
        encyclopediaNavigationBlocker.proceed();
      } else {
        encyclopediaNavigationBlocker.reset();
      }
    });
    return () => { active = false; };
  }, [encyclopediaNavigationBlocker, showToast]);

  useBeforeUnload(useCallback((event) => {
    if (!isEncyclopediaDraftDirty && !isEncyclopediaSavePending) return;
    event.preventDefault();
    event.returnValue = '';
  }, [isEncyclopediaDraftDirty, isEncyclopediaSavePending]));

  useEffect(() => {
    try {
      writeMoJingStorage(ENC_ENTRY_SIDEBAR_LAYOUT_KEY, entryListLayout);
    } catch {
      /* ignore */
    }
  }, [entryListLayout]);

  useEffect(() => {
    setShowBatchGenerateDialog(false);
    setBatchGenerateCount('10');
    setBatchGenerateHint('');
    setBatchIgnoreWeakAnchor(false);
  }, [categoryKey, selectedEncId]);

  useEffect(() => {
    if (!aiBatchGenerateRunning) return undefined;
    const frame = window.requestAnimationFrame(() => batchGenerateStatusRef.current?.focus());
    return () => window.cancelAnimationFrame(frame);
  }, [aiBatchGenerateRunning]);

  useEffect(() => {
    if (encLibraryTool !== 'graph') {
      setGraphRelPickMode(false);
      setGraphRelA(null);
      setGraphRelB(null);
      setGraphRelType('关联');
      setGraphRelLabel('');
    }
  }, [encLibraryTool]);

  useEffect(() => {
    setBatchIgnoreWeakAnchor(false);
  }, [selectedEncId]);

  useEffect(() => {
    if (encRouteValue && selectedEncId == null) {
      setEncyclopediaRoute(null, null, null, true);
      return;
    }
    if (!selectedEncId && (categoryRouteValue || entryRouteValue)) {
      setEncyclopediaRoute(null, null, null, true);
      return;
    }
    if (categoryRouteValue && categoryKey == null) {
      setEncyclopediaRoute(selectedEncId, null, null, true);
      return;
    }
    if (!categoryKey && entryRouteValue) {
      setEncyclopediaRoute(selectedEncId, null, null, true);
      return;
    }
    if (entryRouteValue && selectedEntryId == null) {
      setEncyclopediaRoute(selectedEncId, categoryKey, null, true);
    }
  }, [
    categoryKey,
    categoryRouteValue,
    encRouteValue,
    entryRouteValue,
    selectedEncId,
    selectedEntryId,
    setEncyclopediaRoute,
  ]);

  const transientRouteKey = `${selectedEncId ?? ''}|${categoryKey ?? ''}|${selectedEntryId ?? ''}`;
  const previousTransientRouteKeyRef = useRef(transientRouteKey);
  useEffect(() => {
    if (previousTransientRouteKeyRef.current === transientRouteKey) return;
    previousTransientRouteKeyRef.current = transientRouteKey;
    entryFormRevisionRef.current += 1;
    setShowEntryForm(false);
    setEntryFormBaseline('');
    encyclopediaFormRevisionRef.current += 1;
    setShowEncForm(false);
    setEncyclopediaFormBaseline('');
    resetEncyclopediaForm();
    setEncLibraryTool(null);
    setActiveTab('content');
    setEntryCoverHint('');
  }, [transientRouteKey]);

  const encQuery = useQuery({ queryKey: ['encyclopedias'], queryFn: api.listEncyclopedias });

  useEffect(() => {
    if (
      selectedEncId == null
      || encQuery.data === undefined
      || encQuery.isFetching
      || encQuery.isError
    ) return;
    if (!encQuery.data.some((encyclopedia) => encyclopedia.id === selectedEncId)) {
      setEncyclopediaRoute(null, null, null, true);
    }
  }, [encQuery.data, encQuery.isError, encQuery.isFetching, selectedEncId, setEncyclopediaRoute]);

  useEffect(() => {
    let cancelled = false;
    Promise.allSettled([
      api.get('/encyclopedia/generate-styles'),
      api.get('/encyclopedia/generate-types'),
    ]).then((results) => {
      if (cancelled) return;
      const [stylesRes, typesRes] = results;
      if (stylesRes.status === 'fulfilled') setGenStyles(stylesRes.value as any);
      if (typesRes.status === 'fulfilled') setGenTypes(typesRes.value as any);
      if (stylesRes.status === 'rejected' || typesRes.status === 'rejected') {
        showToast('百科名称生成器的风格/类型列表加载失败，将使用内置默认值（Web）', 'warn');
      }
    });
    return () => { cancelled = true; };
  }, [showToast]);
  const entriesQuery = useQuery({
    queryKey: ['encyclopedia-entries', selectedEncId, categoryKey, deferredEntrySearch],
    queryFn: () => {
      const params: Record<string, any> = {};
      if (selectedEncId) params.encyclopedia_id = selectedEncId;
      if (categoryKey) params.entry_type = categoryKey;
      if (deferredEntrySearch) params.q = deferredEntrySearch;
      return api.listEncyclopediaEntries(params);
    },
    enabled: Boolean(selectedEncId),
  });
  const entryDetailQuery = useQuery({
    queryKey: ['entry-detail', selectedEntryId],
    queryFn: () => api.getEncyclopediaEntry(selectedEntryId!),
    enabled: Boolean(selectedEncId && categoryKey && selectedEntryId),
  });

  useEffect(() => {
    if (
      !focusEntryAfterSaveRef.current
      || showEntryForm
      || !selectedEntryId
      || !entryDetailQuery.data?.entry
    ) return;
    focusEntryAfterSaveRef.current = false;
    focusVisibleDraftReturnTarget('[data-encyclopedia-entry-edit-return]');
  }, [entryDetailQuery.data, selectedEntryId, showEntryForm]);

  useEffect(() => {
    const loadedEntry = entryDetailQuery.data?.entry;
    if (!loadedEntry || !selectedEncId || !categoryKey || !selectedEntryId) return;
    if (
      loadedEntry.id !== selectedEntryId
      || loadedEntry.encyclopedia_id !== selectedEncId
      || loadedEntry.entry_type !== categoryKey
    ) {
      setEncyclopediaRoute(selectedEncId, categoryKey, null, true);
    }
  }, [
    categoryKey,
    entryDetailQuery.data,
    selectedEncId,
    selectedEntryId,
    setEncyclopediaRoute,
  ]);

  const saveEntryMutation = useMutation({
    mutationFn: (request: {
      payload: EncyclopediaEntryDraft;
      routeKey: string;
      formRevision: number;
      encyclopediaId: number;
      category: EncyclopediaSchemaEntryKey | null;
    }) => api.post('/encyclopedia/entries', request.payload),
    onSuccess: (result: any, request) => {
      queryClient.invalidateQueries({ queryKey: ['encyclopedia-entries'] });
      queryClient.invalidateQueries({ queryKey: ['encyclopedia-sediment'] });
      if (selectedEntryId) queryClient.invalidateQueries({ queryKey: ['entry-detail'] });
      if (
        routeSnapshotRef.current.key === request.routeKey
        && entryFormRevisionRef.current === request.formRevision
      ) {
        setEntryFormBaseline('');
        setShowEntryForm(false);
        const savedCategory = entryCategoryKey(request.payload.entry_type) || request.category;
        if (result?.id && savedCategory) {
          focusEntryAfterSaveRef.current = true;
          setEncyclopediaRoute(request.encyclopediaId, savedCategory, Number(result.id), true, undefined, true);
        }
      }
      showToast('条目已保存', 'success');
    },
    onError: (e) => showToast(String(e), 'error'),
    onSettled: () => {
      saveEntrySubmittingRef.current = false;
    },
  });

  const saveEncyclopediaMutation = useMutation({
    mutationFn: (request: {
      payload: Parameters<typeof api.saveEncyclopedia>[0];
      routeKey: string;
      formRevision: number;
      editingId: number | null;
    }) => api.saveEncyclopedia(request.payload),
    onSuccess: (saved, request) => {
      queryClient.invalidateQueries({ queryKey: ['encyclopedias'] });
      if (
        routeSnapshotRef.current.key === request.routeKey
        && encyclopediaFormRevisionRef.current === request.formRevision
      ) {
        setEncyclopediaRoute(saved.id, null, null, true, undefined, true);
        discardEncyclopediaForm();
      }
      showToast(request.editingId ? '百科库已更新' : '百科库已创建', 'success');
    },
    onError: (e) => showToast(String(e), 'error'),
    onSettled: () => {
      saveEncyclopediaSubmittingRef.current = false;
    },
  });

  const generateEntryCoverMutation = useMutation({
    mutationFn: async (p: { hint: string; id?: number; draft: EncyclopediaEntryDraft; routeKey: string; formRevision: number }) => {
      const hint = p.hint ?? '';
      const targetId = p.id ?? p.draft.id;
      if (targetId) {
        return {
          kind: 'persisted' as const,
          entry: await api.generateEncyclopediaEntryCoverImage(targetId, { prompt_hint: hint, size: '1024x1792' }),
        };
      }
      const e = p.draft;
      if (!e?.title?.trim()) throw new Error('请先填写标题');
      const prev = await api.previewEncyclopediaEntryCoverImage({
        title: e.title.trim(),
        entry_type: e.entry_type || 'concept',
        summary: e.summary || '',
        prompt_hint: hint,
        size: '1024x1792',
      });
      const err = (prev.error || '').trim();
      if (err) throw new Error(err);
      const u = prev.urls?.[0]?.trim();
      if (!u) throw new Error('没拿到图片地址，看下设置里的生图 Key、地址和模型');
      const { cover_image_path } = await api.persistEncyclopediaEntryCoverFromUrl({ image_url: u });
      return { kind: 'draft' as const, cover_image_path };
    },
    onSuccess: (data, request) => {
      const sameForm = routeSnapshotRef.current.key === request.routeKey
        && entryFormRevisionRef.current === request.formRevision;
      if (data.kind === 'draft') {
        if (!sameForm) return;
        setEditingEntry((prev) => ({ ...prev, cover_image_path: data.cover_image_path }));
        showToast('封面已生成，请点击保存写入条目', 'success');
        return;
      }
      const updated = data.entry;
      queryClient.invalidateQueries({ queryKey: ['encyclopedia-entries'] });
      queryClient.invalidateQueries({ queryKey: ['entry-detail', updated.id] });
      if (sameForm && request.draft.id === updated.id) {
        setEditingEntry((prev) => prev.id === updated.id ? { ...prev, cover_image_path: updated.cover_image_path } : prev);
        setEntryFormBaseline((baseline) => mergeEncyclopediaEntryDraftSnapshot(
          baseline, { cover_image_path: updated.cover_image_path },
        ));
      }
      showToast('条目封面已生成', 'success');
    },
    onError: (e) => showToast(String(e), 'error'),
    onSettled: () => { entryCoverSubmittingRef.current = false; },
  });
  function generateEntryCover(hint: string, id?: number) {
    if (entryCoverSubmittingRef.current) return;
    entryCoverSubmittingRef.current = true;
    generateEntryCoverMutation.mutate({ hint, id, draft: { ...editingEntry },
      routeKey: routeSnapshotRef.current.key, formRevision: entryFormRevisionRef.current });
  }

  const deleteEntryMutation = useMutation({
    mutationFn: (request: { id: number; routeKey: string }) => api.delete(`/encyclopedia/entries/${request.id}`),
    onSuccess: (_result, request) => {
      queryClient.invalidateQueries({ queryKey: ['encyclopedia-entries'] });
      queryClient.invalidateQueries({ queryKey: ['encyclopedia-sediment'] });
      queryClient.removeQueries({ queryKey: ['entry-detail', request.id] });
      const current = routeSnapshotRef.current;
      if (current.key === request.routeKey && current.entryId === request.id) {
        setEncyclopediaRoute(current.encyclopediaId, current.category, null, true);
      }
      showToast('条目已删除', 'success');
    },
    onError: (e) => showToast(String(e), 'error'),
  });
  const sourceImportMutation = useMutation({
    mutationFn: () => {
      const sources = parseSourceImportText(sourceImportText);
      if (!sources.length) throw new Error('没有可导入来源');
      return api.importEncyclopediaSources(selectedEncId!, {
        sources,
        dry_run: sourceImportDryRun,
        overwrite_existing: !sourceImportDryRun,
        max_extract_chars: 6000,
      });
    },
    onSuccess: (result) => {
      setSourceImportResult(result);
      queryClient.invalidateQueries({ queryKey: ['encyclopedia-entries'] });
      queryClient.invalidateQueries({ queryKey: ['encyclopedias'] });
      const count = result?.imported_count ?? result?.items?.length ?? 0;
      showToast(sourceImportDryRun ? `来源预览：${count} 项` : `已导入 ${count} 条`, 'success');
    },
    onError: (e) => showToast(String(e), 'error'),
  });
  const worldInfoImportMutation = useMutation({
    mutationFn: () => api.importEncyclopediaWorldInfo(selectedEncId!, worldInfoImportText),
    onSuccess: (res) => {
      queryClient.invalidateQueries({ queryKey: ['encyclopedia-entries'] });
      queryClient.invalidateQueries({ queryKey: ['encyclopedias'] });
      showToast(`WorldInfo 已导入 ${res.created} 条百科条目`, 'success');
      setWorldInfoImportText('');
    },
    onError: (e) => showToast(String(e), 'error'),
  });

  const encTimelineQuery = useQuery({
    queryKey: ['encyclopedia-timeline', selectedEncId],
    queryFn: () => api.listEncyclopediaTimeline(selectedEncId!),
    enabled: Boolean(selectedEncId && encLibraryTool === 'timeline'),
  });
  const encGraphQuery = useQuery({
    queryKey: ['encyclopedia-relation-graph', selectedEncId],
    queryFn: () => api.getEncyclopediaRelationGraph(selectedEncId!),
    enabled: Boolean(selectedEncId && encLibraryTool === 'graph'),
  });


  const addTimelineMutation = useMutation({
    mutationFn: (encyclopedia_id: number) =>
      api.post('/encyclopedia/timeline', {
        encyclopedia_id,
        title: tlTitle.trim(),
        time_label: tlTimeLabel.trim(),
        time_order: Number(tlOrder) || 0,
        entry_type: 'event',
        summary: '',
        content: '',
        tags: '',
        timeline_branch: 'main',
      }),
    onSuccess: () => {
      setTlTitle('');
      setTlTimeLabel('');
      setTlOrder(0);
      queryClient.invalidateQueries({ queryKey: ['encyclopedia-timeline'] });
      showToast('已添加时间线事件', 'success');
    },
    onError: (e) => showToast(String(e), 'error'),
  });

  const createEncRelationMutation = useMutation({
    mutationFn: () => {
      if (!selectedEncId || graphRelA == null || graphRelB == null) throw new Error('请在图谱上选择两个不同条目作为起点与终点');
      if (graphRelA === graphRelB) throw new Error('起点与终点不能为同一条目');
      return api.post('/encyclopedia/relations', {
        encyclopedia_id: selectedEncId,
        from_entry_id: graphRelA,
        to_entry_id: graphRelB,
        relation_type: graphRelType.trim() || '关联',
        label: graphRelLabel.trim(),
      });
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['encyclopedia-relation-graph', selectedEncId] });
      setGraphRelA(null);
      setGraphRelB(null);
      showToast('关系已创建', 'success');
    },
    onError: (e) => showToast(String(e), 'error'),
  });

  const deleteTimelineMutation = useMutation({
    mutationFn: (id: number) => api.delete(`/encyclopedia/timeline/${id}`),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['encyclopedia-timeline'] });
      showToast('已删除', 'success');
    },
    onError: (e) => showToast(String(e), 'error'),
  });

  const encyclopedias: any[] = useMemo(() => {
    const data = encQuery.data ?? [];
    const query = encSearch.trim().toLocaleLowerCase();
    if (!query) return data;
    return data.filter((e: any) => String(e.name ?? '').toLocaleLowerCase().includes(query));
  }, [encQuery.data, encSearch]);
  const entries: EncyclopediaEntry[] = useMemo(() => {
    const raw = (entriesQuery.data ?? []) as EncyclopediaEntry[];
    return raw.map((e) => ({ ...e, cover_image_path: e.cover_image_path ?? '' }));
  }, [entriesQuery.data]);
  const entryDetail: any = entryDetailQuery.data;
  const validatedEntry = useMemo(() => {
    const candidate = entryDetail?.entry;
    return candidate
      && candidate.id === selectedEntryId
      && candidate.encyclopedia_id === selectedEncId
      && candidate.entry_type === categoryKey
      ? candidate
      : null;
  }, [categoryKey, entryDetail, selectedEncId, selectedEntryId]);
  const entry = validatedEntry;
  const relations: any[] = entry ? (entryDetail?.relations ?? []) : [];

  const entryGraphQuery = useQuery({
    queryKey: ['encyclopedia-entry-graph', selectedEntryId, activeTab],
    queryFn: () => api.getEncyclopediaEntryGraph(selectedEntryId!, 2),
    enabled: Boolean(selectedEntryId && activeTab === 'graph' && entry),
  });
  const entryTimelineQuery = useQuery({
    queryKey: ['encyclopedia-entry-timeline', entry?.encyclopedia_id, activeTab],
    queryFn: () => api.listEncyclopediaTimeline(entry!.encyclopedia_id),
    enabled: Boolean(entry && activeTab === 'timeline' && entry.encyclopedia_id),
  });
  const selectedEnc = (encQuery.data ?? []).find((e) => e.id === selectedEncId);
  /** 简介 / 体裁 / 世界补充皆空时，批量生成更依赖「额外提示」与已有条目 digest */
  const encyclopediaAnchorWeak = useMemo(() => {
    if (!selectedEnc) return false;
    const d = String(selectedEnc.description ?? '').trim();
    const g = String(selectedEnc.genre_tags ?? '').trim();
    const w = String(selectedEnc.world_prompt ?? '').trim();
    return !d && !g && !w;
  }, [selectedEnc]);
  const entryTypeDef = entry ? (Object.entries(ENTRY_SCHEMAS).find(([k]) => k === entry.entry_type)?.[1] ?? null) : null;

  const groupedEntries = useMemo(() => {
    const groups: Record<string, any[]> = {};
    entries.forEach((e: any) => {
      const t = categoryKey || e.entry_type || 'other';
      if (!groups[t]) groups[t] = [];
      groups[t].push(e);
    });
    return groups;
  }, [entries, categoryKey]);

  function openBatchGenerateDialog() {
    if (!selectedEncId || !category || !categoryKey) {
      showToast('请先选择一个百科分类', 'warn');
      return;
    }
    if (aiBatchGenerateSubmittingRef.current || aiBatchGenerateRunning) return;
    setShowBatchGenerateDialog(true);
  }

  async function submitBatchGenerate(count: number, contextHint: string) {
    if (aiBatchGenerateSubmittingRef.current || aiBatchGenerateRunning) return;
    if (!selectedEncId || !category || !categoryKey) {
      setShowBatchGenerateDialog(false);
      showToast('当前百科分类已失效，请重新选择', 'warn');
      return;
    }

    const encyclopediaId = selectedEncId;
    const categoryLabel = category;
    const entryType = categoryKey;
    aiBatchGenerateSubmittingRef.current = true;
    setShowBatchGenerateDialog(false);
    setAiBatchGenerateRunning(true);
    showToast(`正在生成 ${count} 条${categoryLabel}资料…`, 'info');
    try {
      const queryOverride = `${categoryLabel} ${contextHint}`.trim();
      const data = await api.post(`/encyclopedia/${encyclopediaId}/batch-generate`, {
        entry_type: entryType,
        count,
        context_hint: contextHint,
        sequential: true,
        query_override: queryOverride || undefined,
        digest_token_budget: 900,
      }) as { created?: number; warnings?: string[]; error?: string };
      const created = data.created ?? 0;
      if (created === 0 && data.error) throw new Error(data.error);
      const warning = Array.isArray(data.warnings) && data.warnings.length
        ? `；${data.warnings[0]}`
        : '';
      showToast(`批量生成完成：新增 ${created} 条${warning}`, created > 0 ? 'success' : 'warn');
      await queryClient.invalidateQueries({ queryKey: ['encyclopedia-entries', encyclopediaId] });
      await queryClient.invalidateQueries({ queryKey: ['encyclopedias'] });
      if (created > 0) {
        setBatchGenerateCount('10');
        setBatchGenerateHint('');
        setBatchIgnoreWeakAnchor(false);
      }
    } catch (error) {
      showToast(error instanceof Error ? error.message : '批量生成失败，请稍后重试', 'error');
    } finally {
      aiBatchGenerateSubmittingRef.current = false;
      setAiBatchGenerateRunning(false);
    }
  }

  function buildMetaForType(typeKey: EncyclopediaSchemaEntryKey | string, currentMeta: EncyclopediaMetaJson = {}): EncyclopediaMetaJson {
    const schema = ENTRY_SCHEMAS[typeKey];
    const meta: Record<string, unknown> = { ...currentMeta };
    schema?.fields.forEach((field) => {
      if (meta[field.key] !== undefined) return;
      if (field.type === 'tags' || field.type === 'objects') meta[field.key] = [];
      else if (field.type === 'object') meta[field.key] = {};
      else meta[field.key] = '';
    });
    return meta as EncyclopediaMetaJson;
  }

  function resetEncyclopediaForm() {
    setEditingEncId(null);
    setNewEncName('');
    setNewEncDesc('');
    setNewEncGenreTags('');
    setNewEncWorldPrompt('');
    setNewEncGameplayMode('自由剧情');
    setNewEncAntiCheatPrompt('');
  }

  function discardEntryForm() {
    entryFormRevisionRef.current += 1;
    setShowEntryForm(false);
    setEntryFormBaseline('');
    setEntryCoverHint('');
  }

  function discardEncyclopediaForm() {
    encyclopediaFormRevisionRef.current += 1;
    setShowEncForm(false);
    setEncyclopediaFormBaseline('');
    resetEncyclopediaForm();
  }

  function discardAllDraftForms() {
    discardEntryForm();
    discardEncyclopediaForm();
  }

  function focusVisibleDraftReturnTarget(selector: string) {
    window.requestAnimationFrame(() => {
      const target = Array.from(document.querySelectorAll<HTMLButtonElement>(selector)).find((button) => {
        const rect = button.getBoundingClientRect();
        return !button.disabled
          && rect.width > 0
          && rect.height > 0
          && rect.right > 0
          && rect.left < window.innerWidth
          && rect.bottom > 0
          && rect.top < window.innerHeight;
      });
      target?.focus();
    });
  }

  async function confirmDraftReplacement(message: string): Promise<boolean> {
    if (saveEntrySubmittingRef.current || saveEncyclopediaSubmittingRef.current) {
      showToast('正在保存，请稍候', 'warn');
      return false;
    }
    if (!isEncyclopediaDraftDirty) return true;
    return confirmModal('百科修改尚未保存', message);
  }

  async function requestCloseEntryForm() {
    if (saveEntrySubmittingRef.current) {
      showToast('条目正在保存，请稍候', 'warn');
      return;
    }
    if (isEntryFormDirty && !await confirmModal(
      '条目修改尚未保存',
      '关闭后会丢失当前修改。确认放弃修改吗？',
    )) return;
    const returnSelector = editingEntry.id
      ? '[data-encyclopedia-entry-edit-return]'
      : '[data-encyclopedia-entry-new-return]';
    discardEntryForm();
    focusVisibleDraftReturnTarget(returnSelector);
  }

  async function requestCloseEncyclopediaForm() {
    if (saveEncyclopediaSubmittingRef.current) {
      showToast('百科库正在保存，请稍候', 'warn');
      return;
    }
    if (isEncyclopediaFormDirty && !await confirmModal(
      '百科库修改尚未保存',
      '关闭后会丢失当前修改。确认放弃修改吗？',
    )) return;
    const returnSelector = editingEncId == null
      ? '[data-encyclopedia-library-create-return]'
      : `[data-encyclopedia-library-edit-return="${editingEncId}"]`;
    discardEncyclopediaForm();
    focusVisibleDraftReturnTarget(returnSelector);
  }

  async function openCreateEncyclopediaForm() {
    if (showEncForm && editingEncId == null) {
      await requestCloseEncyclopediaForm();
      return;
    }
    if (!await confirmDraftReplacement('新建百科库会丢失当前修改。确认继续吗？')) return;
    discardAllDraftForms();
    const draft = {
      editingId: null,
      name: '',
      description: '',
      genreTags: '',
      worldPrompt: '',
      gameplayMode: '自由剧情',
      antiCheatPrompt: '',
    };
    setEncyclopediaFormBaseline(encyclopediaLibraryDraftSnapshot(draft));
    setShowEncForm(true);
  }

  async function openEditEncyclopediaForm(enc: WorldEncyclopedia) {
    if (showEncForm && editingEncId === enc.id) return;
    if (!await confirmDraftReplacement('打开其他百科库会丢失当前修改。确认继续吗？')) return;
    discardAllDraftForms();
    setEditingEncId(enc.id);
    setNewEncName(enc.name || '');
    setNewEncDesc(enc.description || '');
    setNewEncGenreTags(enc.genre_tags || '');
    setNewEncWorldPrompt(enc.world_prompt || '');
    setNewEncGameplayMode(enc.gameplay_mode || '自由剧情');
    setNewEncAntiCheatPrompt(enc.anti_cheat_prompt || '');
    setEncyclopediaFormBaseline(encyclopediaLibraryDraftSnapshot({
      editingId: enc.id,
      name: enc.name || '',
      description: enc.description || '',
      genreTags: enc.genre_tags || '',
      worldPrompt: enc.world_prompt || '',
      gameplayMode: enc.gameplay_mode || '自由剧情',
      antiCheatPrompt: enc.anti_cheat_prompt || '',
    }));
    setShowEncForm(true);
  }

  function saveEncyclopediaForm() {
    if (saveEncyclopediaSubmittingRef.current) return;
    if (!newEncName.trim()) {
      showToast('请填写百科库名称（必填）', 'warn');
      return;
    }
    saveEncyclopediaSubmittingRef.current = true;
    saveEncyclopediaMutation.mutate({
      payload: {
        id: editingEncId ?? undefined,
        name: newEncName.trim(),
        description: newEncDesc.trim(),
        genre_tags: newEncGenreTags.trim(),
        world_prompt: newEncWorldPrompt.trim(),
        gameplay_mode: newEncGameplayMode.trim() || '自由剧情',
        anti_cheat_prompt: newEncAntiCheatPrompt.trim(),
      },
      routeKey: routeSnapshotRef.current.key,
      formRevision: encyclopediaFormRevisionRef.current,
      editingId: editingEncId,
    });
  }

  async function handleNewEntry() {
    if (!selectedEncId) return;
    if (showEntryForm && !editingEntry.id) return;
    if (!await confirmDraftReplacement('新建条目会丢失当前修改。确认继续吗？')) return;
    discardAllDraftForms();
    setEncLibraryTool(null);
    const typeKey = categoryKey || 'concept';
    const meta = buildMetaForType(typeKey);
    const draft = { encyclopedia_id: selectedEncId, title: '', entry_type: typeKey, summary: '', content: '', cover_image_path: '', tags: '', meta_json: meta };
    setEditingEntry(draft);
    setEntryFormBaseline(encyclopediaEntryDraftSnapshot(draft));
    setShowEntryForm(true);
  }

  function entryCategoryKey(value: string | undefined): EncyclopediaSchemaEntryKey | null {
    return value && Object.prototype.hasOwnProperty.call(ENTRY_SCHEMAS, value)
      ? value as EncyclopediaSchemaEntryKey
      : null;
  }

  async function openEntry(entryId: number, entryType?: string) {
    if (!selectedEncId) return;
    const nextCategory = entryCategoryKey(entryType) || categoryKey;
    if (!nextCategory) return;
    if (!await confirmDraftReplacement('打开其他条目会丢失当前修改。确认继续吗？')) return;
    discardAllDraftForms();
    setEncLibraryTool(null);
    setActiveTab('content');
    const parentKey = routeSnapshotRef.current.key;
    setEncyclopediaRoute(selectedEncId, nextCategory, entryId, false, parentKey, true);
  }

  function returnToEncyclopediaRoute(
    encyclopediaId: number | null,
    nextCategory: EncyclopediaSchemaEntryKey | null = null,
  ) {
    const target = new URLSearchParams(searchParams);
    if (encyclopediaId == null) {
      target.delete('encId');
      target.delete('category');
      target.delete('entryId');
    } else {
      target.set('encId', String(encyclopediaId));
      if (nextCategory == null) {
        target.delete('category');
        target.delete('entryId');
      } else {
        target.set('category', nextCategory);
        target.delete('entryId');
      }
    }
    const parentKey = (location.state as { encyclopediaParentKey?: string } | null)?.encyclopediaParentKey;
    if (parentKey === target.toString()) navigate(-1);
    else setEncyclopediaRoute(encyclopediaId, nextCategory, null, true);
  }

  async function openLibraryTool(tool: 'timeline' | 'graph' | 'sediment') {
    if (!await confirmDraftReplacement('打开百科工具会丢失当前修改。确认继续吗？')) return;
    discardAllDraftForms();
    setEncLibraryTool(tool);
  }

  async function handleEditEntry(entry: EncyclopediaEntry) {
    if (showEntryForm && editingEntry.id === entry.id) return;
    if (!await confirmDraftReplacement('编辑其他条目会丢失当前修改。确认继续吗？')) return;
    discardAllDraftForms();
    setEncLibraryTool(null);
    const meta = entry.meta_json || {};
    const typeKey = entry.entry_type;
    const draft = { ...entry, meta_json: buildMetaForType(typeKey, meta) } as EncyclopediaEntryDraft;
    setEditingEntry(draft);
    setEntryFormBaseline(encyclopediaEntryDraftSnapshot(draft));
    setShowEntryForm(true);
  }

  function parseSourceImportText(text: string) {
    return text
      .split('\n')
      .map((line) => line.trim())
      .filter(Boolean)
      .map((line) => {
        const parts = line.split('|').map((part) => part.trim()).filter(Boolean);
        if (parts.length >= 2) {
          return { title: parts[0], url: parts[1], entry_type: parts[2] || 'concept', tags: ['来源导入'], trust_level: 'wiki' };
        }
        if (parts.length === 1) {
          return { title: parts[0], url: '', entry_type: 'concept', tags: ['来源导入'], trust_level: 'wiki' };
        }
        return null;
      })
      .filter(Boolean) as Array<{ title: string; url: string; entry_type: string; tags: string[]; trust_level: string }>;
  }

  let mobileLevel = 1;
  if (selectedEncId) mobileLevel = 2;
  if (category) mobileLevel = 3;
  if (selectedEntryId || showEntryForm || encLibraryTool) mobileLevel = 4;

  const encLayoutStyle =
    !isCompactLayout
      ? ({
          ['--enc-1st-w' as string]: `${encCol1.width}px`,
          ['--enc-2nd-w' as string]: `${encCol2.width}px`,
          ['--enc-3rd-w' as string]: `${encCol3.width}px`,
        } as CSSProperties)
      : undefined;

  return (
    <div 
      className={`page-layout with-secondary-nav encyclopedia-layout ${(!showEntryForm && !entry && !encLibraryTool) ? 'is-empty-main' : ''}`}
      data-mobile-level={mobileLevel}
      style={encLayoutStyle}
    >
      {/* ===== 第一级: 百科库列表（始终显示） ===== */}
      <aside className="secondary-sidebar enc-1st">
        <div className="secondary-sidebar-header">
          <div className="creation-workspace-title">
            <CreationHomeLink compact />
            <h3>百科库</h3>
          </div>
          <button type="button" className="btn-icon" data-encyclopedia-library-create-return onClick={openCreateEncyclopediaForm} title="新建百科库" aria-label="新建百科库"><UiIcon name="plus" /></button>
        </div>
        {showEncForm && (
          <div className="encyclopedia-library-form">
            <div className="button-row" style={{ justifyContent: 'space-between' }}>
              <strong>{editingEncId ? '编辑百科库' : '新建百科库'}</strong>
              {isEncyclopediaFormDirty && <span className="pill pill-accent" role="status">未保存</span>}
            </div>
            <label><span>名称</span><input value={newEncName} onChange={(e) => setNewEncName(e.target.value)} placeholder="例如：雾都纪事" /></label>
            <label><span>简介</span><input value={newEncDesc} onChange={(e) => setNewEncDesc(e.target.value)} placeholder="用一句话说明这个世界" /></label>
            <label><span>体裁标签</span><input value={newEncGenreTags} onChange={(e) => setNewEncGenreTags(e.target.value)} placeholder="例如：黑暗奇幻、群像" /></label>
            <label><span>故事方式</span><input value={newEncGameplayMode} onChange={(e) => setNewEncGameplayMode(e.target.value)} placeholder="例如：自由剧情" /></label>
            <label><span>世界补充设定</span><textarea value={newEncWorldPrompt} onChange={(e) => setNewEncWorldPrompt(e.target.value)} placeholder="会作为对话中的世界规则" rows={4} /></label>
            <label><span>玩法边界</span><textarea value={newEncAntiCheatPrompt} onChange={(e) => setNewEncAntiCheatPrompt(e.target.value)} placeholder="约束角色能力与世界规则" rows={3} /></label>
            <div style={{ display: 'flex', gap: 6 }}>
              <button type="button" className="btn btn-primary btn-sm" disabled={saveEncyclopediaMutation.isPending} onClick={saveEncyclopediaForm}>
                {saveEncyclopediaMutation.isPending ? '保存中…' : editingEncId ? '保存' : '创建'}
              </button>
              <button type="button" className="btn btn-ghost btn-sm" disabled={saveEncyclopediaMutation.isPending} onClick={() => { void requestCloseEncyclopediaForm(); }}>
                取消
              </button>
            </div>
          </div>
        )}
        <div className="secondary-sidebar-search">
          <input value={encSearch} onChange={(e) => setEncSearch(e.target.value)} placeholder="按名称搜索百科库…" aria-label="按名称搜索百科库" />
        </div>
        <div className="secondary-sidebar-list">
          {encQuery.isPending && <div className="secondary-sidebar-empty"><UiIcon name="loading" className="ui-icon-loading" /><p>正在加载百科库…</p></div>}
          {encQuery.isError && (
            <InlineQueryError message="百科库加载失败" error={encQuery.error} retrying={encQuery.isFetching} onRetry={() => { void encQuery.refetch(); }} />
          )}
          {encyclopedias.map((enc: any) => (
            <div key={enc.id} className={`encyclopedia-library-row ${selectedEncId === enc.id ? 'active' : ''}`}>
              <button type="button" className="encyclopedia-library-select" onClick={() => setEncyclopediaRoute(enc.id)}>
                <span className="secondary-nav-avatar encyclopedia-library-avatar" style={{ background: enc.is_official ? 'var(--accent)' : 'var(--text-3)' }}><UiIcon name="world" /></span>
                <span className="secondary-nav-text">
                  <span className="secondary-nav-name">{enc.name}</span>
                  <span className="secondary-nav-sub">{enc.entry_count} 条资料</span>
                </span>
              </button>
              <button type="button" className="btn-icon encyclopedia-library-edit" data-encyclopedia-library-edit-return={enc.id} title={`编辑${enc.name}`} aria-label={`编辑${enc.name}`} onClick={() => openEditEncyclopediaForm(enc)}><UiIcon name="edit" /></button>
            </div>
          ))}
          {!encQuery.isPending && !encQuery.isError && encyclopedias.length === 0 && (
            <div className="secondary-sidebar-empty">
              <UiIcon name={encSearch.trim() ? 'search' : 'world'} />
              <p>{encSearch.trim() ? '没有匹配的百科库' : '还没有百科库'}</p>
              {encSearch.trim() ? (
                <button type="button" className="btn btn-ghost btn-sm" onClick={() => setEncSearch('')}>清空搜索</button>
              ) : (
                <button type="button" className="btn btn-primary btn-sm" data-encyclopedia-library-create-return onClick={openCreateEncyclopediaForm}><UiIcon name="plus" />创建第一个百科库</button>
              )}
            </div>
          )}
        </div>
      </aside>
      {!isCompactLayout && (
        <div
          className="enc-column-resizer"
          role="separator"
          aria-orientation="vertical"
          aria-label="调整百科库列表宽度"
          title="拖拽调整宽度，双击恢复默认"
          onMouseDown={(e) => encCol1.beginDrag(e, encCol1.width)}
          onDoubleClick={(e) => {
            e.preventDefault();
            encCol1.reset();
          }}
        />
      )}

      {/* ===== 第二级: 分类导航 + 工具入口（选中百科库后显示） ===== */}
      {selectedEncId && (
        <aside className="secondary-sidebar enc-2nd">
          <div className="secondary-sidebar-header">
            <div style={{ display: 'flex', alignItems: 'center', gap: '8px', overflow: 'hidden' }}>
              <button type="button" className="mobile-only-btn" onClick={() => returnToEncyclopediaRoute(null)} aria-label="返回百科库列表"><UiIcon name="back" /></button>
              <h3 style={{ fontSize: '0.82rem', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>分类</h3>
            </div>
            <button type="button" className="btn-icon" data-encyclopedia-entry-new-return onClick={handleNewEntry} title="新建条目" aria-label="新建条目"><UiIcon name="plus" /></button>
          </div>
          <div style={{ padding: '4px 8px', borderBottom: '1px solid var(--line)' }}>
            {CATEGORIES.map((cat) => {
              const schemaEntry = Object.entries(ENTRY_SCHEMAS).find(([, schema]) => schema.label === cat);
              const typeKey = schemaEntry?.[0] as EncyclopediaSchemaEntryKey | undefined;
              const schema = schemaEntry?.[1];
              return (
                <button type="button" key={cat} className={`secondary-nav-item ${category === cat ? 'active' : ''}`} style={{ padding: '6px 12px', fontSize: '0.82rem' }} onClick={() => typeKey && setEncyclopediaRoute(selectedEncId, typeKey)}>
                  <span className="encyclopedia-category-icon"><UiIcon name={entryTypeIcon(typeKey)} /></span>
                  <span>{schema?.label || cat}</span>
                </button>
              );
            })}
          </div>
          <div style={{ padding: '8px', borderTop: '1px solid var(--line)', marginTop: 'auto' }}>
            <button type="button" className="btn btn-ghost btn-sm" style={{ width: '100%', marginBottom: 4 }} onClick={() => openLibraryTool('timeline')}>
              <UiIcon name="stories" />本库时间线
            </button>
            <button type="button" className="btn btn-ghost btn-sm" style={{ width: '100%', marginBottom: 4 }} onClick={() => openLibraryTool('graph')}>
              <UiIcon name="branch" />关系图谱
            </button>
            <button type="button" className="btn btn-ghost btn-sm" style={{ width: '100%', marginBottom: 4 }} onClick={() => openLibraryTool('sediment')}>
              <UiIcon name="archive" />本库沉淀
            </button>
            <button type="button" className="btn btn-ghost btn-sm" style={{ width: '100%', marginBottom: 4 }} onClick={() => setShowSourceImport((v) => !v)}>
              <UiIcon name="import" />{showSourceImport ? '关闭批量导入' : '批量导入'}
            </button>
            <button type="button" className="btn btn-ghost btn-sm" style={{ width: '100%', marginBottom: 4 }} onClick={() => setShowWorldInfoImport((v) => !v)}>
              <UiIcon name="book" />{showWorldInfoImport ? '关闭 WorldInfo' : '导入 WorldInfo JSON'}
            </button>
          </div>
          {showSourceImport && (
            <div style={{ padding: '8px', borderTop: '1px solid var(--line)', fontSize: '0.78rem' }}>
              <label style={{ display: 'block', marginBottom: 4 }}>每行一个：标题|网址|类型</label>
              <textarea rows={4} value={sourceImportText} onChange={(e) => setSourceImportText(e.target.value)} placeholder={'少林派|https://...|faction\n觉远|https://...|character'} style={{ width: '100%', boxSizing: 'border-box', fontSize: '0.75rem', padding: '4px 6px' }} />
              <div style={{ display: 'flex', gap: 4, marginTop: 4 }}>
                <button type="button" className="btn btn-primary btn-sm" style={{ flex: 1 }} onClick={() => sourceImportMutation.mutate()} disabled={sourceImportMutation.isPending}>
                  {sourceImportMutation.isPending ? '导入中...' : sourceImportDryRun ? '预览导入' : '导入'}
                </button>
                <label style={{ display: 'flex', alignItems: 'center', gap: 2, fontSize: '0.7rem', whiteSpace: 'nowrap' }}>
                  <input type="checkbox" checked={sourceImportDryRun} onChange={(e) => setSourceImportDryRun(e.target.checked)} disabled={sourceImportMutation.isPending} /> 预览
                </label>
              </div>
              {sourceImportMutation.isPending && (
                <div className="loading-bar" style={{ marginTop: 8, fontSize: '0.72rem' }}>正在提交来源导入请求（预览或写入由选项决定）…</div>
              )}
            </div>
          )}
          {showWorldInfoImport && selectedEncId && (
            <div style={{ padding: '8px', borderTop: '1px solid var(--line)', fontSize: '0.78rem' }}>
              <div className="hint" style={{ marginBottom: 6 }}>支持常见设定资料 JSON：顶层为数组，或包含 entries 数组 / entries 为对象的对象；触发关键词可写在条目的扩展信息里。</div>
              <textarea
                rows={8}
                value={worldInfoImportText}
                onChange={(e) => setWorldInfoImportText(e.target.value)}
                placeholder='[ { "comment": "条目名", "content": "…", "keys": ["关键词"] } ]'
                style={{ width: '100%', boxSizing: 'border-box', fontSize: '0.75rem', padding: '4px 6px' }}
              />
              <button
                type="button"
                className="btn btn-primary btn-sm"
                style={{ marginTop: 6, width: '100%' }}
                disabled={worldInfoImportMutation.isPending || !worldInfoImportText.trim()}
                onClick={() => worldInfoImportMutation.mutate()}
              >
                {worldInfoImportMutation.isPending ? '导入中…' : '写入百科条目'}
              </button>
            </div>
          )}
        </aside>
      )}
      {!isCompactLayout && selectedEncId && (
        <div
          className="enc-column-resizer"
          role="separator"
          aria-orientation="vertical"
          aria-label="调整分类栏宽度"
          title="拖拽调整宽度，双击恢复默认"
          onMouseDown={(e) => encCol2.beginDrag(e, encCol2.width)}
          onDoubleClick={(e) => {
            e.preventDefault();
            encCol2.reset();
          }}
        />
      )}

      {/* ===== 第三级: 条目列表（选中分类后显示） ===== */}
      {selectedEncId && category && (
        <aside className="secondary-sidebar enc-3rd">
          <div className="secondary-sidebar-header">
            <div style={{ display: 'flex', alignItems: 'center', gap: '8px', overflow: 'hidden', flex: 1, minWidth: 0 }}>
              <button type="button" className="mobile-only-btn" onClick={() => returnToEncyclopediaRoute(selectedEncId)} aria-label="返回分类列表"><UiIcon name="back" /></button>
              <h3 style={{ fontSize: '0.82rem', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{category}</h3>
            </div>
            <div style={{ display: 'flex', alignItems: 'center', gap: 4, flexShrink: 0 }}>
              <button type="button" className="btn-icon" title={entryListLayout === 'grid' ? '切换为列表' : '切换为网格'} aria-label={entryListLayout === 'grid' ? '切换为列表' : '切换为网格'} onClick={() => setEntryListLayout((value) => value === 'grid' ? 'list' : 'grid')}><UiIcon name={entryListLayout === 'grid' ? 'menu' : 'grid'} /></button>
              <button type="button" className="btn-icon mobile-only-btn" data-encyclopedia-entry-new-return onClick={handleNewEntry} title="新建条目" aria-label="新建条目"><UiIcon name="plus" /></button>
            </div>
          </div>
          <div style={{ padding: '8px 10px', display: 'flex', gap: 4 }}>
            <input value={searchText} onChange={(e) => setSearchText(e.target.value)} placeholder={`搜索${category}…`} aria-label={`搜索${category}`} style={{ flex: 1, fontSize: '0.78rem', padding: '4px 8px' }} />
          </div>
          <div className="encyclopedia-entry-tools">
            <button
              type="button"
              className="btn btn-ghost btn-sm"
              disabled={aiBatchGenerateRunning}
              onClick={openBatchGenerateDialog}
            >
              <UiIcon name={aiBatchGenerateRunning ? 'loading' : 'sparkles'} className={aiBatchGenerateRunning ? 'ui-icon-loading' : undefined} />
              {aiBatchGenerateRunning ? '正在批量生成…' : `AI 批量生成${category}`}
            </button>
          </div>
          {aiBatchGenerateRunning && (
            <div ref={batchGenerateStatusRef} className="encyclopedia-batch-status" role="status" aria-live="polite" tabIndex={-1}>
              正在逐条生成，完成前请保持此页面打开。
            </div>
          )}
          <div className={`secondary-sidebar-list ${entryListLayout === 'grid' ? 'character-sidebar-grid enc-entry-grid' : ''}`}>
            {entriesQuery.isPending && <div className="secondary-sidebar-empty"><UiIcon name="loading" className="ui-icon-loading" /><p>正在加载条目…</p></div>}
            {entriesQuery.isError && (
              <InlineQueryError message="条目加载失败" error={entriesQuery.error} retrying={entriesQuery.isFetching} onRetry={() => { void entriesQuery.refetch(); }} />
            )}
            {Object.entries(groupedEntries).map(([type, items]) => (
              <div key={type} className={entryListLayout === 'grid' ? 'enc-entry-grid-section' : undefined}>
                <div style={{ padding: '4px 12px', fontSize: '0.7rem', color: 'var(--text-2)', fontWeight: 600 }}>{ENTRY_SCHEMAS[type]?.label || type} ({items.length})</div>
                {items.map((e: EncyclopediaEntry) => {
                  const cover = (e.cover_image_path || '').trim();
                  if (entryListLayout === 'grid') {
                    return (
                      <button
                        key={e.id}
                        type="button"
                        className={`character-grid-card ${selectedEntryId === e.id ? 'active' : ''}`}
                        onClick={() => openEntry(e.id, e.entry_type)}
                      >
                        <div
                          className="character-grid-cover"
                          style={{
                            backgroundColor: 'var(--surface)',
                            backgroundImage: cover ? `url(${api.storageUrl(`/storage/${cover}`)})` : undefined,
                          }}
                        >
                          {!cover && <span className="character-grid-initial">{(e.title || '?')[0]}</span>}
                        </div>
                        <div className="character-grid-caption">
                          <span className="character-grid-name">{e.title}</span>
                          {(e.summary || e.content) && (
                            <span className="character-grid-sub">
                              {(e.summary || e.content).replace(/\s+/g, ' ').slice(0, 40)}
                            </span>
                          )}
                        </div>
                      </button>
                    );
                  }
                  return (
                    <button key={e.id} type="button" className={`secondary-nav-item ${selectedEntryId === e.id ? 'active' : ''}`} style={{ padding: '6px 12px' }} onClick={() => openEntry(e.id, e.entry_type)}>
                      {cover ? (
                        <span
                          className="secondary-nav-avatar"
                          style={{
                            width: 32,
                            height: 48,
                            borderRadius: 6,
                            backgroundImage: `url(${api.storageUrl(`/storage/${cover}`)})`,
                            backgroundSize: 'cover',
                            flexShrink: 0,
                          }}
                        />
                      ) : null}
                      <span className="secondary-nav-text">
                        <span className="secondary-nav-name">{e.title}</span>
                        {(e.summary || e.content) ? (
                          <span className="secondary-nav-sub">
                            {(e.summary || e.content).replace(/\s+/g, ' ').slice(0, 80)}
                          </span>
                        ) : null}
                      </span>
                    </button>
                  );
                })}
              </div>
            ))}
            {!entriesQuery.isPending && !entriesQuery.isError && entries.length === 0 && (
              <div className="secondary-sidebar-empty">
                <UiIcon name={searchText.trim() ? 'search' : entryTypeIcon(categoryKey)} />
                <p>{searchText.trim() ? '没有匹配的条目' : `还没有${category}资料`}</p>
                {searchText.trim() ? (
                  <button type="button" className="btn btn-ghost btn-sm" onClick={() => setSearchText('')}>清空搜索</button>
                ) : (
                  <button type="button" className="btn btn-primary btn-sm" data-encyclopedia-entry-new-return onClick={handleNewEntry}><UiIcon name="plus" />新建{category}</button>
                )}
              </div>
            )}
          </div>
        </aside>
      )}
      {!isCompactLayout && selectedEncId && category && (
        <div
          className="enc-column-resizer"
          role="separator"
          aria-orientation="vertical"
          aria-label="调整条目列表宽度"
          title="拖拽调整宽度，双击恢复默认"
          onMouseDown={(e) => encCol3.beginDrag(e, encCol3.width)}
          onDoubleClick={(e) => {
            e.preventDefault();
            encCol3.reset();
          }}
        />
      )}

      {/* ===== 右侧详情区 ===== */}
      <main className="secondary-main">
        {showEntryForm ? (
          <div>
            <div className="secondary-detail-header">
              <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                <button className="btn-icon mobile-only" type="button" disabled={saveEntryMutation.isPending} onClick={() => { void requestCloseEntryForm(); }} style={{ flexShrink: 0 }} aria-label="返回条目列表">←</button>
                <h2>{editingEntry.id ? '编辑条目' : `新建${category || '条目'}`}</h2>
                {isEntryFormDirty && <span className="pill pill-accent" role="status">未保存</span>}
              </div>
              <div className="button-row">
                <button
                  type="button"
                  className="btn btn-primary btn-sm"
                  disabled={saveEntryMutation.isPending}
                  onClick={() => {
                    if (saveEntrySubmittingRef.current) return;
                    saveEntrySubmittingRef.current = true;
                    if (!selectedEncId) {
                      saveEntrySubmittingRef.current = false;
                      showToast('请先选择百科库', 'warn');
                      return;
                    }
                    saveEntryMutation.mutate({
                      payload: { ...editingEntry },
                      routeKey: routeSnapshotRef.current.key,
                      formRevision: entryFormRevisionRef.current,
                      encyclopediaId: selectedEncId,
                      category: categoryKey,
                    });
                  }}
                >
                  {saveEntryMutation.isPending ? '保存中...' : '保存'}
                </button>
                <button type="button" className="btn btn-ghost btn-sm" disabled={saveEntryMutation.isPending} onClick={() => { void requestCloseEntryForm(); }}>取消</button>
              </div>
            </div>
            <div className="form-grid">
              <div className="form-group full-row"><label className="required">标题</label><input value={editingEntry.title || ''} onChange={(e) => setEditingEntry({ ...editingEntry, title: e.target.value })} maxLength={300} required /></div>
              <div className="form-group full-row">
                <div className="form-section-title">条目封面</div>
                <p className="hint">
                  封面会出现在列表和网格里；用设置里配好的生图账号出图。
                  {editingEntry.id ? ' 生成完成后自动更新封面。' : ' 生成完成后请保存条目。'}
                </p>
                <div style={{ width: '100%' }}>
                  <ExpandableTextArea
                  aria-label="封面补充说明"
                  value={entryCoverHint}
                  onChange={(e) => setEntryCoverHint(e.target.value)}
                  placeholder="补充人物、场景、光线与风格（可选）"
                  />
                </div>
                <div style={{ marginTop: 8, display: 'flex', gap: 12, alignItems: 'center', flexWrap: 'wrap' }}>
                  <button
                    type="button"
                    className="btn btn-primary btn-sm"
                    disabled={generateEntryCoverMutation.isPending}
                    onClick={() => generateEntryCover(entryCoverHint)}
                  >
                    {generateEntryCoverMutation.isPending ? '生成中…' : 'AI 生成条目封面'}
                  </button>
                  {(editingEntry.cover_image_path || '').trim() ? (
                    <img
                      alt="封面"
                      src={api.storageUrl(`/storage/${editingEntry.cover_image_path}`)}
                      style={{ width: 88, aspectRatio: '2 / 3', objectFit: 'cover', borderRadius: 8, border: '1px solid var(--line)' }}
                    />
                  ) : null}
                </div>
              </div>
            {SHOW_BUILTIN_ENCYCLOPEDIA_NAME_GENERATOR_UI && showNameGen && (
              <div style={{ padding: 12, marginBottom: 8, background: 'var(--surface)', borderRadius: 'var(--radius-sm)', border: '1px solid var(--line)' }}>
                <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 8 }}>
                  <strong style={{ display: 'inline-flex', alignItems: 'center', gap: 6 }}><UiIcon name="sparkles" />名称生成器</strong>
                  <button type="button" className="btn btn-ghost btn-sm" aria-label="关闭名称生成器" onClick={() => setShowNameGen(false)}><UiIcon name="close" /></button>
                </div>
                <p className="hint" style={{ marginBottom: 10 }}>
                  后端已升级为：东方「姓+名」分层、西/科幻/克系人名马尔可夫链、势力/地名语法修正，并可传已有名称去重。
                  勾选「LLM 再润色」将使用<strong>人物页中第一个有效联网角色</strong>的 Key 再跑一轮（条数不变，可能略慢）。
                </p>
                <div className="form-row" style={{ marginBottom: 8 }}>
                  <div className="form-group"><label>风格</label>
                    <select value={genStyle} onChange={(e) => setGenStyle(e.target.value)}>
                      {(genStyles.length ? genStyles : [
                        { id: 'eastern', label: '东方玄幻' },
                        { id: 'western', label: '西方奇幻' },
                        { id: 'cthulhu', label: '克苏鲁神话' },
                        { id: 'scifi', label: '科幻/星战' },
                      ]).map((s) => (<option key={s.id} value={s.id}>{s.label}</option>))}
                    </select>
                  </div>
                  <div className="form-group"><label>类型</label>
                    <select value={genType} onChange={(e) => setGenType(e.target.value)}>
                      {(genTypes.length ? genTypes : [
                        { id: 'character', label: '人物名' },
                        { id: 'location', label: '地名' },
                        { id: 'skill', label: '功法/技能名' },
                        { id: 'item', label: '物品/神器名' },
                        { id: 'faction', label: '势力/组织名' },
                      ]).map((t) => (<option key={t.id} value={t.id}>{t.label}</option>))}
                    </select>
                  </div>
                  <div className="form-group"><label>数量</label>
                    <select value={genCount} onChange={(e) => setGenCount(Number(e.target.value))}>
                      {[3,5,10,15,20].map((n) => (<option key={n} value={n}>{n}</option>))}
                    </select>
                  </div>
                </div>
                <label style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 10, cursor: 'pointer', fontSize: '0.9rem' }}>
                  <input type="checkbox" checked={genRefineLlm} onChange={(e) => setGenRefineLlm(e.target.checked)} />
                  LLM 再润色一轮（可选）
                </label>
                <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8, alignItems: 'center', marginBottom: 8 }}>
                  <button type="button" className="btn btn-primary btn-sm" onClick={async () => {
                    setGenLoading(true);
                    setGenRefineNote(null);
                    try {
                      const existing = genResults.map((r: { name?: string }) => (r?.name || '').trim()).filter(Boolean);
                      const data = await api.post('/encyclopedia/generate-names', {
                        style: genStyle,
                        name_type: genType,
                        count: genCount,
                        existing,
                        refine_llm: genRefineLlm,
                      });
                      setGenResults(data.results || []);
                      const note = (data as { refine_note?: string | null }).refine_note;
                      setGenRefineNote(note ?? null);
                      if (note) showToast(note, note.includes('失败') || note.includes('不符') ? 'error' : 'info');
                    } catch { showToast('生成失败', 'error'); }
                    setGenLoading(false);
                  }} disabled={genLoading}>
                    {genLoading ? <><UiIcon name="loading" className="ui-icon-loading" /><span>生成中</span></> : <><UiIcon name="sparkles" /><span>生成</span></>}
                  </button>
                  {genResults.length > 0 ? (
                    <button
                      type="button"
                      className="btn btn-ghost btn-sm"
                      onClick={() => {
                        const t = genResults.map((r: { name?: string }) => r?.name).filter(Boolean).join('\n');
                        void copyText(t).then(() => showToast('Copied all names', 'success')).catch(() => showToast('Copy failed', 'error'));
                      }}
                    >复制全部名称</button>
                  ) : null}
                </div>
                {genRefineNote ? (
                  <p className="hint" style={{ marginBottom: 8, color: 'var(--text-2)' }}>{genRefineNote}</p>
                ) : null}
                {genResults.length > 0 && (
                  <div style={{ marginTop: 8, maxHeight: 240, overflowY: 'auto' }}>
                    {genResults.map((r, i) => (
                      <div key={`${r.name}-${i}`} className="mini-card" style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '6px 10px', cursor: 'pointer', marginBottom: 4 }}
                        onClick={() => { setEditingEntry({ ...editingEntry, title: r.name }); setShowNameGen(false); }}>
                        <div>
                          <strong>{r.name}</strong>
                          <div style={{ fontSize: '0.7rem', color: 'var(--text-2)' }}>{r.meaning || ''}</div>
                        </div>
                        <button type="button" className="btn btn-ghost btn-sm" aria-label={`复制${r.name}`} onClick={(e) => { e.stopPropagation(); void copyText(r.name).then(() => showToast('Copied', 'success')).catch(() => showToast('Copy failed', 'error')); }}><UiIcon name="copy" /></button>
                      </div>
                    ))}
                  </div>
                )}
              </div>
            )}
            {SHOW_BUILTIN_ENCYCLOPEDIA_NAME_GENERATOR_UI ? (
            <button type="button" className="btn btn-ghost btn-sm" style={{ marginBottom: 8 }} onClick={() => setShowNameGen(!showNameGen)}>
              <UiIcon name="sparkles" /><span>{showNameGen ? '关闭' : '随机生成名称'}</span>
            </button>
            ) : null}
            <div className="form-row full-row">
                <label>条目类型</label>
                <select
                  value={editingEntry.entry_type || 'concept'}
                  onChange={async (event) => {
                    const nextType = event.target.value;
                    if (nextType === editingEntry.entry_type) return;
                    const ok = await confirmModal('切换条目类型', '切换类型会重置扩展字段，但摘要和正文内容不会丢失。确认切换？', 'warning');
                    if (ok) {
                      setEditingEntry({ ...editingEntry, entry_type: nextType, meta_json: buildMetaForType(nextType, editingEntry.meta_json || {}) });
                    }
                  }}
                >
                  {Object.entries(ENTRY_SCHEMAS).map(([typeKey, schema]) => (
                    <option key={typeKey} value={typeKey}>{schema.label}</option>
                  ))}
                </select>
              </div>
              <div className="form-row full-row">
                <div className="form-group"><label>标签</label><input value={editingEntry.tags || ''} onChange={(e) => setEditingEntry({ ...editingEntry, tags: e.target.value })} placeholder="多个标签用逗号隔开，比如：江湖,门派,少林" /></div>
              </div>
              <div className="form-group full-row"><label>一句话简介</label><textarea rows={2} value={editingEntry.summary || ''} onChange={(e) => setEditingEntry({ ...editingEntry, summary: e.target.value })} placeholder="用一句话概括这个条目" /></div>
              <div className="form-group full-row"><label>详细内容</label><ExpandableTextArea aria-label="详细内容" value={editingEntry.content || ''} onChange={(e) => setEditingEntry({ ...editingEntry, content: e.target.value })} placeholder="填写详细设定，AI 会参考这些内容来回答" maxLength={50000} /></div>

              {/* AI 智能补全按钮 */}
              <div className="form-group full-row">
                <button
                  className="btn btn-primary btn-sm"
                  type="button"
                  disabled={
                    aiCompleting
                    || !selectedEncId
                    || Boolean(encLibraryTool)
                    || !editingEntry.title?.trim()
                    || !(editingEntry.summary?.trim() || editingEntry.content?.trim())
                  }
                  onClick={async () => {
                    setAiCompleting(true);
                    try {
                      const result = await api.post('/encyclopedia/entries/ai-complete', {
                        title: editingEntry.title,
                        summary: editingEntry.summary || '',
                        content: editingEntry.content || '',
                        entry_type: editingEntry.entry_type || 'concept',
                      });
                      if (result.meta_json) {
                        const merged = { ...(editingEntry.meta_json || {}) };
                        for (const [key, value] of Object.entries(result.meta_json)) {
                          const existing = merged[key];
                          if (existing === undefined || existing === '' || existing === null || (Array.isArray(existing) && existing.length === 0)) {
                            merged[key] = value;
                          }
                        }
                        const nextContent = (editingEntry.content || '').trim()
                          ? editingEntry.content
                          : (result.content || '').trim() || editingEntry.content;
                        setEditingEntry({
                          ...editingEntry,
                          meta_json: merged,
                          summary: (editingEntry.summary || '').trim() || (result.summary || '').trim() || editingEntry.summary,
                          content: nextContent,
                        });
                        showToast(`AI 已补全 ${Object.keys(result.meta_json).length} 个字段`, 'success');
                      }
                    } catch (e) {
                      showToast(String(e), 'error');
                    }
                    setAiCompleting(false);
                  }}
                >
                  {aiCompleting ? (
                    <><UiIcon name="loading" className="ui-icon-loading" /><span>AI 补全中…</span></>
                  ) : (
                    <><UiIcon name="sparkles" /><span>AI 智能补全</span></>
                  )}
                </button>
                <div className="hint">
                  填写标题与「一句话简介」或「详细内容」后，AI 会补全扩展字段；简介单独写也能生成详细初稿（不覆盖你已写的内容）。
                  请先选百科库并进入<strong>条目编辑</strong>；在「本库时间线 / 图谱 / 沉淀」全屏视图中，本按钮会禁用以免误触。
                </div>
              </div>

              <details style={{ marginTop: 8, padding: 8, border: '1px solid var(--line)', borderRadius: 'var(--radius-sm)' }}>
                <summary style={{ cursor: 'pointer', fontWeight: 600, fontSize: '0.85rem' }}>触发规则（控制什么时候把这条内容告诉 AI）</summary>
                <div style={{ marginTop: 8 }}>
                  <div className="form-row">
                    <div className="form-group" style={{ flex: 1 }}>
                      <label>触发关键词（逗号分隔）</label>
                      <input value={metaStringArray(editingEntry.meta_json, 'trigger_keywords').join(',')} onChange={(e) => {
                        const kw = e.target.value.split(',').map(s => s.trim()).filter(Boolean);
                        setEditingEntry({ ...editingEntry, meta_json: { ...(editingEntry.meta_json || {}), trigger_keywords: kw } });
                      }} placeholder="例如：少林,易筋经,方丈" />
                      <div className="hint">当聊天中出现这些词时，此条目会自动注入 AI 上下文</div>
                    </div>
                    <div className="form-group" style={{ flex: 1 }}>
                      <label>触发正则（每行一个）</label>
                      <textarea rows={2} value={metaStringArray(editingEntry.meta_json, 'trigger_regex').join('\n')} onChange={(e) => {
                        const rx = e.target.value.split('\n').map(s => s.trim()).filter(Boolean);
                        setEditingEntry({ ...editingEntry, meta_json: { ...(editingEntry.meta_json || {}), trigger_regex: rx } });
                      }} placeholder="例如：少\w{2}" />
                    </div>
                  </div>
                  <div className="form-row" style={{ marginTop: 8 }}>
                    <div className="form-group">
                      <label>优先级</label>
                      <input type="number" min={0} max={100} value={metaNumber(editingEntry.meta_json, 'priority', 0)} onChange={(e) => {
                        setEditingEntry({ ...editingEntry, meta_json: { ...(editingEntry.meta_json || {}), priority: Number(e.target.value) } });
                      }} style={{ width: 80 }} />
                      <div className="hint">数值越大越优先注入</div>
                    </div>
                    <div className="form-group">
                      <label>激活模式</label>
                      <select value={metaString(editingEntry.meta_json, 'activation_mode', 'normal')} onChange={(e) => {
                        setEditingEntry({ ...editingEntry, meta_json: { ...(editingEntry.meta_json || {}), activation_mode: e.target.value } });
                      }}>
                        <option value="normal">普通（关键词命中时注入）</option>
                        <option value="constant">常驻（始终注入）</option>
                        <option value="mention_only">提及模式（只有明确提到条目名称时才注入）</option>
                        <option value="recursive">递归（命中后扫描关联条目）</option>
                      </select>
                    </div>
                    <div className="form-group">
                      <label>往对话里塞这条时，来源至少要到</label>
                      <select value={metaString(editingEntry.meta_json, 'min_trust_level', 'unverified')} onChange={(e) => {
                        setEditingEntry({ ...editingEntry, meta_json: { ...(editingEntry.meta_json || {}), min_trust_level: e.target.value } });
                      }}>
                        <option value="unverified">不限</option>
                        <option value="manual">人工录入以上</option>
                        <option value="wiki">Wiki 来源以上</option>
                        <option value="official">仅官方认证</option>
                      </select>
                    </div>
                  </div>
                </div>
              </details>

              {(() => {
                const schema = ENTRY_SCHEMAS[editingEntry.entry_type as string];
                if (!schema) return null;
                return schema.fields.map((field) => {
                  const meta: EncyclopediaMetaJson = editingEntry.meta_json || {};
                  return (
                    <div key={field.key} className="form-group full-row">
                      <label>{field.label}</label>
                      <FieldRenderer field={field} value={meta[field.key]} onChange={(v) => setEditingEntry({ ...editingEntry, meta_json: { ...meta, [field.key]: v } })} />
                    </div>
                  );
                });
              })()}
              <div className="form-group full-row"><label>变更说明</label><input value={editingEntry.change_note || ''} onChange={(e) => setEditingEntry({ ...editingEntry, change_note: e.target.value })} placeholder="改了什么？" /></div>
            </div>
          </div>
        ) : selectedEncId && encLibraryTool === 'timeline' ? (
          <div style={{ padding: 16, maxWidth: 720 }}>
            <div className="button-row" style={{ marginBottom: 12 }}>
              <h2 style={{ margin: 0, flex: 1 }}>本库时间线</h2>
              <button type="button" className="btn btn-ghost btn-sm" onClick={() => setEncLibraryTool(null)}>关闭</button>
            </div>
            <p className="hint" style={{ marginBottom: 12 }}>按 time_order 排序；可与条目编辑里的关系、事件条目配合使用。</p>
            <div className="mini-card" style={{ marginBottom: 16 }}>
              <div className="form-row" style={{ gap: 8, flexWrap: 'wrap' }}>
                <input placeholder="事件标题" value={tlTitle} onChange={(e) => setTlTitle(e.target.value)} style={{ flex: '1 1 140px' }} />
                <input placeholder="时间标签（如：纪元前 12 年）" value={tlTimeLabel} onChange={(e) => setTlTimeLabel(e.target.value)} style={{ flex: '1 1 160px' }} />
                <input type="number" placeholder="排序" value={tlOrder} onChange={(e) => setTlOrder(Number(e.target.value))} style={{ width: 88 }} />
                <button type="button" className="btn btn-primary btn-sm" disabled={!tlTitle.trim() || addTimelineMutation.isPending} onClick={() => selectedEncId && addTimelineMutation.mutate(selectedEncId)}>
                  {addTimelineMutation.isPending ? '…' : '添加'}
                </button>
              </div>
            </div>
            {encTimelineQuery.isLoading && <div>加载中…</div>}
            {encTimelineQuery.isError && (
              <div className="secondary-empty">
                <p>时间线加载失败</p>
                <button type="button" className="btn btn-ghost btn-sm" onClick={() => encTimelineQuery.refetch()}>重试</button>
              </div>
            )}
            <div className="stack-list">
              {!encTimelineQuery.isError && (encTimelineQuery.data ?? []).map((ev: any) => (
                <div key={ev.id} className="mini-card" style={{ display: 'flex', justifyContent: 'space-between', gap: 8, alignItems: 'flex-start' }}>
                  <div>
                    <span className="pill pill-sm">{ev.time_label || '未标注时间'}</span>
                    <strong style={{ marginLeft: 6 }}>{ev.title}</strong>
                    {ev.summary && <div style={{ fontSize: '0.82rem', color: 'var(--text-2)', marginTop: 4 }}>{ev.summary}</div>}
                  </div>
                  <button type="button" className="btn btn-ghost btn-sm btn-danger" onClick={() => deleteTimelineMutation.mutate(ev.id)}>删</button>
                </div>
              ))}
              {encTimelineQuery.isSuccess && encTimelineQuery.data.length === 0 && (
                <div style={{ color: 'var(--text-2)' }}>暂无事件，可在上方添加。</div>
              )}
            </div>
          </div>
        ) : selectedEncId && encLibraryTool === 'graph' ? (
          <div style={{ padding: 16 }}>
            <div className="button-row" style={{ marginBottom: 12 }}>
              <h2 style={{ margin: 0, flex: 1 }}>本库关系图谱</h2>
              <button type="button" className="btn btn-ghost btn-sm" onClick={() => setEncLibraryTool(null)}>关闭</button>
            </div>
            <p className="hint" style={{ marginBottom: 12 }}>
              与「聊天记忆 / 会话记忆」不同：这里只展示<strong>百科条目之间</strong>已保存的关联边。新建边可在条目编辑里添加，或使用下方「两点建关系」在图上指定两端后再提交。
            </p>
            {encGraphQuery.isLoading && <div>加载中…</div>}
            {encGraphQuery.isError && (
              <div className="secondary-empty">
                <p>关系图谱加载失败</p>
                <button type="button" className="btn btn-ghost btn-sm" onClick={() => encGraphQuery.refetch()}>重试</button>
              </div>
            )}
            {encGraphQuery.isSuccess && encGraphQuery.data.nodes.length === 0 && (
              <p style={{ color: 'var(--text-2)' }}>暂无关系数据。可在条目编辑中创建条目间关系；有至少一条边后，图谱会显示节点。</p>
            )}
            {encGraphQuery.isSuccess && encGraphQuery.data.nodes.length > 0 && (() => {
              const libNodes = encGraphQuery.data.nodes;
              const titleOf = (id: number | null) =>
                id == null ? '—' : libNodes.find((n) => n.id === id)?.title ?? `#${id}`;
              const onLibGraphNode = (id: number) => {
                if (!graphRelPickMode) {
                  const node = libNodes.find((item) => item.id === id);
                  openEntry(id, node?.entry_type);
                  return;
                }
                if (graphRelA === id && graphRelB == null) {
                  setGraphRelA(null);
                  return;
                }
                if (graphRelB === id) {
                  setGraphRelB(null);
                  return;
                }
                if (graphRelA === id && graphRelB != null) {
                  setGraphRelA(null);
                  setGraphRelB(null);
                  return;
                }
                if (graphRelA == null) {
                  setGraphRelA(id);
                  return;
                }
                if (graphRelB == null && id !== graphRelA) {
                  setGraphRelB(id);
                  return;
                }
                if (graphRelB != null && id !== graphRelA) {
                  setGraphRelB(id);
                }
              };
              return (
                <>
                  <div className="button-row" style={{ flexWrap: 'wrap', gap: 8, marginBottom: 12 }}>
                    <button
                      type="button"
                      className={`btn btn-sm ${graphRelPickMode ? 'btn-primary' : 'btn-ghost'}`}
                      onClick={() => {
                        setGraphRelPickMode((v) => !v);
                        setGraphRelA(null);
                        setGraphRelB(null);
                      }}
                    >
                      {graphRelPickMode ? '退出「两点建关系」' : '两点建关系'}
                    </button>
                  </div>
                  {graphRelPickMode && (
                    <div className="mini-card" style={{ marginBottom: 12 }}>
                      <p style={{ margin: '0 0 8px', fontSize: '0.85rem', color: 'var(--text-2)' }}>
                        橙色为起点，绿色为终点；点某一端点可清除该端。两端都选好并填写类型后，点「创建关系」写入数据库。
                      </p>
                      <div style={{ fontSize: '0.82rem', marginBottom: 8 }}>
                        <strong>起点：</strong>{titleOf(graphRelA)}
                        {' · '}
                        <strong>终点：</strong>{titleOf(graphRelB)}
                      </div>
                      <div className="form-row" style={{ gap: 8, flexWrap: 'wrap', alignItems: 'center' }}>
                        <input placeholder="关系类型" value={graphRelType} onChange={(e) => setGraphRelType(e.target.value)} style={{ flex: '1 1 120px' }} />
                        <input placeholder="备注（可选）" value={graphRelLabel} onChange={(e) => setGraphRelLabel(e.target.value)} style={{ flex: '1 1 160px' }} />
                        <button
                          type="button"
                          className="btn btn-primary btn-sm"
                          disabled={graphRelA == null || graphRelB == null || createEncRelationMutation.isPending}
                          onClick={() => createEncRelationMutation.mutate()}
                        >
                          {createEncRelationMutation.isPending ? '…' : '创建关系'}
                        </button>
                      </div>
                    </div>
                  )}
                  <SimpleRelationGraph
                    nodes={encGraphQuery.data.nodes}
                    edges={encGraphQuery.data.edges}
                    pickAId={graphRelPickMode ? graphRelA : undefined}
                    pickBId={graphRelPickMode ? graphRelB : undefined}
                    footerHint={
                      graphRelPickMode
                        ? '建关系模式：先点起点（橙），再点终点（绿）；退出本模式后，单击节点将打开条目详情。'
                        : '环形示意布局；线表示关系。单击节点打开对应条目；或先开启「两点建关系」在图上指定两端后再创建。'
                    }
                    onSelectNode={onLibGraphNode}
                  />
                </>
              );
            })()}
          </div>
        ) : selectedEncId && encLibraryTool === 'sediment' ? (
          <SedimentReviewPanel key={selectedEncId} encyclopediaId={selectedEncId}
            initialLocation={sedimentLocation?.encyclopediaId === selectedEncId ? sedimentLocation.location : undefined}
            onOpenEntry={(id, type, location) => { setSedimentLocation({ encyclopediaId: selectedEncId, location }); void openEntry(id, type); }}
            onClose={(location) => { setSedimentLocation({ encyclopediaId: selectedEncId, location }); setEncLibraryTool(null); }} />
        ) : selectedEntryId && entryDetailQuery.isPending ? (
          <div className="secondary-empty">条目详情加载中…</div>
        ) : selectedEntryId && entryDetailQuery.isError ? (
          <div className="secondary-empty">
            <h2>条目暂时打不开</h2>
            <p style={{ color: 'var(--text-2)', marginTop: 8 }}>可能已被删除，也可能是本地服务暂时不可用。</p>
            <div className="button-row" style={{ marginTop: 12 }}>
              <button type="button" className="btn btn-primary btn-sm" onClick={() => entryDetailQuery.refetch()}>重试</button>
              {sedimentLocation?.encyclopediaId === selectedEncId && <button type="button" className="btn btn-ghost btn-sm" onClick={() => { void openLibraryTool('sediment'); }}>返回沉淀资料</button>}
              <button type="button" className="btn btn-ghost btn-sm" onClick={() => returnToEncyclopediaRoute(selectedEncId, categoryKey)}>返回条目列表</button>
            </div>
          </div>
        ) : !entry ? (
          <div className="secondary-empty">
            <div className="welcome-icon" aria-hidden="true"><UiIcon name={selectedEncId ? entryTypeIcon(categoryKey) : 'world'} /></div>
            <h2>{!selectedEncId ? '建立你的世界百科' : category ? `选择${category}资料` : selectedEnc?.name || '选择资料分类'}</h2>
            <p style={{ color: 'var(--text-2)', marginTop: 8 }}>
              {!selectedEncId
                ? '把人物、地点、事件和世界规则整理在一起，供长篇故事持续使用。'
                : category
                  ? `从列表选择一条${category}资料查看，或直接创建新条目。`
                  : '选择一个分类开始整理资料，也可使用时间线、关系图谱和沉淀工具。'}
            </p>
            <div className="button-row" style={{ marginTop: 16 }}>
              {!selectedEncId ? (
                <button type="button" className="btn btn-primary" onClick={openCreateEncyclopediaForm}><UiIcon name="plus" />新建百科库</button>
              ) : category ? (
                <button type="button" className="btn btn-primary" onClick={handleNewEntry}><UiIcon name="plus" />新建{category}</button>
              ) : null}
            </div>
          </div>
        ) : (
          <>
            <div className="secondary-detail-header">
              <div className="secondary-detail-title">
                <button className="btn-icon mobile-only" type="button" onClick={() => returnToEncyclopediaRoute(selectedEncId, categoryKey)} style={{ marginRight: 8, flexShrink: 0 }} aria-label="返回条目列表"><UiIcon name="back" /></button>
                <span className="encyclopedia-detail-icon"><UiIcon name={entryTypeIcon(entry.entry_type)} /></span>
                <div>
                  <h2 style={{ fontSize: '1.3rem' }}>{entry.title}</h2>
                  <p className="secondary-detail-meta">{entryTypeDef?.label || entry.entry_type} {entry.tags ? `| ${entry.tags}` : ''}</p>
                </div>
              </div>
              <div className="button-row" style={{ flexWrap: 'wrap', gap: 8, alignItems: 'center' }}>
                {sedimentLocation?.encyclopediaId === selectedEncId && <button type="button" className="btn btn-ghost btn-sm" onClick={() => { void openLibraryTool('sediment'); }}>返回沉淀资料</button>}
                <button type="button" className="btn btn-ghost btn-sm" data-encyclopedia-entry-edit-return onClick={() => handleEditEntry(entry)}><UiIcon name="edit" />编辑</button>
                <div style={{ width: '100%' }}>
                  <ExpandableTextArea
                  aria-label="封面补充说明"
                  value={entryCoverHint}
                  onChange={(e) => setEntryCoverHint(e.target.value)}
                  placeholder="补充人物、场景、光线与风格（可选）"
                  />
                </div>
                <button
                  type="button"
                  className="btn btn-primary btn-sm"
                  disabled={generateEntryCoverMutation.isPending}
                  onClick={() => generateEntryCover(entryCoverHint, entry.id)}
                >
                  <UiIcon name={generateEntryCoverMutation.isPending ? 'loading' : 'sparkles'} className={generateEntryCoverMutation.isPending ? 'ui-icon-loading' : undefined} />
                  {generateEntryCoverMutation.isPending ? '生成中…' : '生成封面'}
                </button>
                <button
                  className="btn btn-ghost btn-sm btn-danger"
                  type="button"
                  title={deleteEntryMutation.isPending ? '正在删除条目' : '删除条目'}
                  aria-label={deleteEntryMutation.isPending ? '正在删除条目' : '删除条目'}
                  disabled={deleteEntryMutation.isPending}
                  onClick={async () => {
                    if (deleteEntryMutation.isPending) return;
                    const ok = await confirmModal('删除条目', `确定删除「${entry.title}」？删除后不可恢复。`, 'danger');
                    if (ok) deleteEntryMutation.mutate({ id: entry.id, routeKey: routeSnapshotRef.current.key });
                  }}
                >
                  <UiIcon name={deleteEntryMutation.isPending ? 'loading' : 'delete'} className={deleteEntryMutation.isPending ? 'ui-icon-loading' : undefined} />
                </button>
              </div>
            </div>
            <div className="secondary-tabs">
              <button type="button" className={`secondary-tab ${activeTab === 'content' ? 'active' : ''}`} onClick={() => setActiveTab('content')}>详情</button>
              <button type="button" className={`secondary-tab ${activeTab === 'relations' ? 'active' : ''}`} onClick={() => setActiveTab('relations')}>关系 ({relations.length})</button>
              <button type="button" className={`secondary-tab ${activeTab === 'graph' ? 'active' : ''}`} onClick={() => setActiveTab('graph')}>图谱</button>
              <button type="button" className={`secondary-tab ${activeTab === 'timeline' ? 'active' : ''}`} onClick={() => setActiveTab('timeline')}>时间线</button>
              <button type="button" className={`secondary-tab ${activeTab === 'sediment' ? 'active' : ''}`} onClick={() => setActiveTab('sediment')}>沉淀</button>
            </div>
            {activeTab === 'content' && <EntryDetailView entry={entry} entryType={entry.entry_type} />}
            {activeTab === 'sediment' && entry && (
              <div className="stack-list" style={{ maxWidth: 640 }}>
                <p className="hint">
                  从对话写进百科，在聊天里点「沉淀」或靠自动沉淀。这里只看<strong>当前这条</strong>从哪来的、分值多少。
                </p>
                <p className="hint" style={{ marginTop: 8 }}>
                  与<strong>会话侧记忆（RAG / 记忆条）</strong>不同：沉淀页描述的是「这条百科条目」是否由某次对话生成、可信度如何；不会在聊天里自动当作长上下文注入。
                </p>
                <div className="mini-card" style={{ marginTop: 12 }}>
                  <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8, alignItems: 'center' }}>
                    <span className="pill pill-sm">分值：{entry.confidence}</span>
                    {entry.source_session_id != null && (
                      <a className="btn btn-ghost btn-sm" href={`/chat/${entry.source_session_id}`}>
                        打开来源会话 #{entry.source_session_id}
                      </a>
                    )}
                    {entry.source_message_id != null && (
                      <span style={{ color: 'var(--text-2)', fontSize: '0.85rem' }}>来源消息 ID：{entry.source_message_id}</span>
                    )}
                  </div>
                  <p style={{ fontSize: '0.82rem', color: 'var(--text-2)', marginTop: 10 }}>
                    创建：{new Date(entry.created_at).toLocaleString()} · 更新：{new Date(entry.updated_at).toLocaleString()}
                  </p>
                  {entry.confidence === 'confirmed' && entry.source_session_id == null && (
                    <p style={{ marginTop: 8, color: 'var(--text-2)' }}>本条为人工或模板创建，无会话自动沉淀溯源。</p>
                  )}
                  {(entry.confidence === 'inferred' || entry.confidence === 'speculative' || entry.source_session_id != null) && (
                    <p style={{ marginTop: 8, fontSize: '0.88rem', color: 'var(--text-2)' }}>
                      推断出来的条目，在编辑里改一改、保存，就相当于你自己核对过了。
                    </p>
                  )}
                </div>
              </div>
            )}
            {activeTab === 'relations' && (
              <div className="stack-list" style={{ maxWidth: 600 }}>
                {relations.map((r: any) => (
                  <div key={r.id} className="mini-card" style={{ cursor: 'pointer' }} onClick={() => {
                    const fromCurrent = r.from_id === entry.id;
                    openEntry(fromCurrent ? r.to_id : r.from_id, fromCurrent ? r.to_type : r.from_type);
                  }}>
                    <span className="pill pill-sm">{r.relation_type}</span>
                    <strong>{r.from_id === entry.id ? r.to_title : r.from_title}</strong>
                    <small style={{ color: 'var(--text-2)' }}>({r.from_id === entry.id ? r.to_type : r.from_type})</small>
                  </div>
                ))}
                {relations.length === 0 && <div style={{ padding: 16, color: 'var(--text-2)' }}>暂无关联条目，可在编辑时添加关系</div>}
              </div>
            )}
            {activeTab === 'graph' && (
              <div style={{ maxWidth: 640 }}>
                {entryGraphQuery.isLoading && <div>加载中…</div>}
                {entryGraphQuery.data && entryGraphQuery.data.nodes.length === 0 && (
                  <p style={{ color: 'var(--text-2)' }}>当前条目暂无关联图扩展（深度 2 内无邻居）。</p>
                )}
                {entryGraphQuery.data && entryGraphQuery.data.nodes.length > 0 && (
                  <SimpleRelationGraph
                    nodes={entryGraphQuery.data.nodes}
                    edges={entryGraphQuery.data.edges}
                    highlightId={entry.id}
                    onSelectNode={(id) => {
                      const node = entryGraphQuery.data?.nodes.find((item) => item.id === id);
                      openEntry(id, node?.entry_type);
                    }}
                  />
                )}
              </div>
            )}
            {activeTab === 'timeline' && (
              <div style={{ maxWidth: 640 }}>
                <p className="hint" style={{ marginBottom: 12 }}>以下为当前百科库下的全局时间线（与条目「时间线」类型元数据不同通道）。</p>
                <div className="mini-card" style={{ marginBottom: 12 }}>
                  <div className="form-row" style={{ gap: 8, flexWrap: 'wrap' }}>
                    <input placeholder="事件标题" value={tlTitle} onChange={(e) => setTlTitle(e.target.value)} style={{ flex: '1 1 140px' }} />
                    <input placeholder="时间标签" value={tlTimeLabel} onChange={(e) => setTlTimeLabel(e.target.value)} style={{ flex: '1 1 140px' }} />
                    <input type="number" placeholder="排序" value={tlOrder} onChange={(e) => setTlOrder(Number(e.target.value))} style={{ width: 88 }} />
                    <button type="button" className="btn btn-primary btn-sm" disabled={!tlTitle.trim() || addTimelineMutation.isPending} onClick={() => entry.encyclopedia_id && addTimelineMutation.mutate(entry.encyclopedia_id)}>
                      {addTimelineMutation.isPending ? '…' : '添加'}
                    </button>
                  </div>
                </div>
                {entryTimelineQuery.isLoading && <div>加载中…</div>}
                <div className="stack-list">
                  {(entryTimelineQuery.data ?? []).map((ev: any) => (
                    <div key={ev.id} className="mini-card" style={{ display: 'flex', justifyContent: 'space-between', gap: 8 }}>
                      <div>
                        <span className="pill pill-sm">{ev.time_label || '未标注'}</span>
                        <strong style={{ marginLeft: 6 }}>{ev.title}</strong>
                        {ev.summary && <div style={{ fontSize: '0.82rem', color: 'var(--text-2)', marginTop: 4 }}>{ev.summary}</div>}
                      </div>
                      <button type="button" className="btn btn-ghost btn-sm btn-danger" onClick={() => deleteTimelineMutation.mutate(ev.id)}>删</button>
                    </div>
                  ))}
                  {(entryTimelineQuery.data ?? []).length === 0 && !entryTimelineQuery.isLoading && (
                    <div style={{ color: 'var(--text-2)' }}>暂无事件。</div>
                  )}
                </div>
              </div>
            )}
          </>
        )}
      </main>
      <BatchGenerateDialog
        open={showBatchGenerateDialog}
        category={category}
        count={batchGenerateCount}
        contextHint={batchGenerateHint}
        anchorWeak={encyclopediaAnchorWeak}
        allowWeakAnchor={batchIgnoreWeakAnchor}
        onCountChange={setBatchGenerateCount}
        onContextHintChange={setBatchGenerateHint}
        onAllowWeakAnchorChange={setBatchIgnoreWeakAnchor}
        onCancel={() => setShowBatchGenerateDialog(false)}
        onSubmit={(count, contextHint) => { void submitBatchGenerate(count, contextHint); }}
      />
    </div>
  );
}
