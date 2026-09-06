"""Request-scoped cancellation; no detached worker or second task owner."""
import asyncio


async def run_world_request(build, is_disconnected=None):
    if is_disconnected is not None and await is_disconnected():
        raise asyncio.CancelledError()
    task = asyncio.create_task(build())
    try:
        while True:
            done, _ = await asyncio.wait({task}, timeout=0.1)
            # Check again even when the result wins the same scheduling tick.
            if is_disconnected is not None and await is_disconnected():
                raise asyncio.CancelledError()
            if task in done:
                return task.result()
    finally:
        if not task.done():
            task.cancel()
        await asyncio.gather(task, return_exceptions=True)
