"""Generate the mod logo shown on CurseForge and in the mods list.

Deliberately not the item texture scaled up. 16x16 art blown up to 256 is mush,
and the mods list renders this small - so it is drawn at size, as a plain mark: the
probe's lens over a dark ground, with a spawn grid behind it.

    python scripts/generate_logo.py
"""

from pathlib import Path

from PIL import Image, ImageDraw

OUT = Path(__file__).resolve().parents[1] / "src/main/resources/logo.png"
SIZE = 256

# Same palette the report screen uses, so the mod looks like one thing across the
# store page, the mods list, and the GUI.
GROUND = (26, 27, 30, 255)
GRID = (40, 43, 48, 255)
LENS = (86, 196, 214, 255)
LENS_CORE = (150, 230, 240, 255)
SHAFT = (110, 110, 118, 255)
SHAFT_DARK = (60, 60, 66, 255)
BLOCKED = (255, 107, 96, 255)
CLEAR = (91, 214, 117, 255)


def main() -> None:
    image = Image.new("RGBA", (SIZE, SIZE), GROUND)
    draw = ImageDraw.Draw(image)

    # A faint block grid: this is a tool about positions, and the grid says so
    # without needing a word on it.
    step = 32
    for offset in range(0, SIZE + 1, step):
        draw.line([(offset, 0), (offset, SIZE)], fill=GRID, width=1)
        draw.line([(0, offset), (SIZE, offset)], fill=GRID, width=1)

    # Two marked cells, one of each verdict. The whole mod in one glance: some
    # blocks spawn, some do not, and it tells you which.
    draw.rectangle([33, 161, 62, 190], fill=BLOCKED)
    draw.rectangle([161, 193, 190, 222], fill=CLEAR)

    # The probe: a shaft running corner to corner with the lens at the top.
    draw.line([(96, 196), (150, 108)], fill=SHAFT_DARK, width=14)
    draw.line([(96, 196), (150, 108)], fill=SHAFT, width=8)

    draw.ellipse([120, 46, 196, 122], fill=SHAFT_DARK)
    draw.ellipse([126, 52, 190, 116], fill=LENS)
    draw.ellipse([142, 66, 168, 92], fill=LENS_CORE)

    OUT.parent.mkdir(parents=True, exist_ok=True)
    image.save(OUT)
    print(f"wrote {OUT} ({SIZE}x{SIZE})")


if __name__ == "__main__":
    main()
