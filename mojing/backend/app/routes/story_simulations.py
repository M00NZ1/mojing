from fastapi import APIRouter, Depends, HTTPException, Request
from sqlalchemy.orm import Session

from ..database import get_db
from ..schemas import StoryWritingRequest, StoryWritingResult
from ..services.story_simulation_service import StoryGenerationCancelled, create_story_session


router = APIRouter(prefix="/story-simulations", tags=["小说创作"])


@router.post("", response_model=StoryWritingResult)
async def create_story_simulation(payload: StoryWritingRequest, request: Request, db: Session = Depends(get_db)):
    try:
        return await create_story_session(db, payload, is_disconnected=request.is_disconnected)
    except StoryGenerationCancelled as exc:
        raise HTTPException(status_code=499, detail="小说生成已停止，草稿仍然保留。") from exc
    except HTTPException:
        raise
    except RuntimeError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
