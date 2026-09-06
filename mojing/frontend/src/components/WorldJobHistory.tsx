import { useEffect, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useSearchParams } from 'react-router-dom';
import { api } from '../api/client';
import type { WorldGenerationResult } from '../types';
import InlineQueryError from './InlineQueryError';
import UiIcon from './UiIcon';
import './WorldJobHistory.css';

const statusNames: Record<string, string> = { succeeded: '已完成', failed: '生成失败', running: '生成中', pending: '等待开始', cancelled: '已停止' };

export default function WorldJobHistory({ onManage }: { onManage: (result: WorldGenerationResult) => void }) {
  const [params, setParams] = useSearchParams();
  const selected = Number(params.get('job')) || null;
  const [cursors, setCursors] = useState<(number | undefined)[]>([undefined]);
  const [lorePage, setLorePage] = useState(0);
  const client = useQueryClient();
  const cursor = cursors[cursors.length - 1];
  const history = useQuery({ queryKey: ['jobs', 'world', 'history', cursor], queryFn: () => api.worldJobHistory(cursor),
    refetchInterval: (query) => query.state.data?.items.some((job) => ['pending', 'running'].includes(job.status)) ? 5000 : false });
  const detail = useQuery({ queryKey: ['world-result', selected], queryFn: () => api.worldJobResult(selected!), enabled: selected !== null, gcTime: 0, retry: false });
  const save = useMutation({ mutationFn: (id: number) => api.saveWorldJobResult(id), onSuccess: (result, id) => {
    client.setQueryData(['world-result', id], result);
    void client.invalidateQueries({ queryKey: ['world-templates'] });
  } });
  const resetSave = save.reset;
  useEffect(() => { setLorePage(0); resetSave(); }, [selected, resetSave]);
  const selectJob = (id: number | null) => {
    const next = new URLSearchParams(params);
    if (id) next.set('job', String(id)); else next.delete('job');
    setParams(next);
    setLorePage(0);
    save.reset();
  };
  const result = detail.data;
  return <section className="page-card world-history" aria-label="生成记录">
    <div className="card-header">
      <div><p className="eyebrow">世界创作</p><h2>{selected ? '生成结果' : '生成记录'}</h2></div>
      <button type="button" className="btn btn-ghost btn-sm" onClick={() => selected ? selectJob(null) : void history.refetch()}>{selected ? '返回记录' : '刷新记录'}</button>
    </div>
    {!selected && <>
      <p className="hint">完整结果会保留在本机。保存到世界库后，就能用于新的故事。</p>
      {history.isLoading && <p role="status">正在读取记录…</p>}
      {history.isError && <InlineQueryError message="记录读取失败" error={history.error} retrying={history.isFetching} onRetry={() => void history.refetch()} />}
      {history.isSuccess && !history.data.items.length && <div className="world-history-empty"><UiIcon name="world" /><h3>还没有生成记录</h3><p>生成世界或从文本整理后，结果会出现在这里。</p></div>}
      <div className="world-history-list">{history.data?.items.map((job) => <article className="world-history-row" key={job.id}>
        <div><span className={`world-job-status world-job-${job.status}`}>{statusNames[job.status] || '状态未知'}</span><h3>{job.label || '世界创作'}</h3><p>{job.job_type === 'world_import' ? '文本整理' : '世界生成'} · {new Date(job.created_at).toLocaleString('zh-CN')}</p>
          {job.status === 'failed' && <p className="world-history-error">{job.error_message || '生成未完成，请返回创作页重试。'}</p>}
          {job.status === 'running' && <p className="hint">结果完成后会自动更新；异常退出的旧任务可能需要重新生成。</p>}
          {job.status === 'succeeded' && job.result_version !== 1 && <p className="hint">旧记录未保留完整结果；已保存的内容可在“我的世界”查看。</p>}
        </div>
        {job.result_version === 1 && <button type="button" className="btn btn-ghost btn-sm" onClick={() => selectJob(job.id)}>查看结果</button>}
      </article>)}</div>
      <div className="button-row world-history-pagination"><button type="button" className="btn btn-ghost btn-sm" disabled={cursors.length === 1 || history.isFetching} onClick={() => setCursors((current) => current.slice(0, -1))}>较新记录</button><span>第 {cursors.length} 页</span><button type="button" className="btn btn-ghost btn-sm" disabled={!history.data?.next_cursor || history.isFetching} onClick={() => setCursors((current) => [...current, history.data!.next_cursor!])}>更早记录</button></div>
    </>}
    {selected && <>
      {detail.isLoading && <p role="status">正在读取完整结果…</p>}
      {detail.isError && <InlineQueryError message="结果读取失败" error={detail.error} retrying={detail.isFetching} onRetry={() => void detail.refetch()} />}
      {result && <div className="world-history-result">
        <span className="world-job-status world-job-succeeded">{result.saved_template ? '已保存到世界库' : '结果已保留 · 尚未加入世界库'}</span>
        <h3>{result.template.label}</h3><p>{result.template.summary}</p>
        <div className="button-row">{result.saved_template ? <button type="button" className="btn btn-primary" onClick={() => onManage(result)}>管理此世界</button> : <button type="button" className="btn btn-primary" disabled={save.isPending} onClick={() => save.mutate(selected)}>{save.isPending ? '正在保存…' : '保存到世界库'}</button>}</div>
        {save.isError && <InlineQueryError message="保存失败，结果仍在记录中" error={save.error} retrying={save.isPending} onRetry={() => save.mutate(selected)} />}
        <h4>世界设定</h4><div className="world-history-body">{result.template.world_prompt}</div>
        <h4>世界条目 · {result.lore_entries.length}</h4>
        {result.lore_entries.slice(lorePage * 20, (lorePage + 1) * 20).map((entry, index) => <details className="world-history-lore" key={`${lorePage}-${index}`}><summary>{entry.title}</summary><div className="world-history-body">{entry.content}</div></details>)}
        {result.lore_entries.length > 20 && <div className="button-row"><button className="btn btn-ghost btn-sm" disabled={lorePage === 0} onClick={() => setLorePage((page) => page - 1)}>上一页条目</button><button className="btn btn-ghost btn-sm" disabled={(lorePage + 1) * 20 >= result.lore_entries.length} onClick={() => setLorePage((page) => page + 1)}>下一页条目</button></div>}
        <details className="world-history-lore"><summary>质量与命名参考</summary><p>{result.quality_report.verdict} · {result.quality_report.score} 分</p><p>{result.quality_report.risks.join('；')}</p><p>人名：{result.names.person_names.join('、') || '无'}</p><p>地名：{result.names.place_names.join('、') || '无'}</p><p>物品：{result.names.item_names.join('、') || '无'}</p></details>
      </div>}
    </>}
  </section>;
}
