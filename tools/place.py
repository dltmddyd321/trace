"""배경 사진 위에 포즈 실루엣을 지정한 위치·크기로 얹어본다.

"AI가 배경을 보고 사람을 어디에 세울지 정한다"는 방식이 실제로 말이 되는지
눈으로 확인하기 위한 검증 도구다.

사용법:
    ./.venv/bin/python place.py refs/bg01.jpg out/pose05.json 0.30 0.42 0.52 0.99
                                 배경        포즈자산        x0   y0   x1   y1
"""

import json
import sys
from pathlib import Path

import cv2
import numpy as np

OUT_DIR = Path(__file__).parent / "out"


def fit_box(source_box: list, target_box: list) -> tuple:
    """원본 실루엣 박스를 목표 박스에 넣기 위한 배율과 이동량을 구한다.

    가로세로 비율은 유지한다 — 사람을 늘리거나 눌러 놓으면 따라 설 수가 없다.
    """
    sx0, sy0, sx1, sy1 = source_box
    tx0, ty0, tx1, ty1 = target_box

    scale = min((tx1 - tx0) / (sx1 - sx0), (ty1 - ty0) / (sy1 - sy0))

    # 비율 유지로 남는 여백은 가로는 가운데, 세로는 아래(발끝)에 맞춘다.
    # 사람은 바닥에 서 있으므로 발 위치가 머리 위 여백보다 중요하다.
    width = (sx1 - sx0) * scale
    dx = tx0 + ((tx1 - tx0) - width) / 2 - sx0 * scale
    dy = ty1 - sy1 * scale

    return scale, dx, dy


def main() -> None:
    if len(sys.argv) != 7:
        raise SystemExit(__doc__)

    bg_path = Path(sys.argv[1])
    asset_path = Path(sys.argv[2])
    target_box = [float(v) for v in sys.argv[3:7]]

    bgr = cv2.imread(str(bg_path))
    if bgr is None:
        raise SystemExit(f"배경을 읽지 못했습니다: {bg_path}")
    h, w = bgr.shape[:2]

    asset = json.loads(asset_path.read_text(encoding="utf-8"))
    scale, dx, dy = fit_box(asset["person"]["box"], target_box)

    def place(point):
        return int((point[0] * scale + dx) * w), int((point[1] * scale + dy) * h)

    canvas = bgr.copy()

    silhouette = np.array(
        [place(p) for p in asset["person"]["silhouette"]], dtype=np.int32
    )
    # 실루엣 안쪽을 옅게 채워야 "사람이 설 자리"로 읽힌다. 선만 있으면 도형처럼 보인다.
    fill = canvas.copy()
    cv2.fillPoly(fill, [silhouette], (255, 255, 255))
    canvas = cv2.addWeighted(fill, 0.35, canvas, 0.65, 0)
    cv2.polylines(canvas, [silhouette], True, (255, 255, 255), 3)

    joints = asset["person"]["joints"]
    for a, b in asset["person"]["edges"]:
        cv2.line(canvas, place(joints[str(a)]), place(joints[str(b)]), (80, 200, 255), 3)

    out_path = OUT_DIR / f"{bg_path.stem}_placed.png"
    cv2.imwrite(str(out_path), canvas)
    print(f"{bg_path.name} + {asset_path.stem} → {out_path.name} (scale {scale:.3f})")


if __name__ == "__main__":
    main()
