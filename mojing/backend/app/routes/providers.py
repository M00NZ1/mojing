from fastapi import APIRouter, Depends

from ..schemas import ProviderPreset
from ..services.provider_catalog import PROVIDER_CATALOG



router = APIRouter(prefix="/providers", tags=["提供商"])


@router.get("/catalog", response_model=list[ProviderPreset])
def get_provider_catalog():
    return PROVIDER_CATALOG
