import uvicorn

from stockrescue_intelligence.config import Settings


def main() -> None:
    settings = Settings()
    uvicorn.run(
        "stockrescue_intelligence.app:create_app",
        factory=True,
        host=str(settings.host),
        port=settings.port,
        log_level=settings.log_level,
        access_log=False,
        limit_concurrency=32,
        timeout_keep_alive=5,
    )
