# -*- coding: utf-8 -*-
"""生成 XY-READER 自适应图标：靛蓝渐变底 + 白色开书 + 琥珀书签"""
from PIL import Image, ImageDraw
import os

RES = r"C:\Users\11\.zcode\workspace\default\ArkReader\app\src\main\res"
S = 432  # adaptive icon xxxhdpi 前景/背景画布

def qbez(p0, p1, p2, n=32):
    """二次贝塞尔采样"""
    pts = []
    for i in range(n + 1):
        t = i / n
        x = (1-t)**2*p0[0] + 2*(1-t)*t*p1[0] + t**2*p2[0]
        y = (1-t)**2*p0[1] + 2*(1-t)*t*p1[1] + t**2*p2[1]
        pts.append((x, y))
    return pts

def mirror_x(pts, axis=S):
    return [(axis - x, y) for x, y in pts]

def draw_book(scale=1.0, dx=0, dy=0):
    """返回左页多边形点集（右页镜像得到）"""
    def T(p):
        return (216 + (p[0]-216)*scale + dx, 200 + (p[1]-200)*scale + dy)
    top = qbez(T((210,152)), T((160,118)), T((112,140)))
    outer = qbez(T((112,140)), T((100,212)), T((104,282)))
    bottom = qbez(T((104,282)), T((158,286)), T((210,262)))
    left = top + outer + bottom
    right = mirror_x(left)
    return left, right

def render(size, book_scale, book_dy, fg_only=True):
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    if not fg_only:
        # 对角渐变背景 #4338CA -> #818CF8
        c0, c1 = (67, 56, 202), (129, 140, 248)
        px = img.load()
        for y in range(S):
            for x in range(S):
                t = (x + y) / (2 * S - 2)
                px[x, y] = (
                    int(c0[0] + (c1[0]-c0[0])*t),
                    int(c0[1] + (c1[1]-c0[1])*t),
                    int(c0[2] + (c1[2]-c0[2])*t),
                    255,
                )
    left, right = draw_book(book_scale, 0, book_dy)
    # 书下投影
    sh = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    ImageDraw.Draw(sh).ellipse([120, 288, 312, 312], fill=(20, 16, 60, 70))
    img.alpha_composite(sh)
    # 两页（留 8px 书脊缝）
    d.polygon(right, fill=(235, 238, 252, 255))   # 右页略暗制造受光差
    d.polygon(left, fill=(255, 255, 255, 255))    # 左页亮
    # 琥珀书签（右侧页内垂下，V 形缺口）
    bx = 296
    btop = 150 + book_dy
    d.polygon([(bx-13, btop), (bx+13, btop), (bx+13, btop+64), (bx, btop+50), (bx-13, btop+64)],
              fill=(251, 191, 36, 255))
    return img

def resize(img, size):
    return img.resize((size, size), Image.LANCZOS)

# 预览合成（用户看的）
bg = render(S, 1.0, 0, fg_only=False)
fg = render(S, 1.0, 0, fg_only=True)
preview = bg.copy(); preview.alpha_composite(fg)
os.makedirs(r"C:\Users\11\.zcode\workspace\default\mhark_shots", exist_ok=True)
preview.resize((512, 512), Image.LANCZOS).save(r"C:\Users\11\.zcode\workspace\default\mhark_shots\icon_preview.png")
# 圆形裁切预览（模拟启动器圆形遮罩）
mask = Image.new("L", (S, S), 0)
ImageDraw.Draw(mask).ellipse([0, 0, S, S], fill=255)
circ = Image.new("RGBA", (S, S), (0, 0, 0, 0)); circ.paste(preview, (0, 0), mask)
circ.resize((512, 512), Image.LANCZOS).save(r"C:\Users\11\.zcode\workspace\default\mhark_shots\icon_preview_circle.png")

# 落盘资源
bg.resize((432, 432), Image.LANCZOS).save(os.path.join(RES, "drawable", "ic_launcher_background.png"))
fg.resize((432, 432), Image.LANCZOS).save(os.path.join(RES, "drawable", "ic_launcher_foreground.png"))
print("OK")
