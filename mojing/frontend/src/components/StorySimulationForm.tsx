import type { FormEvent } from 'react';
import InlineQueryError, { type RefreshableQuery } from './InlineQueryError';
import type { Character, WorldEncyclopedia, WorldTemplate } from '../types';

export type StorySimulationFormValues = {
  premise: string;
  direction: string;
  tone: string;
  chapter_count: number;
  template_id: string;
  encyclopedia_id: string;
  character_ids: number[];
};

type Props = {
  values: StorySimulationFormValues;
  charactersQuery: RefreshableQuery<Character[]>;
  templatesQuery: RefreshableQuery<WorldTemplate[]>;
  encyclopediasQuery: RefreshableQuery<WorldEncyclopedia[]>;
  loading: boolean;
  onChange: (patch: Partial<StorySimulationFormValues>) => void;
  onSubmit: (event: FormEvent<HTMLFormElement>) => void;
};

export default function StorySimulationForm({ values, charactersQuery, templatesQuery, encyclopediasQuery, loading, onChange, onSubmit }: Props) {
  const characters = charactersQuery.data ?? [];
  const templates = templatesQuery.data ?? [];
  const encyclopedias = encyclopediasQuery.data ?? [];
  const toggleCharacter = (id: number) => onChange({ character_ids: values.character_ids.includes(id) ? values.character_ids.filter((item) => item !== id) : [...values.character_ids, id] });

  return (
    <form className="page-card story-simulation-form" onSubmit={onSubmit} aria-busy={loading}>
      <div className="card-header"><h2>从一个故事背景开始</h2><span className="story-simulation-step">草稿自动保存</span></div>
      <div className="story-simulation-fields">
        <label className="story-simulation-field story-simulation-field-wide">故事背景与大致设定
          <textarea required minLength={2} value={values.premise} onChange={(event) => onChange({ premise: event.target.value })} placeholder="例如：现代社会，主角十八岁意外觉醒系统。写清主角处境、系统规则和你已有的关键设定即可。" rows={6} disabled={loading} />
        </label>
        <label className="story-simulation-field">开篇剧情走向（可选）
          <input value={values.direction} onChange={(event) => onChange({ direction: event.target.value })} placeholder="例如：先写觉醒当天，以及系统发布第一个任务" disabled={loading} />
        </label>
        <label className="story-simulation-field">叙事基调
          <input value={values.tone} onChange={(event) => onChange({ tone: event.target.value })} placeholder="例如：紧张但保留希望" disabled={loading} />
        </label>
        <label className="story-simulation-field">首次连续生成
          <select value={values.chapter_count} onChange={(event) => onChange({ chapter_count: Number(event.target.value) })} disabled={loading}>
            {[1, 2, 3].map((count) => <option value={count} key={count}>{count} 章</option>)}
          </select>
        </label>
        <div className="story-simulation-field">
          <label htmlFor="story-simulation-template">世界模板（可选）</label>
          {templatesQuery.isError && <InlineQueryError message="世界模板加载失败" error={templatesQuery.error} retrying={templatesQuery.isFetching} onRetry={() => { void templatesQuery.refetch(); }} />}
          {templatesQuery.isLoading ? <p className="story-simulation-muted">正在加载世界模板…</p> : !templatesQuery.isError && templates.length === 0 ? <p className="story-simulation-muted">暂无可绑定世界模板</p> : null}
          <select id="story-simulation-template" value={values.template_id} onChange={(event) => onChange({ template_id: event.target.value })} disabled={loading || templatesQuery.isLoading}>
            <option value="">不绑定模板</option>{templates.map((item) => <option value={item.template_id} key={item.template_id}>{item.label}</option>)}
          </select>
        </div>
        <div className="story-simulation-field">
          <label htmlFor="story-simulation-encyclopedia">百科库（可选）</label>
          {encyclopediasQuery.isError && <InlineQueryError message="百科列表加载失败" error={encyclopediasQuery.error} retrying={encyclopediasQuery.isFetching} onRetry={() => { void encyclopediasQuery.refetch(); }} />}
          {encyclopediasQuery.isLoading ? <p className="story-simulation-muted">正在加载百科库…</p> : !encyclopediasQuery.isError && encyclopedias.length === 0 ? <p className="story-simulation-muted">暂无可绑定百科库</p> : null}
          <select id="story-simulation-encyclopedia" value={values.encyclopedia_id} onChange={(event) => onChange({ encyclopedia_id: event.target.value })} disabled={loading || encyclopediasQuery.isLoading}>
            <option value="">不绑定百科</option>{encyclopedias.map((item) => <option value={item.id} key={item.id}>{item.name}</option>)}
          </select>
        </div>
      </div>
      <fieldset className="story-simulation-characters">
        <legend>参与角色（可选）</legend>
        {charactersQuery.isError && <InlineQueryError message="角色加载失败" error={charactersQuery.error} retrying={charactersQuery.isFetching} onRetry={() => { void charactersQuery.refetch(); }} />}
        {charactersQuery.isLoading ? <p className="story-simulation-muted">正在加载角色…</p> : !charactersQuery.isError && characters.length === 0 ? <p className="story-simulation-muted">暂无可绑定角色</p> : null}
        {characters.length > 0 && <div className="story-simulation-character-list">{characters.map((character) => <label key={character.id} className="story-simulation-character"><input type="checkbox" checked={values.character_ids.includes(character.id)} onChange={() => toggleCharacter(character.id)} disabled={loading} /> <span>{character.name}</span></label>)}</div>}
      </fieldset>
      <p className="story-simulation-draft-note">当前内容会保存在这台设备上，刷新或离开后仍可继续。</p>
      <p className="story-simulation-muted">生成后会直接进入创作会话。之后可继续输入剧情走向，或让小说作者连续续写。</p>
      <button className="btn btn-primary story-simulation-submit" type="submit" disabled={loading || !values.premise.trim()}>{loading ? '正在创作小说开篇…' : '生成小说并开始创作'}</button>
    </form>
  );
}
