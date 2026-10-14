"""Reads a timetable PDF printed from the school's 教务 system (树维 EAMS "学生课表" print page).

The page is a grid with one column per weekday. Text extraction alone loses the columns, so the
weekday comes from where each line sits under the 星期一…星期日 header; everything else comes from
the entry text itself, e.g. "● 线性代数 A ◇ 1-2 节 /(1~5,7~8) 周◇草堂 9-203 ◇朱立平".

Nothing here keeps the file or reads the student details in the page header.
"""
from __future__ import annotations

import io
import re
import unicodedata
from dataclasses import dataclass, field

from pdfminer.high_level import extract_pages
from pdfminer.layout import LTChar, LTTextLine

DAYS = ["星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日"]

# A time part of an entry: "1-2 节 /(1~5,7~8) 周◇草堂 9-203 ◇朱立平". The room and teacher are
# separated by ◇; a second time part of the same course may use spaces instead.
PART = re.compile(r"(\d{1,2})\s*-\s*(\d{1,2})\s*节\s*/\s*\(([^)]*(?:\([^)]*\)[^)]*)*)\)\s*周")
WEEK_ITEM = re.compile(r"^(\d{1,2})(?:~(\d{1,2}))?(?:\((单|双)\))?$")


class TimetableError(ValueError):
    pass


@dataclass
class Slot:
    day: int
    start: int
    end: int
    weekFrom: int
    weekTo: int
    parity: str
    room: str


@dataclass
class Course:
    name: str
    teacher: str = ""
    slots: list[Slot] = field(default_factory=list)


# CJK Radicals Supplement forms the print uses for some characters; NFKC leaves these alone.
RADICALS = str.maketrans({
    "⻄": "西", "⻩": "黄", "⻔": "门", "⻓": "长", "⻛": "风", "⻜": "飞", "⻢": "马", "⻥": "鱼",
    "⻝": "食", "⻅": "见", "⻉": "贝", "⻋": "车", "⻚": "页", "⻮": "竹", "⻰": "龙", "⻳": "龟",
    "⻆": "角", "⻘": "青", "⻬": "齐", "⻧": "卤", "⻨": "麦", "⻦": "鸟", "⻤": "鬼", "⻣": "骨",
})


def normalize(text: str) -> str:
    # The print uses Kangxi radicals and full-width forms (⼤, （）); NFKC turns most into plain text.
    return unicodedata.normalize("NFKC", text).translate(RADICALS)


def parse_weeks(spec: str) -> list[tuple[int, int, str]]:
    """'1~3(单),4~6(双),7' → [(1, 3, 'odd'), (4, 6, 'even'), (7, 7, 'all')]."""
    ranges = []
    for raw in re.split(r"[,，]", re.sub(r"\s+", "", spec)):
        if not raw:
            continue
        match = WEEK_ITEM.match(raw)
        if not match:
            raise TimetableError(f"看不懂周次：{raw}")
        start = int(match.group(1))
        end = int(match.group(2) or start)
        parity = {"单": "odd", "双": "even"}.get(match.group(3) or "", "all")
        if end < start:
            start, end = end, start
        ranges.append((start, end, parity))
    return ranges


def _lines(data: bytes) -> list[tuple[float, float, float, str]]:
    """(x centre, y top, x0, text) of every text line, split where a line crosses columns."""
    out = []
    for page in extract_pages(io.BytesIO(data)):
        stack = [page]
        while stack:
            item = stack.pop()
            if isinstance(item, LTTextLine):
                chars = [ch for ch in item if isinstance(ch, LTChar)]
                if chars:
                    out.append((chars, item.y1))
            elif hasattr(item, "__iter__"):
                stack.extend(item)
        break  # one page; a longer print still has the whole grid on the first
    return out


def _columns(lines) -> tuple[list[float], float, float]:
    headers = {}
    for chars, top in lines:
        text = normalize("".join(c.get_text() for c in chars)).replace(" ", "")
        for i, name in enumerate(DAYS):
            if text == name:
                headers[i] = ((chars[0].x0 + chars[-1].x1) / 2, top)
    if len(headers) < 5:
        raise TimetableError("没有找到星期一到星期日的表头，这不像教务系统打印的课表")
    centres = [headers[i][0] for i in sorted(headers)]
    width = (centres[-1] - centres[0]) / (len(centres) - 1)
    while len(centres) < 7:
        centres.append(centres[-1] + width)
    header_top = min(top for _, top in headers.values())
    left = centres[0] - width / 2
    # The grid ends at the credit total; the browser's print footer (URL, page) comes after it.
    footer = [top for chars, top in lines if top < header_top and _is_footer("".join(c.get_text() for c in chars))]
    bottom = max(footer) if footer else 0.0
    return centres, header_top, left, bottom


