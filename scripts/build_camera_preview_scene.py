#!/usr/bin/env python3
"""Prepare and verify one fixed registered camera-preview scene pyramid."""

from __future__ import annotations

import argparse
import hashlib
import io
import json
import math
import os
import re
import sys
import tempfile
import warnings
from pathlib import Path

try:
    import PIL
    from PIL import Image, ImageChops, ImageDraw, ImageFont, features
except ModuleNotFoundError as exc:  # pragma: no cover - only without tooling
    raise SystemExit(
        "Pillow is required. Install scripts/requirements-moon-texture.txt "
        "in a temporary virtual environment."
    ) from exc


PINNED_PILLOW_VERSION = "12.3.0"
LEVEL_COUNT = 6
MAX_SCALE_STEP = 3.7
MIN_CUMULATIVE_RATIO = 625.0
GEOMETRY_TOLERANCE_PIXELS = 1.0
WEBP_OPTIONS = {"lossless": True, "quality": 100, "method": 6, "exact": True}
LEVEL_ID_PATTERN = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]*\Z")
TEXT_FONT = ImageFont.load_default(size=24)


class SceneToolError(RuntimeError):
    """Raised when an authoring input does not satisfy the fixed contract."""


def _number(value: object, label: str) -> float:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise SceneToolError(f"{label} must be a number.")
    result = float(value)
    if not math.isfinite(result):
        raise SceneToolError(f"{label} must be finite.")
    return result


def _integer(value: object, label: str) -> int:
    if isinstance(value, bool) or not isinstance(value, int):
        raise SceneToolError(f"{label} must be an integer.")
    return value


def _anchor(level: dict) -> tuple[float, float]:
    raw = level.get("anchor")
    if not isinstance(raw, dict):
        raise SceneToolError(f"Level {level.get('id')} anchor must be an object.")
    values = (_number(raw.get("x"), "anchor.x"), _number(raw.get("y"), "anchor.y"))
    if any(value < 0.0 or value > 1.0 for value in values):
        raise SceneToolError(f"Level {level.get('id')} anchor must stay within 0..1.")
    return values


def _dimensions(level: dict) -> tuple[int, int]:
    level_id = level.get("id")
    width = _integer(level.get("width"), f"Level {level_id} width")
    height = _integer(level.get("height"), f"Level {level_id} height")
    if width < 960 or height * 4 < width * 3:
        raise SceneToolError(
            f"Level {level_id} must be at least 960 pixels wide and 75% as tall."
        )
    return width, height


