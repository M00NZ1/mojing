from fastapi import APIRouter, Depends, HTTPException, Request
from fastapi.responses import JSONResponse
from sqlalchemy.orm import Session

from ..database import get_db
from ..schemas import StoryGeneratedRecoveryRequest, StoryGenerationRequestStatus, StoryWritingRequest, StoryWritingResult
from ..services.story_simulation_service import GeneratedStoryNotSaved, StoryGenerationCancelled, create_story_session
from ..services.story_generation_draft import delete_generation_draft, read_request_status


router = APIRouter(prefix="/story-simulations", tags=["小说创作"])


def _unsaved_story_response(exc: GeneratedStoryNotSaved) -> JSONResponse:
    return JSONResponse(status_code=503, content={
        "detail": str(exc),
        "generated_story": {
            "version": 1,
            "request_id": exc.request_id,
            "text": exc.text,
            "payload": exc.payload,
            "draft_json": exc.draft_json,
            "context_text": exc.context_text,
        },
    })


@router.get("/requests/{request_id}", response_model=StoryGenerationRequestStatus)
def story_request_status(request_id: str, db: Session = Depends(get_db)):
    return read_request_status(db, request_id)


@router.delete("/requests/{request_id}/draft")
async def delete_story_request_draft(request_id: str, db: Session = Depends(get_db)):
    return await delete_generation_draft(db, request_id)


@router.post("/requests/{request_id}/save-generated", response_model=StoryWritingResult)
async def save_generated_story(
    request_id: str,
    recovery: StoryGeneratedRecoveryRequest,
    request: Request,
    db: Session = Depends(get_db),
):
    if recovery.payload.request_id != request_id:
        raise HTTPException(status_code=409, detail="正文与原请求编号不一致")
    try:
        return await create_story_session(
            db, recovery.payload, is_disconnected=request.is_disconnected,
            generated_recovery=recovery,
        )
    except GeneratedStoryNotSaved as exc:
        return _unsaved_story_response(exc)


@router.post("", response_model=StoryWritingResult)
async def create_story_simulation(payload: StoryWritingRequest, request: Request, db: Session = Depends(get_db)):
    try:
        return await create_story_session(db, payload, is_disconnected=request.is_disconnected)
    except StoryGenerationCancelled as exc:
        raise HTTPException(status_code=499, detail="小说生成已停止，草稿仍然保留。") from exc
    except GeneratedStoryNotSaved as exc:
        return _unsaved_story_response(exc)
    except HTTPException:
        raise
    except RuntimeError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
