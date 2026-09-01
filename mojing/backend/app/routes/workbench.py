from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.orm import Session

from ..database import get_db
from ..schemas import PromptRewriteRequest, PromptRewriteResponse
from ..services.chat_service import rewrite_prompt_text



router = APIRouter(prefix="/workbench", tags=["设定工坊"])


@router.post("/rewrite", summary="改写 Prompt", response_model=PromptRewriteResponse)
def rewrite_prompt(payload: PromptRewriteRequest, db: Session = Depends(get_db)):
    try:
        result = rewrite_prompt_text(db, payload)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    return {"result": result}
