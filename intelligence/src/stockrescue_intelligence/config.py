from typing import Literal

from pydantic import Field, IPvAnyAddress
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_prefix="STOCKRESCUE_INTELLIGENCE_", hide_input_in_errors=True
    )

    host: IPvAnyAddress = "127.0.0.1"
    port: int = Field(default=8000, ge=1024, le=65535)
    log_level: Literal["info", "warning", "error"] = "info"
