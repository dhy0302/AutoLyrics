"""把 Android 自适应图标（矢量 XML）渲染成 PNG，供 README / GitHub 使用。

为什么需要这个：
README 里原先写的是
    <img src="app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml" ...>
那是个 **Android Vector Drawable（XML）**，不是图片格式。
浏览器拿到 XML 只会显示一个破图图标 —— GitHub 上就是这样。

复刻的图形完全取自 ic_launcher.xml + ic_launcher_foreground.xml，
包含其 <group> 的 scale=0.92 / translate=(-6.645, 5.228) 变换，
所以渲染结果与手机上看到的图标一致。
"""

import math
import os

from PIL import Image, ImageDraw

SS = 8  # 超采样倍数，缩小后边缘才平滑
SIZE = 108
GREEN = (0x1D, 0xB9, 0x54)
WHITE = (0xFF, 0xFF, 0xFF)

PIVOT = (54.0, 54.0)
SCALE = 0.92
TRANS = (-6.645, 5.228)

K = 1.0  # 画布坐标 -> 实际像素，由 render() 设置


def xf(x, y):
    """应用 group 变换（绕 pivot 缩放 + 平移），再换算成像素坐标。"""
    px, py = x - PIVOT[0], y - PIVOT[1]
    return (PIVOT[0] + px * SCALE + TRANS[0]) * K, (PIVOT[1] + py * SCALE + TRANS[1]) * K


def bezier(p0, p1, p2, p3, n=60):
    """三次贝塞尔采样成折线点列。"""
    pts = []
    for i in range(n + 1):
        t = i / n
        u = 1 - t
        x = u**3 * p0[0] + 3 * u**2 * t * p1[0] + 3 * u * t**2 * p2[0] + t**3 * p3[0]
        y = u**3 * p0[1] + 3 * u**2 * t * p1[1] + 3 * u * t**2 * p2[1] + t**3 * p3[1]
        pts.append((x, y))
    return pts


def stroke(draw, pts, width, color):
    """按折线画粗线，每点补一个圆 —— 等价于 round cap + round join。"""
    r = width / 2.0
    draw.line(pts, fill=color, width=int(round(width)), joint="curve")
    for x, y in pts:
        draw.ellipse([x - r, y - r, x + r, y + r], fill=color)


def rotated_ellipse(draw, cx, cy, rx, ry, deg, fill):
    """画一个旋转过的实心椭圆（Android path 里的 a + rotation）。"""
    # 先在自身坐标系画好，再旋转贴到目标位置
    n = 72
    pts = []
    for i in range(n):
        th = 2 * math.pi * i / n
        x, y = rx * math.cos(th), ry * math.sin(th)
        rad = math.radians(deg)
        xr = x * math.cos(rad) - y * math.sin(rad)
        yr = x * math.sin(rad) + y * math.cos(rad)
        pts.append((cx + xr, cy + yr))
    draw.polygon(pts, fill=fill)


def render(px_size):
    global K
    big = px_size * SS          # 先按 SS 倍超采样画，最后缩回 px_size
    K = big / SIZE              # 画布坐标 108 -> 实际像素的缩放系数
    img = Image.new("RGB", (big, big), WHITE)
    d = ImageDraw.Draw(img)

    w = 7 * SCALE * K# strokeWidth 也随 group 缩放

    # 符干M60,26 -> L60,62
    a, b = xf(60, 26), xf(60, 62)
    stroke(d, [a, b], w, GREEN)

    # 符尾 M60,26 C60,21 63,18.5 67.5,18.5 L82,18.5
    c0 = xf(60, 26)
    c1, c2, c3 = xf(60, 21), xf(63, 18.5), xf(67.5, 18.5)
    pts = bezier(c0, c1, c2, c3) + [xf(82, 18.5)]
    stroke(d, pts, w, GREEN)

    # 符头 实心椭圆 center(50,72) rx13.5 ry9 rot -20
    hx, hy = xf(50, 72)
    rotated_ellipse(d, hx, hy, 13.5 * SCALE * K, 9 * SCALE * K, -20, GREEN)

    # 符头镂空 白椭圆 center(47,73) rx5.2 ry3.4 rot -20
    ex, ey = xf(47, 73)
    rotated_ellipse(d, ex, ey, 5.2 * SCALE * K, 3.4 * SCALE * K, -20, WHITE)

    return img.resize((px_size, px_size), Image.LANCZOS)


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    out_dir = os.path.join(here, "..", "assets")
    os.makedirs(out_dir, exist_ok=True)
    for s in (512, 192):
        p = os.path.join(out_dir, f"logo-{s}.png")
        render(s).save(p, "PNG", optimize=True)
        print("written:", os.path.relpath(p, here), os.path.getsize(p), "bytes")


if __name__ == "__main__":
    main()