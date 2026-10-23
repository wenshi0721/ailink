#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
把 Markdown 文档渲染成适合普通用户阅读的 A4 PDF。

做法：Markdown -> 带排版样式的 HTML -> Edge/Chrome 无头打印 -> PDF。
不依赖 pandoc / LaTeX，只依赖 markdown 库和本机浏览器。

用法：
    python tools/docs/build-pdf.py docs/ops/手机后台保活设置指南.md release/手机后台保活设置指南.pdf
"""

import os
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

import markdown

# ---------- 浏览器定位 ----------

BROWSER_CANDIDATES = [
    r"C:\Program Files\Google\Chrome\Application\chrome.exe",
    r"C:\Program Files (x86)\Google\Chrome\Application\chrome.exe",
    r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe",
    r"C:\Program Files\Microsoft\Edge\Application\msedge.exe",
    "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
    "/usr/bin/google-chrome",
    "/usr/bin/chromium",
]


def find_browser() -> str:
    for path in BROWSER_CANDIDATES:
        if os.path.isfile(path):
            return path
    for name in ("chrome", "google-chrome", "chromium", "msedge"):
        found = shutil.which(name)
        if found:
            return found
    raise SystemExit("未找到 Chrome / Edge，无法打印 PDF")


# ---------- 样式 ----------

CSS = r"""
@page { size: A4; margin: 17mm 15mm 16mm 15mm; }

:root {
  --brand: #2E5BFF;
  --brand-deep: #10306B;
  --brand-soft: #E8EEFF;
  --text: #14161C;
  --text-2: #4B5563;
  --muted: #6B7280;
  --line: #E5E8F0;
  --line-2: #EEF0F6;
  --bg-soft: #F5F6FA;
  --warn: #B45309;
  --warn-bg: #FEF6E7;
}

* { box-sizing: border-box; }

html { -webkit-print-color-adjust: exact; print-color-adjust: exact; }

body {
  margin: 0;
  font-family: "Microsoft YaHei UI", "Microsoft YaHei", "PingFang SC",
               "Hiragino Sans GB", "Segoe UI", sans-serif;
  font-size: 10.4pt;
  line-height: 1.78;
  color: var(--text);
}

p { margin: 0 0 0.62em; }
strong { font-weight: 600; color: var(--brand-deep); }
a { color: var(--brand); text-decoration: none; }

/* ---------- 封面 ---------- */

.cover {
  height: 246mm;
  border: 1px solid var(--line);
  border-radius: 10px;
  overflow: hidden;
  break-after: page;
  display: flex;
  flex-direction: column;
}

