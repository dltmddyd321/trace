"""레퍼런스 사진에서 사람 자세 라인과 실루엣을 뽑아 템플릿 JSON으로 만든다.

사용법:
    ./.venv/bin/python extract.py refs/pose05.jpg
    ./.venv/bin/python extract.py refs/*.jpg

각 이미지마다 out/<이름>.json 과 검증용 out/<이름>_check.png 를 남긴다.
배경 구조선(structures)은 비워둔 채 나오고, 사람이 보고 채운다.
"""

import json
import sys
from pathlib import Path

import cv2
import mediapipe as mp
import numpy as np
from mediapipe.tasks.python import BaseOptions
from mediapipe.tasks.python.vision import (
    PoseLandmarker,
    PoseLandmarkerOptions,
    RunningMode,
)

MODEL = Path(__file__).parent / "models" / "pose_landmarker_heavy.task"
OUT_DIR = Path(__file__).parent / "out"

# 이 값 아래로 떨어지는 관절은 모델이 가려진 부위를 추측한 것이라 가이드로 그리지 않는다.
VISIBILITY_MIN = 0.5

# 실루엣 윤곽을 몇 점까지 줄일지. 이미지 대각선 길이에 대한 비율로 준다.
SIMPLIFY_RATIO = 0.004

# 얼굴 세부 랜드마크. 눈·코·입 위치는 구도 가이드에 쓸모가 없고 선만 지저분해진다.
FACE_LANDMARKS = set(range(0, 11))

SKELETON_EDGES = [
    (11, 12), (11, 13), (13, 15), (12, 14), (14, 16),
    (11, 23), (12, 24), (23, 24),
    (23, 25), (25, 27), (24, 26), (26, 28),
]


def extract(image_path: Path) -> dict:
    bgr = cv2.imread(str(image_path))
    if bgr is None:
        raise SystemExit(f"이미지를 읽지 못했습니다: {image_path}")

    h, w = bgr.shape[:2]
    rgb = cv2.cvtColor(bgr, cv2.COLOR_BGR2RGB)
    mp_image = mp.Image(image_format=mp.ImageFormat.SRGB, data=rgb)

    options = PoseLandmarkerOptions(
        # macOS에서 기본 GPU 델리게이트가 Metal 초기화에 실패해 프로세스째 죽는다.
        # 오프라인 배치라 속도는 중요하지 않으므로 CPU로 고정한다.
        base_options=BaseOptions(
            model_asset_path=str(MODEL), delegate=BaseOptions.Delegate.CPU
        ),
        running_mode=RunningMode.IMAGE,
        output_segmentation_masks=True,
        num_poses=1,
    )
    with PoseLandmarker.create_from_options(options) as landmarker:
        result = landmarker.detect(mp_image)

    if not result.pose_landmarks:
        raise SystemExit(f"사람을 찾지 못했습니다: {image_path.name}")

    landmarks = result.pose_landmarks[0]

    joints = {}
    for idx, lm in enumerate(landmarks):
        if idx in FACE_LANDMARKS or lm.visibility < VISIBILITY_MIN:
            continue
        joints[str(idx)] = [round(lm.x, 4), round(lm.y, 4)]

    edges = [
        [a, b] for a, b in SKELETON_EDGES
        if str(a) in joints and str(b) in joints
    ]

    mask = result.segmentation_masks[0].numpy_view()
    silhouette = trace_silhouette(mask, w, h)

    xs = [p[0] for p in silhouette]
    ys = [p[1] for p in silhouette]
    box = [round(min(xs), 4), round(min(ys), 4), round(max(xs), 4), round(max(ys), 4)]

    # 인물이 프레임 끝에 닿으면 실루엣 자체가 잘려 있다는 뜻이라 배치 자산으로 못 쓴다.
    # 클로즈업 인물 사진이 검색 결과에 섞여 들어오는 걸 여기서 걸러낸다.
    if (box[2] - box[0]) >= 0.95 or (box[3] - box[1]) >= 0.98:
        print(f"  경고: {image_path.name}는 클로즈업이라 실루엣이 잘렸습니다. 자산에서 제외하세요")

    return {
        "id": image_path.stem,
        "category": "outdoor",
        "source": image_path.name,
        "person": {"silhouette": silhouette, "box": box, "joints": joints, "edges": edges},
        # 배경은 이미지를 직접 보고 채운다. 최대 3개.
        "structures": [],
        "hint": "",
    }


def trace_silhouette(mask: np.ndarray, w: int, h: int) -> list:
    """세그멘테이션 마스크에서 가장 큰 덩어리의 윤곽선을 정규화 좌표로 돌려준다."""
    binary = (mask > 0.5).astype(np.uint8) * 255

    # 옷 주름이나 머리카락 틈으로 생긴 작은 구멍을 메워 윤곽이 한 줄로 이어지게 한다.
    kernel = np.ones((9, 9), np.uint8)
    binary = cv2.morphologyEx(binary, cv2.MORPH_CLOSE, kernel)

    contours, _ = cv2.findContours(binary, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    if not contours:
        raise SystemExit("실루엣 윤곽을 찾지 못했습니다")

    contour = max(contours, key=cv2.contourArea)
    epsilon = SIMPLIFY_RATIO * float(np.hypot(w, h))
    simplified = cv2.approxPolyDP(contour, epsilon, closed=True)

    return [
        [round(float(p[0][0]) / w, 4), round(float(p[0][1]) / h, 4)]
        for p in simplified
    ]


def render_check(image_path: Path, template: dict, out_path: Path) -> None:
    """뽑은 좌표를 원본 위에 다시 그려서 눈으로 확인할 수 있게 한다."""
    bgr = cv2.imread(str(image_path))
    h, w = bgr.shape[:2]
    canvas = bgr.copy()

    def to_px(point):
        return int(point[0] * w), int(point[1] * h)

    silhouette = np.array(
        [to_px(p) for p in template["person"]["silhouette"]], dtype=np.int32
    )
    cv2.polylines(canvas, [silhouette], True, (255, 255, 255), 3)

    joints = template["person"]["joints"]
    for a, b in template["person"]["edges"]:
        cv2.line(canvas, to_px(joints[str(a)]), to_px(joints[str(b)]), (80, 200, 255), 3)
    for point in joints.values():
        cv2.circle(canvas, to_px(point), 6, (80, 200, 255), -1)

    for structure in template["structures"]:
        start, end = structure["line"]
        cv2.line(canvas, to_px(start), to_px(end), (120, 255, 120), 3)

    # 원본을 반쯤 비쳐 보이게 깔아야 선이 실제 피사체와 맞는지 판단할 수 있다.
    blended = cv2.addWeighted(canvas, 0.75, bgr, 0.25, 0)
    cv2.imwrite(str(out_path), blended)


def main() -> None:
    paths = [Path(arg) for arg in sys.argv[1:]]
    if not paths:
        raise SystemExit("이미지 경로를 넘겨주세요")

    OUT_DIR.mkdir(exist_ok=True)
    for path in paths:
        template = extract(path)
        json_path = OUT_DIR / f"{path.stem}.json"
        json_path.write_text(
            json.dumps(template, ensure_ascii=False, indent=2), encoding="utf-8"
        )
        render_check(path, template, OUT_DIR / f"{path.stem}_check.png")

        joints = len(template["person"]["joints"])
        points = len(template["person"]["silhouette"])
        print(f"{path.name}: 관절 {joints}/33, 실루엣 {points}점 → {json_path.name}")


if __name__ == "__main__":
    main()
