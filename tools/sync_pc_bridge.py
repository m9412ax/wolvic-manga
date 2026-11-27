#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
MangaHub VR  <->  PC bridge for the `manga-sync` folder.

The VR app writes <sync folder>/mangahub-sync.json with cross-device book ids
that look like PC paths, e.g. "E:/Manga/书/第01话". This script converts that
file to/from the local viewer's own data:

  * NeeView  : %LOCALAPPDATA%/NeeView/History.json (portable: <exe>/Profile/History.json)
  * Mihon    : a JSON export compatible with "Mihon Backup Viewer"
               (use the viewer to turn it into a .tachibk, then restore in Mihon)

Mihon has no real cross-device sync and NeeView has no per-chapter progress, so
this bridge maps:
  VR chapter mark  -> NeeView history entry (book = chapter folder, page index)
  VR chapter mark  -> Mihon BackupChapter(read / lastPageRead) + BackupHistory

Examples
--------
  # VR -> PC (update NeeView history; run while NeeView is CLOSED)
  python sync_pc_bridge.py --sync-dir E:/manga-sync --pc-root E:/ \
      --to-neeview "$LOCALAPPDATA/NeeView/History.json"

  # PC -> VR (read NeeView history back into the sync file)
  python sync_pc_bridge.py --sync-dir E:/manga-sync --pc-root E:/ \
      --from-neeview "$LOCALAPPDATA/NeeView/History.json"

  # VR -> Mihon Backup Viewer JSON (then convert/restore in Mihon)
  python sync_pc_bridge.py --sync-dir E:/manga-sync --pc-root E:/ \
      --to-mihon-json mihon-import.json --mihon-source-id 0

Only stdlib is used. The sync file is always backed up to
<sync-dir>/mangahub-sync.bak.json before being rewritten.
"""

import argparse
import datetime as _dt
import json
import os
import shutil
import sys
import time
from pathlib import Path

FORMAT = "mangahub-sync/1"
FILE_NAME = "mangahub-sync.json"
LOCAL_SOURCE_ID = 0  # Mihon's Local source id is 0 in current releases


def now_ms():
    return int(_dt.datetime.now(_dt.timezone.utc).timestamp() * 1000)


def load_sync(path):
    if not path.exists():
        return {"format": FORMAT, "device": "PC", "updatedAt": now_ms(),
                "chapters": [], "history": [], "books": []}
    with path.open("r", encoding="utf-8") as f:
        data = json.load(f)
    data.setdefault("chapters", [])
    data.setdefault("history", [])
    data.setdefault("books", [])
    return data


def save_sync(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    if path.exists():
        shutil.copy2(path, path.with_name("mangahub-sync.bak.json"))
    data["format"] = FORMAT
    data["updatedAt"] = now_ms()
    tmp = path.with_suffix(".json.tmp")
    tmp.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")
    os.replace(tmp, path)


def pc_path(sync_id, pc_root):
    """'E:/Manga/书/第01话' + 'E:/' -> 'E:\\Manga\\书\\第01话'."""
    raw = str(sync_id).replace("\\", "/")
    root = str(pc_root).replace("\\", "/")
    if root and raw.lower().startswith(root.lower().rstrip("/")):
        raw = raw[len(root.rstrip("/")):].lstrip("/")
    return str(Path(root) / raw) if root else raw.replace("/", os.sep)


def sync_id(pc_path_value, pc_root):
    raw = str(pc_path_value).replace("\\", "/")
    root = str(pc_root).replace("\\", "/").rstrip("/")
    if root and raw.lower().startswith(root.lower()):
        raw = raw[len(root):].lstrip("/")
        return (root + "/" + raw).rstrip("/") if raw else root
    return raw.strip("/")


def iso(ts_ms):
    if not ts_ms:
        return _dt.datetime.now().astimezone().isoformat(timespec="seconds")
    return _dt.datetime.fromtimestamp(ts_ms / 1000,
                                      _dt.timezone.utc).astimezone().isoformat(timespec="seconds")


# ----------------------------------------------------------------- NeeView

def neeview_entries(path):
    """Returns (container, list-of-entries) so we can preserve the file's shape."""
    data = json.loads(Path(path).read_text(encoding="utf-8-sig"))
    if isinstance(data, list):
        return data, data
    for key in ("History", "history", "Items", "items"):
        if isinstance(data.get(key), list):
            return data, data[key]
    # Unknown wrapper: keep it and add our own key.
    data["History"] = []
    return data, data["History"]


