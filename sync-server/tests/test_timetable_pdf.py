from __future__ import annotations

import pytest
from fastapi.testclient import TestClient

from publish import timetable_pdf as tp
from publish.main import create_app


class Char:
    def __init__(self, text: str, x: float, width: float = 6.0):
        self.text, self.x0, self.x1 = text, x, x + width

    def get_text(self) -> str:
        return self.text


def line(text: str, x: float, top: float):
    return ([Char(ch, x + i * 6) for i, ch in enumerate(text)], top)


CENTRES = [89, 163, 237, 310, 384, 457, 531]


def header():
    return [line(day, c - 9, 735) for day, c in zip(tp.DAYS, CENTRES)]


def test_weeks_cover_ranges_singles_and_odd_even():
    assert tp.parse_weeks("1~5,7~8") == [(1, 5, "all"), (7, 8, "all")]
    assert tp.parse_weeks("1~3(单),4~6(双),7") == [(1, 3, "odd"), (4, 6, "even"), (7, 7, "all")]
    assert tp.parse_weeks(" 8 , 11 ,15") == [(8, 8, "all"), (11, 11, "all"), (15, 15, "all")]
    with pytest.raises(tp.TimetableError):
        tp.parse_weeks("第一周")


def test_entry_with_a_second_time_part_and_space_separated_teacher():
    entry = tp.normalize("大学物理实验◇ 7-8 节 /(2) 周◇ 4-423 ◇王蕾7-8 节 / (3~5,7~14) 周◇草堂物理实验室   王蕾")
    name, teacher, slots = tp._parse_entry(1, entry)
    assert (name, teacher) == ("大学物理实验", "王蕾")
    assert [(s.weekFrom, s.weekTo, s.room) for s in slots] == [(2, 2, "4-423"), (3, 5, "草堂物理实验室"), (7, 14, "草堂物理实验室")]
    assert {(s.day, s.start, s.end) for s in slots} == {(1, 7, 8)}


def test_print_forms_become_plain_characters():
    assert tp.normalize("⼤学物理 ⻩莺 ⻔进杰 ⻄方（土木类）") == "大学物理 黄莺 门进杰 西方(土木类)"


def test_columns_follow_the_weekday_header_and_skip_the_footer():
    lines = header() + [
        # Cell text wraps inside its column, as on the printed page.
        line("●线性代数A◇1-2节/", 60, 700), line("(1~5)周◇草堂9-203", 60, 690), line("◇朱立平", 60, 680),
        # Wednesday and Thursday cells on one text line still land in their own columns.
        line("●体育◇3-4", 214, 650), line("节/(1~16)周◇操场◇吕", 220, 640),
        line("●英语◇1-2节/(2)周◇8-308◇李", 290, 650),
        line("本学期所选课程总学分：33.5", 40, 100),
        line("https://example.edu/print", 40, 60),
    ]
    columns = tp._assign(lines)
    assert columns[0].startswith("●线性代数A")
    assert "所选" not in columns[0] and "https" not in "".join(columns)
    assert columns[2].startswith("●体育")
    assert columns[3].startswith("●英语")


def test_rejects_files_that_are_not_a_timetable_pdf():
    with pytest.raises(tp.TimetableError, match="PDF"):
        tp.parse_timetable(b"hello")


@pytest.fixture
def client(tmp_path, monkeypatch):
    monkeypatch.setenv("PUBLISH_ADMIN_TOKEN", "x")
    with TestClient(create_app(f"sqlite:///{tmp_path / 'p.db'}")) as value:
        yield value


def test_endpoint_parses_in_memory_and_reports_problems(client, monkeypatch):
    url = "/api/public/v1/tools/timetable-pdf"
    assert client.post(url, content=b"").status_code == 400
    assert client.post(url, content=b"not a pdf").json()["detail"] == "请选择 PDF 文件"
    assert client.post(url, content=b"%PDF" + b"0" * (2 * 1024 * 1024)).status_code == 413
    monkeypatch.setattr("publish.main.parse_timetable", lambda data: {"courses": [], "seen": len(data)})
    assert client.post(url, content=b"%PDF-1.4 x", headers={"content-type": "application/pdf"}).json() == {"courses": [], "seen": 10}


def test_endpoint_is_rate_limited_per_client(client, monkeypatch):
    monkeypatch.setattr("publish.main.TIMETABLE_PDF_PER_HOUR", 2)
    url = "/api/public/v1/tools/timetable-pdf"
    codes = [client.post(url, content=b"x", headers={"x-real-ip": "1.2.3.4"}).status_code for _ in range(3)]
    assert codes == [422, 422, 429]
    assert client.post(url, content=b"x", headers={"x-real-ip": "5.6.7.8"}).status_code == 422
