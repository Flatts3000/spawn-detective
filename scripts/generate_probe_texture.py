"""Generate the Spawn Probe item texture.

A 16x16 placeholder in the vanilla item palette: a dark iron rod running corner
to corner with a glass lens at the top, so it reads as a diagnostic instrument at
inventory size rather than another wand. Rerun after editing the palette below.

    python scripts/generate_probe_texture.py
"""

from pathlib import Path

from PIL import Image

OUT = Path(__file__).resolve().parents[1] / "src/main/resources/assets/spawndetective/textures/item/spawn_probe.png"

# Vanilla-adjacent palette: iron greys for the shaft, a cyan lens so the item
# reads as "instrument" at a glance in a hotbar full of tools.
CLEAR = (0, 0, 0, 0)
DARK = (60, 60, 66, 255)
GREY = (110, 110, 118, 255)
LIGHT = (168, 168, 176, 255)
LENS = (86, 196, 214, 255)
LENS_LIGHT = (150, 230, 240, 255)
GLINT = (255, 255, 255, 255)

# 16x16, row-major. ' ' transparent, 'd' dark, 'g' grey, 'l' light,
# 'c' lens, 'C' lens highlight, 'w' white glint.
ART = [
    "    dddd        ",
    "   dccccd       ",
    "  dcCCwccd      ",
    "  dcCccccd      ",
    "  dccccccd      ",
    "   dccccd       ",
    "    dggd        ",
    "     dggd       ",
    "      dggd      ",
    "       dggd     ",
    "        dggd    ",
    "         dggd   ",
    "          dlgd  ",
    "           dld  ",
    "            dd  ",
    "                ",
]

PALETTE = {
    " ": CLEAR,
    "d": DARK,
    "g": GREY,
    "l": LIGHT,
    "c": LENS,
    "C": LENS_LIGHT,
    "w": GLINT,
}


def main() -> None:
    image = Image.new("RGBA", (16, 16), CLEAR)
    for y, row in enumerate(ART):
        for x, char in enumerate(row):
            image.putpixel((x, y), PALETTE[char])
    OUT.parent.mkdir(parents=True, exist_ok=True)
    image.save(OUT)
    print(f"wrote {OUT}")


if __name__ == "__main__":
    main()
