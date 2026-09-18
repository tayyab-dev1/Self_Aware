"""Regenerate ``app/gateway/models.py`` from ``openapi/agent-gateway.json``.

The backend owns the request and response shapes; the AI layer never writes them by hand
(CLAUDE.md: "Do not let the two drift by hand"). Normally run through ``make contracts``,
which first re-exports the spec from the backend.
"""

from __future__ import annotations

import subprocess
import sys
from pathlib import Path

PROJECT_DIR = Path(__file__).resolve().parents[1]
SPEC_PATH = PROJECT_DIR.parent / "openapi" / "agent-gateway.json"
MODELS_PATH = PROJECT_DIR / "app" / "gateway" / "models.py"

HEADER = (
    "# GENERATED from openapi/agent-gateway.json by scripts/generate_gateway_models.py.\n"
    "# Do not edit by hand. Change the backend, then run `make contracts`."
)


def generate(output: Path = MODELS_PATH, spec: Path = SPEC_PATH) -> None:
    """Write Pydantic v2 models for every schema in the gateway spec to ``output``."""
    command = [
        sys.executable,
        "-m",
        "datamodel_code_generator",
        "--input",
        str(spec),
        "--input-file-type",
        "openapi",
        "--output",
        str(output),
        "--output-model-type",
        "pydantic_v2.BaseModel",
        "--target-python-version",
        "3.12",
        "--extra-fields",
        "forbid",
        "--use-standard-collections",
        "--use-union-operator",
        "--use-annotated",
        "--field-constraints",
        "--use-double-quotes",
        "--disable-timestamp",
        "--formatters",
        "builtin",
        "--custom-file-header",
        HEADER,
    ]
    subprocess.run(command, check=True, cwd=PROJECT_DIR)


if __name__ == "__main__":
    generate()