def load_manifest(path: Path) -> dict:
    try:
        manifest = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as exc:
        raise SceneToolError(f"Cannot read manifest {path}: {exc}") from exc
    if not isinstance(manifest, dict):
        raise SceneToolError("Manifest must be a JSON object.")
    if _integer(manifest.get("schemaVersion"), "Manifest schemaVersion") != 1:
        raise SceneToolError("Manifest schemaVersion must be 1.")
    levels = manifest.get("levels")
    if not isinstance(levels, list) or len(levels) != LEVEL_COUNT:
        raise SceneToolError(f"Manifest must contain exactly {LEVEL_COUNT} levels.")

    root_anchor: tuple[float, float] | None = None
    identifiers: set[str] = set()
    for index, level in enumerate(levels):
        if not isinstance(level, dict):
            raise SceneToolError(f"Level {index} must be an object.")
        level_id = level.get("id")
        if not isinstance(level_id, str) or not LEVEL_ID_PATTERN.fullmatch(level_id):
            raise SceneToolError(f"Level {index} has an invalid id.")
        if level_id in identifiers:
            raise SceneToolError(f"Level id {level_id} is duplicated.")
        identifiers.add(level_id)
        width, height = _dimensions(level)
        world_width = _number(level.get("worldWidthMetres"), f"Level {level_id} world width")
        if world_width <= 0:
            raise SceneToolError(f"Level {level_id} world width must be positive.")
        anchor = _anchor(level)
        if root_anchor is None:
            root_anchor = anchor
        elif anchor != root_anchor:
            raise SceneToolError("Every level must declare the same normalized anchor.")

        if index == 0:
            if level.get("parent") is not None or level.get("parentCrop") is not None:
                raise SceneToolError("The first level must not declare a parent or crop.")
            continue
        parent = levels[index - 1]
        if level.get("parent") != parent["id"]:
            raise SceneToolError(f"Level {level_id} must name {parent['id']} as its parent.")
        crop = level.get("parentCrop")
        if not isinstance(crop, list) or len(crop) != 4:
            raise SceneToolError(f"Level {level_id} parentCrop must contain four integers.")
        left, top, right, bottom = (
            _integer(value, f"Level {level_id} parentCrop") for value in crop
        )
        parent_width, parent_height = _dimensions(parent)
        if left < 0 or top < 0 or right > parent_width or bottom > parent_height:
            raise SceneToolError(f"Level {level_id} parentCrop is out of bounds.")
        crop_width, crop_height = right - left, bottom - top
        if crop_width <= 0 or crop_height <= 0:
            raise SceneToolError(f"Level {level_id} parentCrop must have positive area.")
        parent_world = _number(parent["worldWidthMetres"], "Parent world width")
        scale = parent_world / world_width
        if scale <= 1.0 or scale > MAX_SCALE_STEP:
            raise SceneToolError(f"Level {level_id} scale step {scale:.6g} must be >1 and <={MAX_SCALE_STEP:g}.")
        if abs(crop_width - parent_width / scale) > GEOMETRY_TOLERANCE_PIXELS:
            raise SceneToolError(f"Level {level_id} crop width conflicts with its world width.")
        if abs(crop_height - crop_width * height / width) > GEOMETRY_TOLERANCE_PIXELS:
            raise SceneToolError(f"Level {level_id} crop aspect ratio would distort the guide.")
        parent_anchor = _anchor(parent)
        anchor_error = (
            abs(parent_anchor[0] * parent_width - (left + anchor[0] * crop_width)),
            abs(parent_anchor[1] * parent_height - (top + anchor[1] * crop_height)),
        )
        if max(anchor_error) > GEOMETRY_TOLERANCE_PIXELS:
            raise SceneToolError(f"Level {level_id} crop moves the shared contact anchor.")

    declared_ratio = _number(
        manifest.get("cumulativeWorldWidthRatio"), "cumulativeWorldWidthRatio"
    )
    computed_ratio = float(levels[0]["worldWidthMetres"]) / float(
        levels[-1]["worldWidthMetres"]
    )
    if declared_ratio < MIN_CUMULATIVE_RATIO or computed_ratio < MIN_CUMULATIVE_RATIO:
        raise SceneToolError(f"Declared and endpoint world-width ratios must both be at least {MIN_CUMULATIVE_RATIO:g}.")
    if not math.isclose(declared_ratio, computed_ratio, rel_tol=1e-6, abs_tol=1e-6):
        raise SceneToolError("Declared cumulative ratio does not match the endpoint world widths.")
    return manifest


def _level(manifest: dict, level_id: str) -> dict:
    for level in manifest["levels"]:
        if level["id"] == level_id:
            return level
    raise SceneToolError(f"Manifest does not contain level {level_id}.")


def _toolchain(require_webp: bool = False) -> str:
    if PIL.__version__ != PINNED_PILLOW_VERSION:
        raise SceneToolError(
            f"Pillow {PINNED_PILLOW_VERSION} is required, got {PIL.__version__}."
        )
    codec = features.version("webp")
    if require_webp and (not features.check("webp") or codec is None):
        raise SceneToolError("This Pillow installation does not provide WebP support.")
    return codec or "unavailable"


