#!/usr/bin/env python3
"""Crawler / extractor cho BTL IT4863 - Phan B.

Input: mot danh sach URL - truyen qua 1 file text (moi dong 1 URL, dong bat dau
bang '#' bi bo qua) hoac truyen truc tiep nhieu URL qua argv.

Output: crawler/output/corpus.json - mang document theo schema:
    {
      "id": "...",
      "title": "...",
      "content": "...",
      "url": "...",
      "outlinks": [
        {"title": "...", "content": "...", "url": "..."},
        ...
      ]
    }

Moi outlink cung duoc crawl that (1 cap, khong de quy them) de lay title/content that,
cung dinh dang voi tai lieu chinh - khong chi lay chu trong the <a>. De tranh 1 trang co
qua nhieu lien ket lam crawl chay qua lau (vi du trang Wikipedia co hang tram link), so
outlink duoc crawl moi trang bi gioi han boi MAX_OUTLINKS_PER_PAGE. URL da crawl (ca URL
chinh lan outlink) duoc cache lai trong 1 lan chay de khong tai trung.

corpus.json co the nap thang vao search-api qua:
    POST /api/documents (multipart, file .json)
hoac copy vao search-api/data/raw/ roi goi POST /api/documents/import-raw.

Usage:
    python crawler.py urls.txt
    python crawler.py https://a.com/bai-1 https://b.com/bai-2
"""
from __future__ import annotations

import hashlib
import json
import sys
from pathlib import Path
from urllib.parse import urljoin

import requests
from bs4 import BeautifulSoup

OUTPUT_PATH = Path(__file__).parent / "output" / "corpus.json"
TIMEOUT_SECONDS = 10
USER_AGENT = "IT4863-BTL-Crawler/1.0"
# Gioi han so outlink duoc crawl that moi trang, tranh 1 trang co qua nhieu link
# (vi du bai Wikipedia co the co hang tram outlink) lam crawl chay qua lau.
MAX_OUTLINKS_PER_PAGE = 10


def extract_title(soup: BeautifulSoup) -> str:
    og_title = soup.find("meta", property="og:title")
    if og_title and og_title.get("content"):
        return og_title["content"].strip()
    if soup.title and soup.title.string:
        return soup.title.string.strip()
    h1 = soup.find("h1")
    if h1:
        return h1.get_text(strip=True)
    return ""


def extract_content(soup: BeautifulSoup):
    """Tra ve (noi dung van ban chinh, the chua noi dung do) de tai su dung khi tim outlink."""
    for tag in soup(["script", "style", "nav", "footer", "header", "aside"]):
        tag.decompose()
    main = soup.find("article") or soup.find("main") or soup.body or soup
    paragraphs = [p.get_text(" ", strip=True) for p in main.find_all("p")]
    text = "\n".join(p for p in paragraphs if p)
    return (text or main.get_text(" ", strip=True)), main


def extract_outlink_urls(main, base_url: str) -> list[str]:
    """Danh sach URL tuyet doi, da khu trung, cac lien ket <a href> trong vung noi dung."""
    urls: list[str] = []
    seen = {base_url}
    for a in main.find_all("a", href=True):
        href = a["href"].strip()
        if not href or href.startswith("#"):
            continue
        if href.lower().startswith(("javascript:", "mailto:", "tel:")):
            continue
        full_url = urljoin(base_url, href)
        if full_url in seen:
            continue
        seen.add(full_url)
        urls.append(full_url)
    return urls


def make_id(url: str) -> str:
    return hashlib.sha1(url.encode("utf-8")).hexdigest()[:12]


def fetch_page_basic(url: str, cache: dict) -> dict | None:
    """Tai 1 URL va tra ve {"title", "content"} - dung chung cho tai lieu chinh va outlink.
    Ket qua duoc cache theo url de khong tai trung trong 1 lan chay crawler."""
    if url in cache:
        return cache[url]

    try:
        response = requests.get(url, headers={"User-Agent": USER_AGENT}, timeout=TIMEOUT_SECONDS)
        response.raise_for_status()
    except requests.RequestException as exc:
        print(f"    [SKIP outlink] {url}: {exc}", file=sys.stderr)
        cache[url] = None
        return None

    soup = BeautifulSoup(response.text, "html.parser")
    content, _ = extract_content(soup)
    page = {"title": extract_title(soup), "content": content}
    cache[url] = page
    return page


def crawl_outlinks(outlink_urls: list[str], cache: dict) -> list[dict]:
    outlinks = []
    for url in outlink_urls[:MAX_OUTLINKS_PER_PAGE]:
        print(f"    Crawling outlink: {url}")
        page = fetch_page_basic(url, cache)
        if page is None:
            continue
        outlinks.append({"title": page["title"], "content": page["content"], "url": url})
    return outlinks


def crawl_one(url: str, cache: dict) -> dict | None:
    try:
        response = requests.get(url, headers={"User-Agent": USER_AGENT}, timeout=TIMEOUT_SECONDS)
        response.raise_for_status()
    except requests.RequestException as exc:
        print(f"[SKIP] {url}: {exc}", file=sys.stderr)
        return None

    soup = BeautifulSoup(response.text, "html.parser")
    content, main = extract_content(soup)
    title = extract_title(soup)
    cache[url] = {"title": title, "content": content}

    outlink_urls = extract_outlink_urls(main, url)
    return {
        "id": make_id(url),
        "title": title,
        "content": content,
        "url": url,
        "outlinks": crawl_outlinks(outlink_urls, cache),
    }


def load_urls(args: list[str]) -> list[str]:
    if len(args) == 1 and Path(args[0]).is_file():
        text = Path(args[0]).read_text(encoding="utf-8")
        return [line.strip() for line in text.splitlines() if line.strip() and not line.startswith("#")]
    return args


def main() -> None:
    if len(sys.argv) < 2:
        print("Usage: python crawler.py <urls.txt | url1 url2 ...>", file=sys.stderr)
        sys.exit(1)

    urls = load_urls(sys.argv[1:])
    if not urls:
        print("No URLs to crawl.", file=sys.stderr)
        sys.exit(1)

    cache: dict = {}
    documents = []
    for url in urls:
        print(f"Crawling {url} ...")
        doc = crawl_one(url, cache)
        if doc:
            documents.append(doc)

    OUTPUT_PATH.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT_PATH.write_text(json.dumps(documents, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"Wrote {len(documents)} document(s) to {OUTPUT_PATH}")


if __name__ == "__main__":
    main()