def book_of(chapter_id):
    """'E:/Manga/书/第01话' -> 'E:/Manga/书' (a book/folder in NeeView)."""
    parts = chapter_id.rstrip("/").split("/")
    return "/".join(parts[:-1]) if len(parts) > 1 else chapter_id


def to_neev(sync, sync_dir, pc_root, history_path):
    container, entries = neeview_entries(history_path)
    by_path = {}
    for entry in entries:
        if isinstance(entry, dict) and entry.get("path"):
            by_path[str(entry["path"]).lower()] = entry

    added = updated = 0
    # Only the single most recent mark is mirrored into NeeView history.
    latest = sorted(sync["chapters"], key=lambda c: int(c.get("ts") or 0), reverse=True)[:1]
    for chapter in latest:
        cid = chapter.get("id", "")
        if not cid or "://" in cid or cid.startswith("115:"):
            continue  # no local Windows path for online books
        folder = pc_path(book_of(cid), pc_root)
        folder_lower = folder.lower()
        entry = by_path.get(folder_lower)
        if entry is None:
            entry = {"path": folder, "name": os.path.basename(folder),
                     "last_page": 0, "total_pages": 0, "last_access": iso(chapter.get("ts"))}
            entries.append(entry)
            by_path[folder_lower] = entry
            added += 1
        else:
            updated += 1
        total = int(chapter.get("total") or 0)
        page = int(chapter.get("page") or 0)
        # NeeView page_index is 0-based; keep the furthest position.
        entry["last_page"] = max(int(entry.get("last_page") or 0), page)
        if total:
            entry["total_pages"] = max(int(entry.get("total_pages") or 0), total)
        stamp = iso(chapter.get("ts"))
        if str(entry.get("last_access", "")) < stamp:
            entry["last_access"] = stamp

    tmp = history_path + ".tmp"
    Path(tmp).write_text(json.dumps(container, ensure_ascii=False, indent=2), encoding="utf-8")
    shutil.copy2(history_path, history_path + ".bak.json")
    os.replace(tmp, history_path)
    print(f"NeeView: {added} added, {updated} updated -> {history_path}")
    print("NeeView reloads History.json automatically, but close/restart it to be safe.")


def from_neev(sync, pc_root, history_path):
    _, entries = neeview_entries(history_path)
    local = {str(c.get("id", "")).lower(): c for c in sync["chapters"]}
    added = updated = 0
    # NeeView's history can hold thousands of books; only the newest one is
    # pushed to the VR device.
    entries = sorted([e for e in entries if isinstance(e, dict) and e.get("path")],
                     key=lambda e: str(e.get("last_access") or ""), reverse=True)[:1]
    for entry in entries:
        if not isinstance(entry, dict):
            continue
        folder = str(entry.get("path") or "")
        if not folder:
            continue
        # A NeeView "book" is a folder of images: one chapter of the VR book.
        page = int(entry.get("last_page") or 0)
        total = int(entry.get("total_pages") or 0)
        stamp = entry.get("last_access") or ""
        ts = 0
        if stamp:
            try:
                ts = int(_dt.datetime.fromisoformat(stamp).timestamp() * 1000)
            except ValueError:
                pass
        cid = sync_id(folder, pc_root)
        key = cid.lower()
        existing = local.get(key)
        if existing is None:
            sync["chapters"].append({"id": cid, "page": page, "total": total, "ts": ts})
            added += 1
        elif ts > int(existing.get("ts") or 0):
            existing["page"] = page
            existing["total"] = total
            existing["ts"] = ts
            updated += 1
        # History row for the parent book.
        book = book_of(cid)
        if not any(h.get("id") == book for h in sync["history"]):
            sync["history"].append({"id": book, "title": os.path.basename(book),
                                    "source": "LOCAL", "page": page, "total": total, "ts": ts})
    print(f"VR sync: {added} chapters added, {updated} updated from NeeView")


# ------------------------------------------------------------------- Mihon