def _load_rgba(path: Path, chroma_key: tuple[int, int, int] | None = None,
               required_format: str | None = None) -> Image.Image:
    with warnings.catch_warnings():
        warnings.simplefilter("error", Image.DecompressionBombWarning)
        with Image.open(path) as source:
            source.load()
            if required_format is not None and source.format != required_format:
                raise SceneToolError(f"{path} must be a {required_format} image.")
            has_alpha = "A" in source.getbands() or "transparency" in source.info
            image = source.convert("RGBA")
    if has_alpha:
        return image
    if chroma_key is None:
        raise SceneToolError(f"{path} has no alpha channel; declare a flat chroma key.")
    rgb = image.convert("RGB")
    difference = ImageChops.difference(rgb, Image.new("RGB", rgb.size, chroma_key))
    red, green, blue = difference.split()
    alpha = ImageChops.lighter(ImageChops.lighter(red, green), blue).point(
        lambda value: 0 if value == 0 else 255
    )
    image.putalpha(alpha)
    return image


def _encode(image: Image.Image, image_format: str) -> bytes:
    output = io.BytesIO()
    if image_format == "WEBP":
        image.save(output, format="WEBP", **WEBP_OPTIONS)
    else:
        image.save(output, format="PNG", compress_level=9)
    return output.getvalue()


def _require_alpha_webp(data: bytes) -> None:
    with Image.open(io.BytesIO(data)) as encoded:
        encoded.load()
        if encoded.format != "WEBP" or "A" not in encoded.getbands():
            raise SceneToolError("The candidate must contain an alpha channel in WebP format.")


