import { useEffect } from 'react';
import { useRouteError } from 'react-router-dom';

import { AppRecoveryPage } from './ErrorBoundary';

export default function RouteErrorPage() {
  const error = useRouteError();

  useEffect(() => {
    console.error('[RouteErrorBoundary]', error);
  }, [error]);

  return <AppRecoveryPage />;
}