def to_mihon(sync, out_path, source_id):
    """Produces the JSON shape used by Mihon Backup Viewer (proto field names)."""
    chapters_by_book = {}
    for c in sync["chapters"]:
        chapters_by_book.setdefault(book_of(c.get("id", "")), []).append(c)
    manga = []
    for book, chapters in chapters_by_book.items():
        backup_chapters = []
        for index, c in enumerate(chapters):
            total = int(c.get("total") or 0)
            page = int(c.get("page") or 0)
            backup_chapters.append({
                "url": c["id"],
                "name": os.path.basename(c["id"]),
                "read": bool(total and page >= total - 1),
                "lastPageRead": page,
                "chapterNumber": index + 1,
                "sourceOrder": index,
            })
        history = [{
            "url": c["id"],
            "lastRead": int(c.get("ts") or now_ms()),
            "readDuration": 0,
        } for c in chapters if c.get("ts")]
        manga.append({
            "source": source_id,
            "url": book,
            "title": os.path.basename(book),
            "favorite": book in sync["books"],
            "chapters": backup_chapters,
            "history": history,
        })
    Path(out_path).write_text(json.dumps({"backupManga": manga}, ensure_ascii=False, indent=2),
                              encoding="utf-8")
    print(f"Mihon JSON: {len(manga)} series -> {out_path}")
    print("Open it with Mihon Backup Viewer, export .tachibk, then restore in Mihon 0.21.x.")


def from_mihon(sync, in_path):
    data = json.loads(Path(in_path).read_text(encoding="utf-8-sig"))
    manga = data.get("backupManga") if isinstance(data, dict) else data
    local = {str(c.get("id", "")).lower(): c for c in sync["chapters"]}
    added = updated = 0
    for entry in manga or []:
        book = entry.get("url", "")
        if not book:
            continue
        if entry.get("favorite") and book not in sync["books"]:
            sync["books"].append(book)
        for chapter in entry.get("chapters") or []:
            cid = chapter.get("url") or book
            key = str(cid).lower()
            page = int(chapter.get("lastPageRead") or 0)
            total = int(chapter.get("totalPages") or 0)
            read = bool(chapter.get("read"))
            if read and total:
                page = max(page, total - 1)
            existing = local.get(key)
            if existing is None:
                sync["chapters"].append({"id": cid, "page": page, "total": total, "ts": now_ms()})
                added += 1
            elif page > int(existing.get("page") or 0):
                existing["page"] = page
                existing["total"] = max(total, int(existing.get("total") or 0))
                existing["ts"] = now_ms()
                updated += 1
    print(f"VR sync: {added} chapters added, {updated} updated from Mihon JSON")


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--sync-dir", required=True, help="the manga-sync folder on the PC")
    parser.add_argument("--pc-root", default="", help="PC root matching the VR mapping, e.g. E:/")
    action = parser.add_mutually_exclusive_group(required=True)
    action.add_argument("--to-neeview", metavar="HISTORY_JSON")
    action.add_argument("--from-neeview", metavar="HISTORY_JSON")
    action.add_argument("--to-mihon-json", metavar="OUT_JSON")
    action.add_argument("--from-mihon-json", metavar="IN_JSON")
    action.add_argument("--watch-neeview", metavar="HISTORY_JSON",
                        help="stay running and write back as soon as NeeView saves History.json")
    parser.add_argument("--interval", type=int, default=5,
                        help="watch poll interval in seconds (default 5)")
    parser.add_argument("--mihon-source-id", type=int, default=LOCAL_SOURCE_ID)
    args = parser.parse_args()

    sync_file = Path(args.sync_dir) / FILE_NAME
    sync = load_sync(sync_file)
    if args.to_neeview:
        to_neev(sync, Path(args.sync_dir), args.pc_root, args.to_neeview)
    elif args.from_neeview:
        from_neev(sync, args.pc_root, args.from_neeview)
        save_sync(sync_file, sync)
    elif args.to_mihon_json:
        to_mihon(sync, args.to_mihon_json, args.mihon_source_id)
    elif args.from_mihon_json:
        from_mihon(sync, args.from_mihon_json)
        save_sync(sync_file, sync)
    elif args.watch_neeview:
        last = 0.0
        print(f"watching {args.watch_neeview} every {args.interval}s (Ctrl+C to stop)")
        while True:
            try:
                mtime = Path(args.watch_neeview).stat().st_mtime
                if mtime != last:
                    if last:
                        from_neev(sync, args.pc_root, args.watch_neeview)
                        save_sync(sync_file, sync)
                    last = mtime
            except Exception as exc:
                print(f"watch error: {exc}", file=sys.stderr)
            time.sleep(max(1, args.interval))
    print(f"sync file: {sync_file}")


if __name__ == "__main__":
    main()
