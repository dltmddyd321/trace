"""이미 만들어진 템플릿 JSON을 원본 위에 다시 그려 눈으로 확인한다.

구조선(structures)을 손으로 채운 뒤 좌표가 실제 배경과 맞는지 볼 때 쓴다.

사용법:
    ./.venv/bin/python render.py out/pose05.json
"""

import json
import sys
from pathlib import Path

from extract import OUT_DIR, render_check

REFS_DIR = Path(__file__).parent / "refs"


def main() -> None:
    paths = [Path(arg) for arg in sys.argv[1:]]
    if not paths:
        raise SystemExit("템플릿 JSON 경로를 넘겨주세요")

    for path in paths:
        template = json.loads(path.read_text(encoding="utf-8"))
        image_path = REFS_DIR / template["source"]
        out_path = OUT_DIR / f"{path.stem}_check.png"
        render_check(image_path, template, out_path)
        print(f"{path.name}: 구조선 {len(template['structures'])}개 → {out_path.name}")


if __name__ == "__main__":
    main()
