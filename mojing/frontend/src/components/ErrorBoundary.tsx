import { Component, useEffect, useRef, type ErrorInfo, type ReactNode } from 'react';
import UiIcon from './UiIcon';

type Props = {
  children: ReactNode;
  fallback?: ReactNode;
};

type State = {
  hasError: boolean;
  error: Error | null;
};

export function AppRecoveryPage() {
  const titleRef = useRef<HTMLHeadingElement>(null);

  useEffect(() => {
    titleRef.current?.focus();
  }, []);

  return (
    <main className="error-boundary-page" role="alert" aria-labelledby="fatal-error-title" aria-describedby="fatal-error-description">
      <section className="error-boundary-card">
        <UiIcon name="warning" className="error-boundary-icon" />
        <h1 id="fatal-error-title" ref={titleRef} tabIndex={-1} className="error-boundary-title">
          页面暂时无法显示
        </h1>
        <p id="fatal-error-description" className="error-boundary-copy">
          本机界面未能完成加载。可以重新加载，或返回对话主页继续操作。
        </p>
        <div className="error-boundary-actions">
          <button type="button" className="btn btn-primary" onClick={() => window.location.reload()}>
            重新加载页面
          </button>
          <button type="button" className="btn btn-ghost" onClick={() => window.location.assign('/chat')}>
            返回对话主页
          </button>
        </div>
      </section>
    </main>
  );
}

export default class ErrorBoundary extends Component<Props, State> {
  state: State = { hasError: false, error: null };

  static getDerivedStateFromError(error: Error): State {
    return { hasError: true, error };
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    console.error('[ErrorBoundary]', error, info.componentStack);
  }

  render() {
    if (this.state.hasError) {
      return this.props.fallback || <AppRecoveryPage />;
    }
    return this.props.children;
  }
}
