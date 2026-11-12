"""משווה את lineup/channels.json לרשימה של אפליקציית מסך (masach-tv).

python3 scripts/check_upstream.py [upstream.json]
מדפיס דוח Markdown אם יש הבדל, אחרת כלום.
"""
import json
import sys
import urllib.request
from pathlib import Path

UPSTREAM = "https://masach-lineup.yev-gavrikov.workers.dev/v1/lineup.json"
# מזהה אצלם -> מספר ערוץ אצלנו
IDS = {"ch_11": 11, "ch_12": 12, "ch_13": 13, "ch_14": 14,
       "ch_i24news": 15, "ch_16tv": 16, "ch_99": 99}


def key(module, details):
    """כתובת מנורמלת: לקשת הנתיב, לשאר הכתובת המלאה."""
    return details.get("link") or details.get("path") or details.get("url") or ""


def load_upstream():
    if len(sys.argv) > 1:
        return json.loads(Path(sys.argv[1]).read_text())
    req = urllib.request.Request(UPSTREAM, headers={"User-Agent": "zapper-tv-check"})
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.loads(r.read())


def main():
    ours = json.loads(Path(__file__).resolve().parent.parent.joinpath("lineup/channels.json").read_text())
    mine = {c["number"]: c for c in ours["channels"]}
    try:
        up = load_upstream()
    except Exception as e:  # השרת שלהם נעלם / השתנה
        print(f"## לא הצלחתי לקרוא את הרשימה של מסך\n\n`{e}`\n\nאם זה חוזר כמה ימים, כנראה שהשרת שלהם נסגר.")
        return 1

    lines = []
    for c in up.get("channels", []):
        num = IDS.get(c.get("id"))
        if num is None or num not in mine:
            continue
        our_links = {key(s["type"], s) for s in mine[num]["sources"]}
        name = mine[num]["name"]
        srcs = c.get("sources", [])
        # אותה כתובת יכולה להופיע אצלם פעמיים (מודולים שונים); תקולה רק אם אף אחת לא תקינה
        ok_links = {key(s.get("module"), s.get("details", {})) for s in srcs
                    if (s.get("health") or {}).get("ok", True)}
        for s in srcs:
            d = s.get("details", {})
            link = key(s.get("module"), d)
            h = s.get("health") or {}
            if "/livedash/" in link:  # DASH: האפליקציה מנגנת HLS בלבד
                continue
            if link in our_links and h.get("ok") is False and link not in ok_links:
                lines.append(f"- **{name}**: אצלם מסומן כתקול ({h.get('reason', '?')}): `{link}`")
            elif link not in our_links and h.get("ok", True):
                extra = f" (referer: `{d['referer']}`)" if d.get("referer") else ""
                lines.append(f"- **{name}**: כתובת חדשה אצלם ({s.get('module')}): `{link}`{extra}")
    if not lines:
        return 0
    print("## הבדלים מול הרשימה של מסך\n")
    print("\n".join(lines))
    print("\nלעדכון: לבקש מ-Claude בסשן, או לערוך את `lineup/channels.json` (האפליקציה מורידה אותו בכל הפעלה).")
    return 1


if __name__ == "__main__":
    sys.exit(main())
