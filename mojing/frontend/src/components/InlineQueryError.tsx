import { friendlyFetchError } from '../utils/userFacingError';

export type RefreshableQuery<T> = {
  data?: T;
  error?: unknown;
  isError: boolean;
  isFetching: boolean;
  isLoading: boolean;
  refetch: () => Promise<unknown>;
};

type Props = {
  message: string;
  error?: unknown;
  retrying?: boolean;
  onRetry: () => void;
};

export default function InlineQueryError({ message, error, retrying = false, onRetry }: Props) {
  return (
    <div className="inline-query-error" role="alert">
      <span>{message}{error ? `：${friendlyFetchError(error)}` : ''}</span>
      <button type="button" className="btn btn-ghost btn-sm" disabled={retrying} onClick={onRetry}>
        {retrying ? '重试中…' : '重试'}
      </button>
    </div>
  );
}