def _atomic_write(path: Path, data: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary_name = tempfile.mkstemp(prefix=f".{path.name}.", dir=path.parent)
    temporary_path = Path(temporary_name)
    try:
        with os.fdopen(descriptor, "wb") as output:
            output.write(data)
        os.replace(temporary_path, path)
    finally:
        temporary_path.unlink(missing_ok=True)


def _registered_child_view(parent_image: Image.Image, child: dict) -> Image.Image:
    guide = parent_image.crop(tuple(child["parentCrop"])).resize(
        _dimensions(child), Image.Resampling.LANCZOS
    )
    alpha = guide.getchannel("A").point(lambda value: 255 if value >= 128 else 0)
    guide.putalpha(alpha)
    return guide


def prepare(manifest_path: Path, level_id: str, parent_path: Path, output: Path,
            chroma_key: tuple[int, int, int] | None = None) -> None:
    _toolchain()
    manifest = load_manifest(manifest_path)
    child = _level(manifest, level_id)
    if child["parent"] is None:
        raise SceneToolError("prepare requires a child level.")
    parent = _level(manifest, child["parent"])
    parent_image = _load_rgba(parent_path, chroma_key)
    if parent_image.size != _dimensions(parent):
        raise SceneToolError(f"Parent image dimensions do not match level {parent['id']}.")
    guide = _registered_child_view(parent_image, child)
    guide_bytes = _encode(guide, "PNG")
    _atomic_write(output, guide_bytes)
    print(f"Wrote exact {level_id} guide to {output}.")


def _checkerboard(image: Image.Image) -> Image.Image:
    background = Image.new("RGB", image.size, "#d5d8dc")
    draw = ImageDraw.Draw(background)
    tile = 24
    for top in range(0, image.height, tile):
        for left in range(0, image.width, tile):
            if (left // tile + top // tile) % 2:
                draw.rectangle((left, top, left + tile - 1, top + tile - 1), fill="#f4f6f7")
    background.paste(image, mask=image.getchannel("A"))
    return background


def _comparison(images: list[Image.Image], labels: list[str], title: str) -> Image.Image:
    panels = [_checkerboard(image) for image in images]
    title_height, label_height = 72, 48
    sheet = Image.new("RGB", (max(image.width for image in panels), title_height + sum(
        image.height + label_height for image in panels
    )), "#17202a")
    draw = ImageDraw.Draw(sheet)
    draw.text((12, 20), title, fill="white", font=TEXT_FONT)
    top = title_height
    for panel, label in zip(panels, labels, strict=True):
        draw.text((12, top + 10), label, fill="white", font=TEXT_FONT)
        top += label_height
        sheet.paste(panel, ((sheet.width - panel.width) // 2, top))
        top += panel.height
    return sheet


def build(manifest_path: Path, level_id: str, guide_path: Path, output: Path,
          refinement_path: Path | None = None, comparison_path: Path | None = None,
          chroma_key: tuple[int, int, int] | None = None) -> dict:
    codec = _toolchain(require_webp=True)
    level = _level(load_manifest(manifest_path), level_id)
    guide = _load_rgba(guide_path, chroma_key)
    if guide.size != _dimensions(level):
        raise SceneToolError(f"Guide dimensions do not match level {level_id}.")
    candidate = guide
    comparison_bytes = None
    if refinement_path is None and comparison_path is not None:
        raise SceneToolError("A comparison path is valid only for a refined build.")
    if refinement_path is not None:
        if comparison_path is None:
            raise SceneToolError("A refined build requires an explicit comparison path.")
        refinement = _load_rgba(refinement_path, chroma_key)
        if refinement.size != guide.size:
            raise SceneToolError("Refinement dimensions must match the exact guide.")
        candidate = refinement.copy()
        candidate.putalpha(guide.getchannel("A"))
        blend = Image.blend(_checkerboard(guide), _checkerboard(candidate), 0.5).convert("RGBA")
        comparison_bytes = _encode(_comparison(
            [guide, candidate, blend],
            ["exact guide", "refinement + guide alpha", "50% overlay"],
            "HUMAN REVIEW REQUIRED: reject moved or contradictory visible features",
        ), "PNG")
    candidate_bytes = _encode(candidate, "WEBP")
    _require_alpha_webp(candidate_bytes)
    if comparison_path is not None and comparison_bytes is not None:
        _atomic_write(comparison_path, comparison_bytes)
    _atomic_write(output, candidate_bytes)
    metadata = {
        "path": str(output),
        "width": candidate.width,
        "height": candidate.height,
        "bytes": len(candidate_bytes),
        "sha256": hashlib.sha256(candidate_bytes).hexdigest(),
        "pillowVersion": PIL.__version__,
        "webpCodecVersion": codec,
    }
    print(json.dumps(metadata, indent=2, sort_keys=True))
    if refinement_path is not None:
        print("Human review must reject moved visible features.", file=sys.stderr)
    return metadata


def _accepted_images(manifest: dict, manifest_path: Path) -> list[Image.Image]:
    codec = _toolchain(require_webp=True)
    images = []
    for level in manifest["levels"]:
        accepted = level.get("accepted")
        if not isinstance(accepted, dict):
            raise SceneToolError(f"Level {level['id']} has no accepted metadata.")
        relative_path = accepted.get("path")
        if not isinstance(relative_path, str) or not relative_path:
            raise SceneToolError(f"Level {level['id']} accepted path is invalid.")
        path = Path(relative_path)
        if not path.is_absolute():
            path = manifest_path.parent / path
        data = path.read_bytes()
        image = _load_rgba(path, required_format="WEBP")
        expected = {
            "width": image.width,
            "height": image.height,
            "bytes": len(data),
            "sha256": hashlib.sha256(data).hexdigest(),
            "pillowVersion": PIL.__version__,
            "webpCodecVersion": codec,
        }
        if image.size != _dimensions(level):
            raise SceneToolError(f"Accepted {level['id']} dimensions conflict with the level.")
        for key, actual in expected.items():
            if accepted.get(key) != actual:
                raise SceneToolError(f"Accepted {level['id']} {key} does not match {path}.")
        images.append(image)
    return images


def check(manifest_path: Path) -> None:
    manifest = load_manifest(manifest_path)
    _accepted_images(manifest, manifest_path)
    print(f"Checked {LEVEL_COUNT} accepted files and metadata; visible-feature geometry still needs human review.")


def _overview(images: list[Image.Image], levels: list[dict]) -> Image.Image:
    tile_width, tile_height, label_height = 480, 360, 48
    sheet = Image.new("RGB", (tile_width * 3, (tile_height + label_height) * 2), "#17202a")
    draw = ImageDraw.Draw(sheet)
    for index, (source, level) in enumerate(zip(images, levels, strict=True)):
        panel = _checkerboard(source)
        panel.thumbnail((tile_width, tile_height), Image.Resampling.LANCZOS)
        left = index % 3 * tile_width
        top = index // 3 * (tile_height + label_height)
        sheet.paste(panel, (left + (tile_width - panel.width) // 2, top))
        label = f"{level['id']}: {float(level['worldWidthMetres']):.6g} m world width"
        draw.text((left + 8, top + tile_height + 10), label, fill="white", font=TEXT_FONT)
    return sheet


def diagnose(manifest_path: Path, output_dir: Path) -> None:
    manifest = load_manifest(manifest_path)
    levels = manifest["levels"]
    images = _accepted_images(manifest, manifest_path)
    outputs: list[tuple[Path, bytes]] = []
    for index in range(1, len(levels)):
        child = levels[index]
        parent_view = _registered_child_view(images[index - 1], child)
        child_view = images[index]
        blend = Image.blend(_checkerboard(parent_view), _checkerboard(child_view), 0.5).convert(
            "RGBA"
        )
        sheet = _comparison(
            [parent_view, child_view, blend],
            ["parent in child viewport", "accepted child", "50% overlay"],
            f"Common world-space viewport: {float(child['worldWidthMetres']):.6g} m",
        )
        name = f"{index:02d}-{levels[index - 1]['id']}-to-{child['id']}.png"
        outputs.append((output_dir / name, _encode(sheet, "PNG")))
    outputs.append((output_dir / "pyramid-overview.png", _encode(
        _overview(images, levels), "PNG"
    )))
    for path, data in outputs:
        _atomic_write(path, data)
    print(f"Wrote {LEVEL_COUNT - 1} adjacent comparisons and one overview to {output_dir}.")


def _chroma_key(value: str) -> tuple[int, int, int]:
    if not re.fullmatch(r"#[0-9A-Fa-f]{6}", value):
        raise argparse.ArgumentTypeError("chroma key must use #RRGGBB")
    return tuple(int(value[index:index + 2], 16) for index in (1, 3, 5))


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    prepare_parser = commands.add_parser("prepare", help="Create one exact child guide.")
    build_parser = commands.add_parser("build", help="Encode one candidate alpha WebP.")
    check_parser = commands.add_parser("check", help="Verify accepted files without writing.")
    diagnose_parser = commands.add_parser("diagnose", help="Write temporary review diagnostics.")
    for command in (prepare_parser, build_parser):
        command.add_argument("--manifest", required=True, type=Path)
        command.add_argument("--level", required=True)
        command.add_argument("--output", required=True, type=Path)
        command.add_argument("--chroma-key", type=_chroma_key)
    prepare_parser.add_argument("--parent", required=True, type=Path)
    build_parser.add_argument("--guide", required=True, type=Path)
    build_parser.add_argument("--refinement", type=Path)
    build_parser.add_argument("--comparison", type=Path)
    check_parser.add_argument("--manifest", required=True, type=Path)
    diagnose_parser.add_argument("--manifest", required=True, type=Path)
    diagnose_parser.add_argument("--output-dir", required=True, type=Path)
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    try:
        if args.command == "prepare":
            prepare(args.manifest, args.level, args.parent, args.output, args.chroma_key)
        elif args.command == "build":
            build(args.manifest, args.level, args.guide, args.output,
                  args.refinement, args.comparison, args.chroma_key)
        elif args.command == "check":
            check(args.manifest)
        else:
            diagnose(args.manifest, args.output_dir)
    except (SceneToolError, OSError) as exc:
        print(f"Camera-preview scene error: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
