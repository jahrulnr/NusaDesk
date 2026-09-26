#!/usr/bin/env python3
"""Generate the terminal math-art backdrop (app/src/main/assets/terminal/math-art.svg).

The tile is the level-set (contour) drawing of a smooth periodic field

    f(x, y) = sin(a) + sin(b) + 0.35 * sin(a + b),   a = 2*pi*x/T, b = 2*pi*y/T

sampled on a regular grid and contoured with marching squares, then simplified
with Ramer-Douglas-Peucker. Contour lines of a smooth field never cross, so the
tile repeats as large flowing loops instead of a tangle of curves — the same
family of "lines produced by calculation" the launcher backdrop uses, with a
different field.

Output is deterministic: same input -> same bytes. Re-run after changing the
field or the levels and re-check with

    ./gradlew test        (dom-renderer-contract.test.js pins the asset shape)

Usage: python3 scripts/build-terminal-math-art.py [output-path]
"""

import math
import sys
from collections import defaultdict
from pathlib import Path

T = 480.0          # tile size in px; the field is periodic in both axes
N = 220            # sampling resolution per axis
LEVELS = [-1.6, -0.8, 0.8, 1.6]
RDP_EPSILON = 0.45
MIN_POINTS = 12
STROKE = "#4fd1a5"
STROKE_OPACITY = "0.10"
STROKE_WIDTH = "1.2"
DEFAULT_OUTPUT = Path("app/src/main/assets/terminal/math-art.svg")

# corner bits: v00=8, v10=4, v11=2, v01=1 (T/R/B/L are the four cell edges)
SEGMENTS = {
    1: [("L", "B")], 2: [("B", "R")], 3: [("L", "R")], 4: [("T", "R")],
    5: [("T", "R"), ("L", "B")], 6: [("T", "B")], 7: [("T", "L")],
    8: [("T", "L")], 9: [("T", "B")], 10: [("T", "L"), ("B", "R")],
    11: [("T", "R")], 12: [("L", "R")], 13: [("B", "R")], 14: [("L", "B")],
}


def field(x, y):
    a = 2 * math.pi * (x / T)
    b = 2 * math.pi * (y / T)
    return math.sin(a) + math.sin(b) + 0.35 * math.sin(a + b)


def interp(p1, p2, v1, v2, level):
    t = (level - v1) / (v2 - v1)
    return (p1[0] + t * (p2[0] - p1[0]), p1[1] + t * (p2[1] - p1[1]))


def segments(values, h, level):
    out = []
    for i in range(N):
        for j in range(N):
            x0, y0 = i * h, j * h
            v00, v10 = values[i][j], values[i + 1][j]
            v11, v01 = values[i + 1][j + 1], values[i][j + 1]
            idx = ((v00 > level) * 8 + (v10 > level) * 4
                   + (v11 > level) * 2 + (v01 > level))
            if idx in (0, 15):
                continue
            points = {
                "T": lambda: interp((x0, y0), (x0 + h, y0), v00, v10, level),
                "R": lambda: interp((x0 + h, y0), (x0 + h, y0 + h), v10, v11, level),
                "B": lambda: interp((x0, y0 + h), (x0 + h, y0 + h), v01, v11, level),
                "L": lambda: interp((x0, y0), (x0, y0 + h), v00, v01, level),
            }
            for a, b in SEGMENTS[idx]:
                out.append((points[a](), points[b]()))
    return out


def key(point):
    return (round(point[0], 2), round(point[1], 2))


def chain(segs):
    adjacency = defaultdict(list)
    for sid, (a, b) in enumerate(segs):
        adjacency[key(a)].append(sid)
        adjacency[key(b)].append(sid)
    used = [False] * len(segs)
    paths = []
    for sid in range(len(segs)):
        if used[sid]:
            continue
        a, b = segs[sid]
        used[sid] = True
        path = [a, b]
        for forward in (True, False):
            current = path[-1] if forward else path[0]
            while True:
                nxt = None
                for candidate in adjacency[key(current)]:
                    if not used[candidate]:
                        nxt = candidate
                        break
                if nxt is None:
                    break
                used[nxt] = True
                p, q = segs[nxt]
                point = q if key(p) == key(current) else p
                if forward:
                    path.append(point)
                else:
                    path.insert(0, point)
                current = point
        paths.append(path)
    return paths


def simplify(points, epsilon):
    if len(points) < 3:
        return points
    (x1, y1), (x2, y2) = points[0], points[-1]
    dx, dy = x2 - x1, y2 - y1
    norm = math.hypot(dx, dy) or 1e-9
    worst, index = 0.0, 0
    for i in range(1, len(points) - 1):
        px, py = points[i]
        distance = abs(dy * px - dx * py + x2 * y1 - y2 * x1) / norm
        if distance > worst:
            worst, index = distance, i
    if worst > epsilon:
        return (simplify(points[:index + 1], epsilon)[:-1]
                + simplify(points[index:], epsilon))
    return [points[0], points[-1]]


def path_data(points):
    parts = ["M%.1f %.1f" % points[0]]
    for x, y in points[1:]:
        parts.append("L%.1f %.1f" % (x, y))
    return "".join(parts)


def main():
    output = Path(sys.argv[1]) if len(sys.argv) > 1 else DEFAULT_OUTPUT
    h = T / N
    values = [[field(i * h, j * h) for j in range(N + 1)] for i in range(N + 1)]

    paths = []
    for level in LEVELS:
        for line in chain(segments(values, h, level)):
            if len(line) < MIN_POINTS:
                continue
            simplified = simplify(line, RDP_EPSILON)
            if len(simplified) >= 4:
                paths.append(simplified)

    svg = [
        "<svg xmlns='http://www.w3.org/2000/svg' width='480' height='480'"
        " viewBox='0 0 480 480'>",
        "<title>NusaDesk terminal math-art background</title>",
        "<desc>Contour lines of a smooth periodic math field, generated by"
        " calculation: closed flowing loops with no crossings. Regenerate with"
        " scripts/build-terminal-math-art.py.</desc>",
        "<g fill='none' stroke='%s' stroke-width='%s' stroke-linecap='round'"
        " stroke-opacity='%s'>" % (STROKE, STROKE_WIDTH, STROKE_OPACITY),
    ]
    for line in paths:
        svg.append("<path d='%s'/>" % path_data(line))
    svg.append("</g></svg>")
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text("\n".join(svg) + "\n", encoding="utf-8")
    print("wrote %s (%d paths, %d points)"
          % (output, len(paths), sum(len(p) for p in paths)))


if __name__ == "__main__":
    main()
