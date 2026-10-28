from contextlib import asynccontextmanager
from typing import Literal

from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, ConfigDict, Field

from stockrescue_intelligence.config import Settings


class ServiceHealth(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)

    contract_version: Literal["1"] = Field(alias="contractVersion")
    service: Literal["stockrescue-intelligence"]
    status: Literal["UP"]


HEALTH = ServiceHealth.model_validate(
    {"contractVersion": "1", "service": "stockrescue-intelligence", "status": "UP"}
)


@asynccontextmanager
async def lifespan(application: FastAPI):
    application.state.settings = Settings()
    application.state.ready = True
    try:
        yield
    finally:
        application.state.ready = False


def create_app() -> FastAPI:
    application = FastAPI(
        title="StockRescue private technical service",
        docs_url=None,
        redoc_url=None,
        openapi_url=None,
        lifespan=lifespan,
    )
    application.state.ready = False

    @application.get("/health/live", response_model=ServiceHealth)
    def live() -> ServiceHealth:
        return HEALTH

    @application.get("/health/ready", response_model=ServiceHealth)
    def ready() -> ServiceHealth:
        if not application.state.ready:
            raise HTTPException(status_code=503, detail="Service startup is incomplete.")
        return HEALTH

    return application
