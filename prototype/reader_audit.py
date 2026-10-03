"""Lays out arXiv HTML papers as the app's reader view does, at phone width, and lists clipping.

The reader view promises that a paper only ever scrolls down: anything wider than the screen
scrolls inside its own box or wraps. This checks the promise. Each paper is reduced to its
article and given the app's stylesheet (assets/reader/ar5iv.min.css) and its own rules, read
out of ArticleView.kt so the two cannot drift apart, then laid out by headless Chromium 412 px
wide. Anything past either edge that is not inside a box that scrolls is reported.

    python3 reader_audit.py --fetch 3          # three papers each from a dozen fields
    python3 reader_audit.py 1.0 1.3 1.75       # audit at three text sizes

Needs a Chromium: chromium, chromium-browser, google-chrome, or the Flathub one.
"""
from __future__ import annotations
import argparse, glob, html, json, os, re, shutil, subprocess, time, urllib.request
from pathlib import Path

ROOT = Path(__file__).parent
APP = ROOT.parent / "android/app/src/main"
OUT = ROOT / "out/reader"
UA = "Aftergleam-research (mailto:user@aftergleam.app)"
FIELDS = ["cs.CV", "cs.LG", "cs.CL", "math.AG", "hep-th", "cond-mat.mtrl-sci", "astro-ph.GA",
          "q-bio.NC", "stat.ME", "physics.optics", "econ.EM", "quant-ph"]

PROBE = r"""<script>
addEventListener('load', () => {
  const W = document.documentElement.clientWidth, out = [];
  const contained = el => { for (let a = el.parentElement; a && a !== document.body; a = a.parentElement) {
      if (['auto', 'scroll', 'hidden', 'clip'].includes(getComputedStyle(a).overflowX)) return true; }
    return false; };
  const past = r => r.right > W + 1 || r.left < -1;
  for (const el of document.querySelectorAll('.ltx_page_content *')) {
    const r = el.getBoundingClientRect();
    if (!r.width || !past(r) || contained(el) || getComputedStyle(el).visibility === 'hidden') continue;
    let p = el.parentElement, outer = true;
    while (p && !p.classList.contains('ltx_page_content')) {
      if (past(p.getBoundingClientRect())) { outer = false; break; } p = p.parentElement; }
    if (outer) out.push({tag: el.tagName, cls: String(el.className.baseVal ?? el.className),
      width: Math.round(r.width), left: Math.round(r.left), text: (el.textContent || '').trim().slice(0, 60)});
  }
  const pre = document.createElement('pre'); pre.id = 'audit'; pre.textContent = JSON.stringify(out);
  document.body.appendChild(pre);
});
</script>"""


def get(url: str) -> tuple[int, str]:
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            return r.status, r.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, ""


def fetch(per_field: int) -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    for cat in FIELDS:
        _, listing = get(f"https://arxiv.org/list/{cat}/new?skip=0&show=25")
        ids = [m.group(1) for it in listing.split("<dt>")[1:]
               if "/html/" in it and (m := re.search(r"/abs/([0-9.]+)", it))][:per_field]
        for pid in ids:
            path = OUT / f"{pid}.html"
            if path.exists():
                continue
            status, page = get(f"https://arxiv.org/html/{pid}")
            print(cat, pid, status, len(page))
            if status == 200:
                path.write_text(page)
            time.sleep(1)


def theme() -> str:
    """The app's rules over ar5iv's, with its colours filled in."""
    src = (APP / "java/si/jakobkreft/aftergleam/ui/ArticleView.kt").read_text()
    css = re.search(r'private fun theme\(s: Style\) = """(.*?)"""\.trimIndent\(\)', src, re.S).group(1)
    css = re.sub(r"\$\{hex\(s\.\w+\)\}", "#777777", css)
    css = re.sub(r"\$\{if \(s\.serif\)[^}]*\}", "serif", css)
    assert "${" not in css, "a template the audit does not know how to fill"
    return css


def article(page: str) -> str | None:
    """As ArticleStore.extract and ArticleDocument.widen do it."""
    m = re.search(r"<article\b[^>]*\bltx_document\b[^>]*>", page)
    if not m:
        return None
    body = page[m.start():page.rfind("</article>") + len("</article>")]
    body = re.sub(r"<script\b[\s\S]*?</script\s*>", "", body, flags=re.I)
    body = re.sub(r"<(?:link|meta|base)\b[^>]*>", "", body, flags=re.I)

    def widen(m: re.Match) -> str:
        tag = m.group(1)
        tex = re.search(r'alttext="([^"]*)"', tag)
        long = tex and len(tex.group(1)) >= 30 and 'display="block"' not in tag
        return f'<span class="aftergleam-wide">{m.group(0)}</span>' if long else m.group(0)

    return re.sub(r"(<math\b[^>]*>)[\s\S]*?</math>", widen, body)


def chromium() -> list[str]:
    for name in ("chromium", "chromium-browser", "google-chrome"):
        if shutil.which(name):
            return [name]
    return ["flatpak", "run", f"--filesystem={OUT}", "org.chromium.Chromium"]


def audit(scale: float) -> None:
    css = (APP / "assets/reader/ar5iv.min.css").read_text()
    rules, browser, clean, papers = theme(), chromium(), 0, 0
    for f in sorted(glob.glob(str(OUT / "*.html"))):
        body = article(Path(f).read_text())
        if body is None:
            continue
        papers += 1
        doc = OUT / "laid-out.htm"
        doc.write_text(
            '<!DOCTYPE html><html lang="en" data-theme="light"><head><meta charset="utf-8">'
            '<meta name="viewport" content="width=device-width, initial-scale=1">'
            f"<style>{css}</style><style>{rules}</style>"
            # The reader's text size, as the web view's textZoom applies it: to every font size.
            f"<style>:root{{font-size:{16 * scale}px}}</style></head>"
            '<body><div class="ltx_page_main"><div class="ltx_page_content">'
            f"{body}</div></div>{PROBE}</body></html>")
        r = subprocess.run(browser + ["--headless", "--disable-gpu", "--window-size=412,900",
                                      "--virtual-time-budget=3000", "--dump-dom", doc.as_uri()],
                           capture_output=True, text=True, timeout=180)
        m = re.search(r'<pre id="audit">(.*?)</pre>', r.stdout, re.S)
        found = json.loads(html.unescape(m.group(1))) if m else None
        if found == []:
            clean += 1
            continue
        print(f"{Path(f).stem:14}", "no result" if found is None else
              "; ".join(f"{i['tag']}.{i['cls'][:40]} {i['width']}px at {i['left']} '{i['text'][:40]}'"
                        for i in found[:3]))
    print(f"text at {scale:.0%}: {clean} of {papers} papers with nothing cut off")


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("scales", nargs="*", type=float, default=[1.0])
    ap.add_argument("--fetch", type=int, default=0, help="papers to fetch per field first")
    args = ap.parse_args()
    if args.fetch:
        fetch(args.fetch)
    for s in args.scales:
        audit(s)
