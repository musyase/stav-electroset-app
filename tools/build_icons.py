"""Create the legacy launcher icons from the same line mark as the adaptive icon."""

from pathlib import Path

from PIL import Image, ImageDraw


ROOT = Path(__file__).resolve().parents[1]
BLUE = (35, 76, 112)
WHITE = (255, 255, 255)
GOLD = (224, 179, 107)


def icon(size: int, rounded: bool) -> Image.Image:
    image = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    draw = ImageDraw.Draw(image)
    margin = round(size * 0.02)
    radius = size // 2 if rounded else round(size * 0.18)
    draw.rounded_rectangle((margin, margin, size - margin, size - margin), radius=radius, fill=BLUE)
    for y in (0.36, 0.50, 0.64):
        draw.line((round(size * 0.29), round(size * y), round(size * 0.71), round(size * y)), fill=WHITE, width=max(2, round(size * 0.045)))
    draw.line((round(size * 0.5), round(size * 0.29), round(size * 0.5), round(size * 0.71)), fill=GOLD, width=max(2, round(size * 0.05)))
    return image


if __name__ == "__main__":
    for density, size in {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}.items():
        folder = ROOT / "app" / "src" / "main" / "res" / f"mipmap-{density}"
        icon(size, False).save(folder / "ic_launcher.webp", format="WEBP", lossless=True)
        icon(size, True).save(folder / "ic_launcher_round.webp", format="WEBP", lossless=True)
