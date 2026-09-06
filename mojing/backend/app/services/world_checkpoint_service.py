"""Persistent world steps in JobRun; an OS lock owns each live request."""
import asyncio
import inspect
import os
from contextlib import contextmanager
from contextvars import ContextVar
from datetime import datetime
from pathlib import Path

from pydantic import TypeAdapter
from sqlalchemy import func, select, update

from ..models import JobRunModel
from ..schemas import WorldGenerationRequest, WorldImportRequest
from .job_service import create_job_run, mark_job_cancelled
from .world_request_control import run_world_request

_current = ContextVar('world_checkpoint', default=None)
ACTIVE = ('running', 'pause_requested')
RESUMABLE = ('pending', 'paused', 'interrupted', 'failed')


class WorldPaused(Exception):
    pass


class WorldAlreadyRunning(ValueError):
    pass


@contextmanager
def world_job_lock(db, job_id):
    # Never unlink lock files: removing a held file can create two owners.
    directory = db.info.get('world_job_lock_dir')
    if directory is None:
        database = db.get_bind().url.database
        if not database or database == ':memory:':
            raise ValueError('检查点任务需要本机文件数据库')
        database_path = Path(database).resolve()
        directory = database_path.parent / f'.{database_path.name}.world-job-locks'
    directory = Path(directory)
    directory.mkdir(parents=True, exist_ok=True)
    with (directory / f'{int(job_id)}.lock').open('a+b') as handle:
        if handle.seek(0, 2) == 0:
            handle.write(b'0')
            handle.flush()
        handle.seek(0)
        try:
            if os.name == 'nt':
                import msvcrt
                msvcrt.locking(handle.fileno(), msvcrt.LK_NBLCK, 1)
            else:
                import fcntl
                fcntl.flock(handle.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        except OSError as exc:
            raise WorldAlreadyRunning('这条记录仍在运行，请等待或暂停当前生成') from exc
        try:
            yield
        finally:
            handle.seek(0)
            if os.name == 'nt':
                msvcrt.locking(handle.fileno(), msvcrt.LK_UNLCK, 1)
            else:
                fcntl.flock(handle.fileno(), fcntl.LOCK_UN)


def recover_world_jobs(db, job_id=None):
    job = JobRunModel
    stmt = select(job.id).where(job.scope == 'world', job.job_type.in_(('world_generate', 'world_import')),
        job.status.in_(ACTIVE), func.json_extract(job.input_json, '$.world_request_version') == 1)
    if job_id is not None:
        stmt = stmt.where(job.id == job_id)
    # Inspect bounded ID pages, without materializing source text or results.
    cursor = 0
    while ids := list(db.scalars(stmt.where(job.id > cursor).order_by(job.id).limit(100))):
        for key in ids:
            try:
                with world_job_lock(db, key):
                    db.execute(update(job).where(job.id == key, job.status.in_(ACTIVE)).values(
                        status='interrupted', finished_at=datetime.utcnow(),
                        error_message='上次执行已中断，已保存步骤保留，可继续生成。'))
                    db.commit()
            except WorldAlreadyRunning:
                pass
        cursor = ids[-1]


def prepare_world_job(db, operation, payload):
    schema = WorldGenerationRequest if operation == 'generate' else WorldImportRequest if operation == 'import' else None
    if schema is None:
        raise ValueError('不支持的世界创作类型')
    request = schema.model_validate(payload)
    values = request.model_dump(mode='json')
    row = create_job_run(db, job_type=f'world_{operation}', scope='world', input_json={
        'world_request_version': 1, 'world_request': values,
        **{key: values[key] for key in ('label', 'world_type', 'source_filename') if key in values}})
    return {'id': row.id, 'status': row.status}


def _read_request(db, job_id):
    row = db.get(JobRunModel, job_id)
    if row is None or row.scope != 'world' or row.job_type not in ('world_generate', 'world_import'):
        raise LookupError('生成记录不存在')
    data = row.input_json or {}
    if data.get('world_request_version') != 1:
        raise ValueError('旧记录没有续跑输入，请重新创建；原记录保留')
    schema = WorldGenerationRequest if row.job_type == 'world_generate' else WorldImportRequest
    return row, schema.model_validate(data.get('world_request'))


def world_job_progress(db, job_id):
    recover_world_jobs(db, job_id)
    job = JobRunModel
    row = db.execute(select(job.id, job.status,
        func.json_extract(job.input_json, '$.world_request_version').label('request_version'),
        func.json_extract(job.output_json, '$.completed_steps').label('completed_steps'),
        func.json_extract(job.output_json, '$.stage_label').label('stage_label'))
        .where(job.id == job_id, job.scope == 'world', job.job_type.in_(('world_generate', 'world_import')))).mappings().first()
    if row is None:
        raise LookupError('生成记录不存在')
    return {**row, 'can_resume': row['request_version'] == 1 and row['status'] in RESUMABLE}


def pause_world_job(db, job_id):
    _read_request(db, job_id)
    db.execute(update(JobRunModel).where(JobRunModel.id == job_id, JobRunModel.status == 'running')
        .values(status='pause_requested'))
    db.commit()
    return world_job_progress(db, job_id)


class Checkpoints:
    def __init__(self, db, row):
        self.db, self.job_id = db, row.id
        self.output = dict(row.output_json or {})
        if self.output and self.output.get('world_checkpoint_version') != 1:
            raise ValueError('检查点版本不支持，原记录已保留')
        self.steps = dict(self.output.get('world_steps') or {})

    def check_pause(self):
        state = self.db.scalar(select(JobRunModel.status).where(JobRunModel.id == self.job_id))
        if state == 'pause_requested':
            self.db.execute(update(JobRunModel).where(JobRunModel.id == self.job_id, JobRunModel.status == 'pause_requested')
                .values(status='paused', finished_at=datetime.utcnow()))
            self.db.commit()
            raise WorldPaused()
        if state != 'running':
            raise asyncio.CancelledError()

    async def step(self, name, value_type, build):
        self.check_pause()
        adapter = TypeAdapter(value_type)
        if name in self.steps:
            return adapter.validate_python(self.steps[name])
        value = build()
        if inspect.isawaitable(value):
            value = await value
        encoded = adapter.dump_python(adapter.validate_python(value), mode='json')
        self.steps[name] = encoded
        self.output = {**self.output, 'world_checkpoint_version': 1, 'world_steps': self.steps,
            'completed_steps': len(self.steps), 'stage_label': name}
        self.db.execute(update(JobRunModel).where(JobRunModel.id == self.job_id, JobRunModel.status.in_(ACTIVE))
            .values(output_json=self.output))
        self.db.commit()
        self.check_pause()
        return value


def has_world_checkpoints():
    return _current.get() is not None


async def world_step(name, value_type, build):
    current = _current.get()
    if current:
        return await current.step(name, value_type, build)
    value = build()
    return await value if inspect.isawaitable(value) else value


async def run_checkpoint_world_job(db, job_id, is_disconnected=None):
    from ..models import CharacterModel
    from .llm_client import resolve_text_config
    from .world_building_service import generate_world_package, import_world_package
    from .world_job_service import complete_world_job, save_world_job_result
    with world_job_lock(db, job_id):
        db.expire_all()
        row, payload = _read_request(db, job_id)
        if row.status not in (*RESUMABLE, *ACTIVE):
            raise ValueError('此记录已结束，请查看结果或创建新的任务')
        checkpoints = Checkpoints(db, row)
        row.status = 'running'
        row.started_at = row.started_at or datetime.utcnow()
        row.finished_at = None
        row.error_message = ''
        db.commit()
        token = _current.set(checkpoints)
        previous = db.info.get('text_config_override')
        try:
            if payload.character_id:
                character = db.get(CharacterModel, payload.character_id)
                if not character:
                    raise ValueError('所选角色已删除，请新建生成任务并重新选择')
                resolved = resolve_text_config(character, db)
                if not resolved.api_key or not resolved.base_url or not resolved.model:
                    raise ValueError('所选模型线路缺少 Key、地址或模型名，请补全后继续')
                db.info['text_config_override'] = resolved
            build = generate_world_package if row.job_type == 'world_generate' else import_world_package
            result = await run_world_request(lambda: build(db, payload.model_copy(update={'auto_save': False})), is_disconnected)
            complete_world_job(db, job_id, result)
            if payload.auto_save:
                db.rollback()
                result = save_world_job_result(db, job_id)
            return {'status': 'succeeded', 'result': result}
        except WorldPaused:
            return {'status': 'paused', 'result': None}
        except asyncio.CancelledError:
            db.rollback()
            mark_job_cancelled(db, job_id)
            raise
        except Exception:
            db.rollback()
            db.execute(update(JobRunModel).where(JobRunModel.id == job_id, JobRunModel.status.in_(ACTIVE)).values(
                status='failed', finished_at=datetime.utcnow(), error_message='执行未完成，已保存步骤保留；请检查模型配置后继续。'))
            db.commit()
            raise
        finally:
            _current.reset(token)
            if previous is None: db.info.pop('text_config_override', None)
            else: db.info['text_config_override'] = previous
