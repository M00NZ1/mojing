import { NavLink } from 'react-router-dom';

export default function CreationHomeLink({ compact = false }: { compact?: boolean }) {
  return (
    <NavLink
      to="/create"
      replace
      className={compact ? 'btn-icon creation-home-link creation-home-link-compact' : 'btn btn-ghost creation-home-link'}
      title="返回创作中心"
      aria-label={compact ? '返回创作中心' : undefined}
    >
      {compact ? '←' : '返回创作'}
    </NavLink>
  );
}