def _is_footer(text: str) -> bool:
    text = normalize(text)
    return "学分" in text or "://" in text or "for-std" in text


def _column_texts(data: bytes) -> list[str]:
    return _assign(_lines(data))


def _assign(lines) -> list[str]:
    """Text of each weekday column, top to bottom, from (characters, line top) pairs."""
    centres, header_top, left, bottom = _columns(lines)
    width = centres[1] - centres[0]
    pieces: list[list[tuple[float, float, str]]] = [[] for _ in range(7)]
    for chars, top in lines:
        if top >= header_top - 1 or top <= bottom + 0.5:
            continue
        # Assign characters, not whole lines: neighbouring cells can share a text line.
        run: list[LTChar] = []
        run_col = None
        for c in sorted(chars, key=lambda ch: ch.x0):
            x = (c.x0 + c.x1) / 2
            if x < left:
                continue
            col = min(range(7), key=lambda k: abs(centres[k] - x))
            if abs(centres[col] - x) > width:
                continue
            if run and col != run_col:
                pieces[run_col].append((-top, run[0].x0, "".join(r.get_text() for r in run)))
                run = []
            run.append(c)
            run_col = col
        if run:
            pieces[run_col].append((-top, run[0].x0, "".join(r.get_text() for r in run)))
    return [normalize("".join(text for _, _, text in sorted(column))) for column in pieces]


def _entries(text: str) -> list[str]:
    return [part.strip() for part in text.split("●") if part.strip()]


def _parse_entry(day: int, entry: str) -> tuple[str, str, list[Slot]] | None:
    parts = list(PART.finditer(entry))
    if not parts:
        return None
    name = entry[: parts[0].start()].strip(" ◇")
    name = re.sub(r"\s+", " ", name).strip()
    teacher = ""
    slots = []
    for index, part in enumerate(parts):
        tail_end = parts[index + 1].start() if index + 1 < len(parts) else len(entry)
        tail = entry[part.end(): tail_end]
        fields = [f.strip() for f in re.split(r"◇", tail) if f.strip()]
        if len(fields) == 1:
            # "草堂物理实验室 王蕾": the second time part separates room and teacher with spaces.
            bits = fields[0].rsplit(None, 1)
            fields = bits if len(bits) == 2 else fields
        room = re.sub(r"\s+", "", fields[0]) if fields else ""
        if len(fields) > 1 and not teacher:
            teacher = re.sub(r"\s*/\s*", "/", fields[-1]).strip()
        start, end = int(part.group(1)), int(part.group(2))
        for week_from, week_to, parity in parse_weeks(part.group(3)):
            slots.append(Slot(day, min(start, end), max(start, end), week_from, week_to, parity, room))
    return name, teacher, slots


def parse_timetable(data: bytes) -> dict:
    if not data.startswith(b"%PDF"):
        raise TimetableError("请选择 PDF 文件")
    try:
        columns = _column_texts(data)
    except TimetableError:
        raise
    except Exception as error:  # a damaged or unusual PDF
        raise TimetableError("读不了这个 PDF") from error

    courses: dict[str, Course] = {}
    skipped = []
    for day, text in enumerate(columns, start=1):
        for entry in _entries(text):
            try:
                parsed = _parse_entry(day, entry)
            except TimetableError as error:
                skipped.append(f"{entry[:30]}（{error}）")
                continue
            if parsed is None:
                if "节" in entry:
                    skipped.append(entry[:30])
                continue
            name, teacher, slots = parsed
            if not name:
                skipped.append(entry[:30])
                continue
            course = courses.setdefault(name, Course(name))
            course.teacher = course.teacher or teacher
            for slot in slots:
                if slot not in course.slots:
                    course.slots.append(slot)

    if not courses:
        raise TimetableError("没有在这个 PDF 里找到课程")
    for course in courses.values():
        course.slots.sort(key=lambda s: (s.day, s.start, s.weekFrom))
    max_week = max(s.weekTo for c in courses.values() for s in c.slots)
    max_period = max(s.end for c in courses.values() for s in c.slots)
    return {
        "courses": [
            {
                "name": c.name,
                "teacher": c.teacher,
                "slots": [
                    {"day": s.day, "from": s.start, "to": s.end, "weekFrom": s.weekFrom, "weekTo": s.weekTo,
                     "parity": s.parity, "room": s.room}
                    for s in c.slots
                ],
            }
            for c in courses.values()
        ],
        "maxWeek": max_week,
        "maxPeriod": max_period,
        "skipped": skipped,
    }
