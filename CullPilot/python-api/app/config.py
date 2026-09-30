from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    service_name: str = "cullpilot-python-api"
    host: str = "127.0.0.1"
    port: int = 8001
    storage_root: str = "../storage"
    # The repository ships an offline model inventory. Missing optional
    # packages or weights still degrade to the feature-only pipeline.
    analysis_enable_semantic: bool = True
    analysis_enable_deepface: bool = True
    analysis_enable_chinese_clip: bool = False
    analysis_model_root: str = ""

    llm_provider: str = "openai_compatible"
    llm_base_url: str = ""
    llm_api_key: str = ""
    llm_model: str = ""
    llm_timeout_seconds: int = 20

    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore",
    )


settings = Settings()