.cover-head {
  background: linear-gradient(135deg, #10306B 0%, #2E5BFF 100%);
  color: #fff;
  padding: 22mm 16mm 18mm;
}

.cover-tag {
  font-size: 9.5pt;
  letter-spacing: 0.18em;
  opacity: 0.85;
  margin-bottom: 8mm;
}

.cover h1 {
  font-size: 30pt;
  line-height: 1.3;
  font-weight: 700;
  margin: 0 0 6mm;
  letter-spacing: 0.01em;
}

.cover-sub {
  font-size: 12.5pt;
  line-height: 1.7;
  opacity: 0.92;
  margin: 0;
}

.cover-body { padding: 14mm 16mm 0; flex: 1; }

.cover-lede {
  font-size: 11pt;
  color: var(--text-2);
  line-height: 1.95;
  margin: 0 0 12mm;
  padding-left: 10px;
  border-left: 3px solid var(--brand);
}

.cover-meta {
  display: table;
  width: 100%;
  border-collapse: collapse;
  font-size: 9.6pt;
}

.cover-meta div { display: table-row; }

.cover-meta span {
  display: table-cell;
  padding: 7px 0;
  border-bottom: 1px solid var(--line-2);
}

.cover-meta span:first-child {
  width: 26mm;
  color: var(--muted);
}

.cover-foot {
  padding: 0 16mm 12mm;
  font-size: 8.8pt;
  color: var(--muted);
  display: flex;
  justify-content: space-between;
}

/* ---------- 目录 ---------- */

.toc { break-after: page; }

.toc h2 {
  font-size: 18pt;
  margin: 0 0 6mm;
  padding: 0;
  border: 0;
  color: var(--brand-deep);
}

.toc-list { margin: 0; padding: 0; }

.toc-item {
  display: flex;
  align-items: baseline;
  padding: 3.1px 0;
  border-bottom: 1px dotted #D8DDE9;
  line-height: 1.55;
}

.toc-item.lv2 { padding-left: 0; }
.toc-item.lv3 { padding-left: 16px; }

.toc-item .num {
  flex: 0 0 auto;
  min-width: 13mm;
  padding-right: 4mm;
  white-space: nowrap;
  color: var(--brand);
  font-weight: 600;
  font-size: 9.4pt;
}

.toc-item.lv3 .num {
  min-width: 11mm;
  color: var(--muted);
  font-weight: 400;
}

.toc-item .txt { flex: 1; }
.toc-item.lv3 .txt { font-size: 9.6pt; color: var(--text-2); }

/* 目录条目可点击跳转（打印后转成 PDF 内部链接） */
.toc-item .toc-a { color: inherit; text-decoration: none; }

.toc-item .pg {
  flex: 0 0 9mm;
  text-align: right;
  font-size: 9pt;
  color: var(--muted);
  font-variant-numeric: tabular-nums;
}

/* ---------- 正文标题 ---------- */

main { counter-reset: h2; }

h2 {
  font-size: 16pt;
  font-weight: 700;
  color: var(--brand-deep);
  margin: 13mm 0 5mm;
  padding: 0 0 3mm 12px;
  border-left: 5px solid var(--brand);
  border-bottom: 1px solid var(--line);
  break-after: avoid;
}

h2.first { margin-top: 0; }

/* 附录 A 是勾选清单，单独起页便于打印核对 */
h2.page-break { break-before: page; margin-top: 0; }

h3 {
  font-size: 12.2pt;
  font-weight: 700;
  color: var(--text);
  margin: 7mm 0 2.6mm;
  padding-left: 14px;
  position: relative;
  break-after: avoid;
}

h3::before {
  content: "";
  position: absolute;
  left: 0;
  top: 0.52em;
  width: 7px;
  height: 7px;
  border-radius: 2px;
  background: var(--brand);
}

h4 { font-size: 10.8pt; margin: 5mm 0 2mm; break-after: avoid; }

/* ---------- 正文元素 ---------- */

blockquote {
  margin: 0 0 6mm;
  padding: 10px 14px;
  background: var(--bg-soft);
  border-left: 3px solid var(--brand-soft);
  border-radius: 0 6px 6px 0;
  font-size: 9.8pt;
  color: var(--text-2);
}

blockquote p { margin: 0 0 4px; }
blockquote p:last-child { margin-bottom: 0; }

ul, ol { margin: 0 0 0.7em; padding-left: 1.5em; }
li { margin-bottom: 0.28em; }
li > ul, li > ol { margin-top: 0.28em; }

li.task {
  list-style: none;
  margin-left: -1.25em;
  margin-bottom: 0.42em;
}

li.task .cb {
  display: inline-block;
  width: 11px;
  height: 11px;
  margin-right: 8px;
  border: 1.4px solid #A9B4CC;
  border-radius: 2.5px;
  vertical-align: -1px;
}

code {
  font-family: Consolas, "Cascadia Mono", "SF Mono", Menlo, monospace;
  font-size: 0.9em;
  background: #F1F4FB;
  color: #1F3A7A;
  padding: 1px 4px;
  border-radius: 3px;
  word-break: break-word;
}

pre {
  background: #F7F8FC;
  border: 1px solid var(--line-2);
  border-radius: 6px;
  padding: 10px 12px;
  margin: 0 0 0.9em;
  overflow: hidden;
  break-inside: avoid;
}

pre code {
  background: none;
  color: #1F2A44;
  padding: 0;
  font-size: 8.8pt;
  line-height: 1.65;
  white-space: pre-wrap;
}

hr { display: none; }

/* ---------- 表格 ---------- */

table {
  width: 100%;
  border-collapse: collapse;
  margin: 0 0 5mm;
  font-size: 9.2pt;
  line-height: 1.62;
  break-inside: auto;
}

thead { display: table-header-group; }

th {
  background: var(--brand-deep);
  color: #fff;
  font-weight: 600;
  text-align: left;
  padding: 7px 8px;
  border: 1px solid var(--brand-deep);
}

td {
  padding: 6.5px 8px;
  border: 1px solid var(--line);
  vertical-align: top;
}

/* 首列多为「目标」短词，给足宽度避免中文被拆成两行 */
td:first-child, th:first-child { min-width: 22.5mm; }

/* 三列表格（各品牌细则）首列不放行内换行，避免「后台使用限制」被拆成单字 */
table:not(:has(thead th:nth-of-type(4))) td:first-child { white-space: nowrap; }

tbody tr:nth-child(even) td { background: #FAFBFE; }
tr { break-inside: avoid; }

/* ---------- 提示块 ---------- */

p.callout {
  background: var(--warn-bg);
  border-left: 3px solid #F0B357;
  border-radius: 0 6px 6px 0;
  padding: 9px 13px;
  margin: 0 0 0.9em;
  font-size: 9.9pt;
  break-inside: avoid;
}

p.callout strong { color: var(--warn); }

p.key {
  background: var(--brand-soft);
  border-left: 3px solid var(--brand);
  border-radius: 0 6px 6px 0;
  padding: 9px 13px;
  margin: 0 0 0.9em;
  font-size: 9.9pt;
  break-inside: avoid;
}

p.key strong { color: var(--brand-deep); }

/* 独立成段的「注意」：作为列表的小标题，不再套空色块 */
p.note-label {
  margin: 0 0 2mm;
  font-size: 10pt;
  font-weight: 700;
  color: var(--warn);
  letter-spacing: 0.06em;
  break-after: avoid;
}

p.note-label::before {
  content: "";
  display: inline-block;
  width: 3px;
  height: 11px;
  margin-right: 7px;
  background: #F0B357;
  border-radius: 2px;
  vertical-align: -1px;
}
"""


# ---------- 转换 ----------

def strip_tags(s: str) -> str:
    return re.sub(r"<[^>]+>", "", s)


# 中文序号 / 附录 / 阿拉伯数字编号，用于把标题拆成「编号 + 标题文本」
PREFIX_RE = re.compile(
    r"^([〇一二三四五六七八九十]+、|附录\s*[A-Z]\s*[:：]?|\d+(?:\.\d+)*\s*[.、]?)\s*(.*)$"
)


def split_prefix(text: str):
    m = PREFIX_RE.match(text.strip())
    if m:
        return m.group(1).strip(), m.group(2).strip()
    return "", text.strip()


def build_toc_and_anchors(html: str):
    """给 h2/h3 注入稳定 id，同时收集目录项（原标题自带编号，不另行编号）。"""
    items = []
    counters = [0, 0]

    def repl(m):
        level = int(m.group(1))
        inner = m.group(2)
        full = strip_tags(inner).strip()
        prefix, display = split_prefix(full)
        if level == 2:
            counters[0] += 1
            counters[1] = 0
            anchor = f"sec-{counters[0]}"
        else:
            counters[1] += 1
            anchor = f"sec-{counters[0]}-{counters[1]}"
        items.append(
            {
                "level": level,
                "num": prefix,
                "text": display,
                "full": full,
                "anchor": anchor,
            }
        )
        return f'<h{level} id="{anchor}">{inner}</h{level}>'

    html = re.sub(r"<h([23])>(.*?)</h\1>", repl, html, flags=re.S)
    return html, items


def render_toc(items, pages=None) -> str:
    pages = pages or {}
    rows = []
    for it in items:
        page = pages.get(it["anchor"], "")
        rows.append(
            f'<div class="toc-item lv{it["level"]}">'
            f'<span class="num">{it["num"]}</span>'
            f'<span class="txt"><a class="toc-a" href="#{it["anchor"]}">{it["text"]}</a></span>'
            f'<span class="pg">{page}</span></div>'
        )
    return "\n".join(rows)


def post_process(html: str) -> str:
    # 文档内相对链接在 PDF 中无意义，降级为纯文本
    html = re.sub(r'<a href="[^"]*\.md[^"]*">(.*?)</a>', r"\1", html)
    # 勾选清单
    html = re.sub(r"<li>\[ \]\s*", '<li class="task"><span class="cb"></span>', html)
    # 注意 / 提示类段落
    html = re.sub(
        r"<p><strong>(注意|提示|警告|提示语)</strong>",
        r'<p class="callout"><strong>\1</strong>',
        html,
    )
    # 独立成段的「注意」（其后通常跟一组列表）：改为小标题，避免空色块
    html = re.sub(
        r'<p class="callout"><strong>注意</strong></p>',
        '<p class="note-label">注意</p>',
        html,
    )
    html = re.sub(
        r"<p><strong>(关键认知|结论先行|重要)</strong>",
        r'<p class="key"><strong>\1</strong>',
        html,
    )
    return html


def build_html(md_text: str, pages=None) -> str:
    body = markdown.markdown(
        md_text,
        extensions=["tables", "fenced_code", "sane_lists", "attr_list"],
    )
    body = post_process(body)
    body, toc_items = build_toc_and_anchors(body)

    # 开头的引用块（适用范围说明）与首章同页，避免出现半空白页
    body = body.replace('<h2 id="sec-1">', '<h2 id="sec-1" class="first">', 1)
    # 附录 A（自检清单）单独起页
    body = re.sub(
        r'<h2 id="(sec-\d+)">(附录\s*A)',
        r'<h2 id="\1" class="page-break">\2',
        body,
    )

    title_match = re.search(r"<h1>(.*?)</h1>", body, flags=re.S)
    title = strip_tags(title_match.group(1)).strip() if title_match else "文档"
    # 正文不再重复大标题（封面已有）
    body = re.sub(r"<h1>.*?</h1>", "", body, count=1, flags=re.S)

    return f"""<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<title>{title}</title>
<style>{CSS}</style>
</head>
<body>

<section class="cover">
  <div class="cover-head">
    <div class="cover-tag">接入助手 AiLink · 使用手册</div>
    <h1>手机后台保活设置指南</h1>
    <p class="cover-sub">主流品牌防杀后台配置手册<br>Android 8.0 – 16 · 覆盖 12 类主流机型</p>
  </div>
  <div class="cover-body">
    <p class="cover-lede">
      按品牌逐步操作，改完就能稳定收消息。<br>
      全文只需四件事：允许自启动、后台不限电、加入电池白名单、任务列表加锁。
    </p>
    <div class="cover-meta">
      <div><span>适用应用</span><span>接入助手（AiLink）及一切需要长期驻留后台的 Android 应用</span></div>
      <div><span>覆盖品牌</span><span>华为、荣耀、小米 / Redmi、OPPO / 一加 / realme、vivo / iQOO、三星、魅族、努比亚、联想、华硕、传音、Pixel 与类原生</span></div>
      <div><span>阅读方式</span><span>先看第〇、二章，再按第四章自己的手机品牌逐项对照</span></div>
      <div><span>更新日期</span><span>2026 年 10 月</span></div>
    </div>
  </div>
  <div class="cover-foot">
    <span>接入助手项目文档 · docs/ops</span>
    <span>2026-10 版</span>
  </div>
</section>

<section class="toc">
  <h2>目录</h2>
  <div class="toc-list">
    {render_toc(toc_items, pages)}
  </div>
</section>

<main>
{body}
</main>

</body>
</html>
""", toc_items


def render_pdf(html: str, out_path: Path, browser: str, workdir: Path) -> None:
    html_path = workdir / "guide.html"
    html_path.write_text(html, encoding="utf-8")
    cmd = [
        browser,
        "--headless=new",
        "--disable-gpu",
        "--no-sandbox",
        f"--user-data-dir={workdir / 'profile'}",
        "--no-pdf-header-footer",
        "--print-to-pdf-no-header",
        "--virtual-time-budget=8000",
        f"--print-to-pdf={out_path}",
        html_path.as_uri(),
    ]
    result = subprocess.run(cmd, capture_output=True, text=True, timeout=180)
    if not out_path.exists():
        print(result.stdout[-2000:])
        print(result.stderr[-2000:])
        raise SystemExit("PDF 生成失败")


def build_page_map(pdf_path: Path, items) -> dict:
    """从第一次渲染的 PDF 里回读每个标题所在页码（用于目录页码）。"""
    try:
        import pypdfium2 as pdfium
    except Exception:
        print("提示：未安装 pypdfium2，目录不显示页码")
        return {}

    doc = pdfium.PdfDocument(str(pdf_path))
    texts = []
    for page in doc:
        tp = page.get_textpage()
        try:
            raw = tp.get_text_bounded()
        except Exception:
            raw = tp.get_text_range()
        texts.append(re.sub(r"\s+", "", raw))

    # 跳过封面与目录页，避免所有条目都指向目录自身
    start = 1
    while start < len(texts) and "目录" in texts[start] and len(texts[start]) > 150:
        start += 1

    pages = {}
    last = start
    for it in items:
        full = re.sub(r"\s+", "", it["full"])
        short = re.sub(r"\s+", "", it["text"])[:10]
        hit = None
        # 精确匹配标题全文，且页码不得倒退（防止撞上正文里的同名表格行）
        for i in range(max(start, last - 1), len(texts)):
            if full and full in texts[i]:
                hit = i + 1
                break
        if hit is None and len(short) >= 6:
            for i in range(max(start, last - 1), len(texts)):
                if short in texts[i]:
                    hit = i + 1
                    break
        if hit:
            pages[it["anchor"]] = hit
            last = hit
    return pages


def add_bookmarks(pdf_path: Path, items, pages, title: str) -> int:
    """写入 PDF 侧边栏书签（Chrome 打印不生成目录书签）。"""
    try:
        from pypdf import PdfReader, PdfWriter
    except Exception:
        print("提示：未安装 pypdf，跳过书签")
        return 0

    reader = PdfReader(str(pdf_path))
    writer = PdfWriter()
    writer.append(reader)
    writer.add_metadata(
        {
            "/Title": title,
            "/Author": "接入助手 AiLink 项目",
            "/Subject": "手机后台保活设置指南（各品牌防杀后台配置手册）",
            "/Creator": "tools/docs/build-pdf.py",
        }
    )

    parent = None
    count = 0
    for it in items:
        pg = pages.get(it["anchor"])
        if not pg or not (1 <= pg <= len(reader.pages)):
            continue
        num = it["num"]
        sep = "" if (not num or num.endswith(("、", "：", ":"))) else " "
        label = f"{num}{sep}{it['text']}"
        if it["level"] == 2 or parent is None:
            parent = writer.add_outline_item(label, pg - 1)
        else:
            writer.add_outline_item(label, pg - 1, parent=parent)
        count += 1

    with open(pdf_path, "wb") as fh:
        writer.write(fh)
    return count


def main():
    if len(sys.argv) < 2:
        raise SystemExit("用法: build-pdf.py <input.md> [output.pdf]")

    md_path = Path(sys.argv[1]).resolve()
    out_path = Path(sys.argv[2]).resolve() if len(sys.argv) > 2 else md_path.with_suffix(".pdf")

    md_text = md_path.read_text(encoding="utf-8")
    out_path.parent.mkdir(parents=True, exist_ok=True)

    workdir = Path(tempfile.mkdtemp(prefix="ailink-pdf-"))
    browser = find_browser()

    # 第一遍：不带页码，用于测量各章实际落页
    html, items = build_html(md_text)
    draft = workdir / "draft.pdf"
    render_pdf(html, draft, browser, workdir)
    pages = build_page_map(draft, items)

    # 第二遍：把页码写进目录
    html, _ = build_html(md_text, pages)
    render_pdf(html, out_path, browser, workdir)

    doc_title = "手机后台保活设置指南"
    marks = add_bookmarks(out_path, items, pages, doc_title)

    total = "?"
    try:
        import pypdfium2 as pdfium

        total = len(pdfium.PdfDocument(str(out_path)))
    except Exception:
        pass

    size_kb = out_path.stat().st_size / 1024
    hit = len([1 for it in items if it["anchor"] in pages])
    print(f"OK  {out_path}")
    print(f"    共 {total} 页 / {size_kb:.0f} KB / 目录页码命中 {hit}/{len(items)} / 书签 {marks} 条")


if __name__ == "__main__":
    main()
