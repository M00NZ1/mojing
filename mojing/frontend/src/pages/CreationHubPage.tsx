import { NavLink } from 'react-router-dom';
import UiIcon, { type UiIconName } from '../components/UiIcon';

const creationResources: ReadonlyArray<{
  to: string;
  icon: UiIconName;
  title: string;
  description: string;
}> = [
  {
    to: '/characters',
    icon: 'chat',
    title: '角色',
    description: '管理人设、形象、角色卡与对话参数。',
  },
  {
    to: '/encyclopedia',
    icon: 'book',
    title: '百科',
    description: '整理世界观、势力、地点、事件与关系。',
  },
  {
    to: '/workbench',
    icon: 'world',
    title: '设定工坊',
    description: '生成或导入世界设定，并管理可复用模板。',
  },
] as const;

export default function CreationHubPage() {
  return (
    <main className="page creation-hub-page">
      <header className="creation-hub-header">
        <div>
          <p className="eyebrow">创作中心</p>
          <h1>从设定到剧情，集中在一处</h1>
          <p className="creation-hub-summary">直接创作小说，或继续整理角色与世界资料。</p>
        </div>
        <NavLink to="/chat" replace className="btn btn-ghost creation-hub-exit">
          返回对话
        </NavLink>
      </header>
      <section aria-labelledby="creation-start-title">
        <h2 id="creation-start-title" className="creation-hub-section-title">开始创作</h2>
        <NavLink to="/story-simulation" className="creation-hub-card creation-hub-primary">
          <span className="creation-hub-icon" aria-hidden="true"><UiIcon name="edit" /></span>
          <span>
            <strong>小说创作</strong>
            <small>选择世界与角色，生成候选剧情并保存为可以继续对话的故事。</small>
          </span>
          <span className="creation-hub-arrow" aria-hidden="true">进入</span>
        </NavLink>
      </section>
      <section aria-labelledby="creation-resources-title">
        <h2 id="creation-resources-title" className="creation-hub-section-title">创作资料</h2>
        <div className="creation-hub-grid">
          {creationResources.map((section) => (
            <NavLink key={section.to} to={section.to} className="creation-hub-card">
              <span className="creation-hub-icon" aria-hidden="true"><UiIcon name={section.icon} /></span>
              <span>
                <strong>{section.title}</strong>
                <small>{section.description}</small>
              </span>
              <span className="creation-hub-arrow" aria-hidden="true">→</span>
            </NavLink>
          ))}
        </div>
      </section>
    </main>
  );
}
