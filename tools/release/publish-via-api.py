#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
GitHub API 发布工具：当 github.com 的 443 被阻断（国内常见）、
但 api.github.com 仍可达时，用 REST API 完成 git push 与 Release 发布。

用法（token 只在本机环境变量里，不写入任何文件）：

    # 1) 把本地分支推成远端 main
    export GITHUB_TOKEN=ghp_xxx
    python tools/release/publish-via-api.py push --local public

    # 2) 创建 Release 并上传 APK
    python tools/release/publish-via-api.py release \
        --tag v1.1.0 \
        --name "v1.1.0 · 首个正式版" \
        --body-file archive/release-body-v1.1.0.md \
        --asset "release/接入助手-1.1.0.apk"

    # 3) 只做连通性与权限检查
    python tools/release/publish-via-api.py check

Token 权限：fine-grained token 需 Contents(Read and write)；classic token 需 repo 权限。
只申请最小权限，用完可在 GitHub 设置里撤销。
"""
import argparse
import base64
import json
import os
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request

API = "https://api.github.com"
UPLOADS = "https://uploads.github.com"
UA = "AiLink-Publish-Tool"


def token_from_env() -> str:
    token = os.environ.get("GITHUB_TOKEN", "").strip()
    if not token:
        sys.exit("缺少 GITHUB_TOKEN 环境变量。\nWindows PowerShell: $env:GITHUB_TOKEN=\"ghp_xxx\"")
    return token


def request(method: str, url: str, token: str, payload=None, raw: bytes = None,
            content_type: str = "application/json", timeout: int = 120):
    data = raw if raw is not None else (
        json.dumps(payload).encode("utf-8") if payload is not None else None
    )
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Authorization", f"Bearer {token}")
    req.add_header("Accept", "application/vnd.github+json")
    req.add_header("X-GitHub-Api-Version", "2022-11-28")
    req.add_header("User-Agent", UA)
    if data is not None:
        req.add_header("Content-Type", content_type)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            body = resp.read()
            return json.loads(body) if body else {}
    except urllib.error.HTTPError as e:
        detail = e.read().decode("utf-8", "ignore")[:800]
        sys.exit(f"HTTP {e.code} {method} {url}\n{detail}")


def git(*args: str, cwd: str = None) -> str:
    out = subprocess.run(["git", *args], cwd=cwd, capture_output=True, text=True)
    if out.returncode != 0:
        sys.exit(f"git {' '.join(args)} 失败：{out.stderr.strip()}")
    return out.stdout.strip()


def cmd_check(args, token):
    me = request("GET", f"{API}/user", token)
    print(f"token 属主：{me.get('login')}")
    repo = request("GET", f"{API}/repos/{args.repo}", token)
    print(f"仓库：{repo['full_name']}（private={repo['private']}）")
    ref = request("GET", f"{API}/repos/{args.repo}/git/ref/heads/{args.branch}", token)
    print(f"{args.branch} 当前指向：{ref['object']['sha'][:10]}")


def cmd_push(args, token):
    if args.dry_run:
        base_sha = git("rev-parse", f"{args.local}^", cwd=args.workdir)
        base_tree = None
    else:
        base_ref = request("GET", f"{API}/repos/{args.repo}/git/ref/heads/{args.branch}", token)
        base_sha = base_ref["object"]["sha"]
        base_tree = request("GET", f"{API}/repos/{args.repo}/git/commits/{base_sha}", token)["tree"]["sha"]

    tip = git("rev-parse", args.local, cwd=args.workdir)
    message = git("log", "-1", "--format=%B", args.local, cwd=args.workdir).strip()

    # 远端 main 必须正好是本地分支的父提交，否则说明远端被改过，需人工确认
    parent = git("rev-parse", f"{args.local}^", cwd=args.workdir)
    if parent != base_sha:
        sys.exit(
            f"远端 {args.branch}（{base_sha[:10]}）与本地父提交（{parent[:10]}）不一致。\n"
            "请确认远端没有新提交后再试；本工具不做强制覆盖。"
        )

    changes = [l for l in git("diff", "-r", "--name-status", base_sha, args.local,
                             cwd=args.workdir).splitlines() if l.strip()]
    if not changes:
        print("没有需要推送的变更。")
        return

    print(f"推送到 {args.repo}@{args.branch}：{len(changes)} 个文件变更"
          f"{'（dry-run，不实际调用 API）' if args.dry_run else ''}")
    entries = []
    for line in changes:
        parts = line.split("\t")
        status, path = parts[0], parts[-1]
        if status.startswith("D"):
            entries.append({"path": path, "mode": "100644", "type": "blob", "sha": None})
            print(f"  - 删除 {path}")
            continue
        full = os.path.join(args.workdir, path)
        mode = git("ls-tree", args.local, "--", path, cwd=args.workdir).split()[0]
        print(f"  {'+' if status.startswith('A') else '~'} {path}（{mode}）")
        if args.dry_run:
            continue
        with open(full, "rb") as fh:
            content = base64.b64encode(fh.read()).decode("ascii")
        blob = request("POST", f"{API}/repos/{args.repo}/git/blobs", token,
                       {"content": content, "encoding": "base64"})
        entries.append({"path": path, "mode": mode, "type": "blob", "sha": blob["sha"]})

    if args.dry_run:
        print(f"dry-run 完成：本地 {tip[:10]}，父提交 {parent[:10]}，共 {len(changes)} 个变更")
        return

    tree = request("POST", f"{API}/repos/{args.repo}/git/trees", token,
                   {"base_tree": base_tree, "tree": entries})
    commit = request("POST", f"{API}/repos/{args.repo}/git/commits", token,
                     {"message": message, "tree": tree["sha"], "parents": [base_sha]})
    request("PATCH", f"{API}/repos/{args.repo}/git/refs/heads/{args.branch}", token,
            {"sha": commit["sha"], "force": False})

    print(f"完成：{args.branch} -> {commit['sha'][:10]}")
    print(f"https://github.com/{args.repo}/commit/{commit['sha']}")


def cmd_release(args, token):
    body = ""
    if args.body_file:
        with open(args.body_file, "r", encoding="utf-8") as fh:
            body = fh.read()

    release = request("POST", f"{API}/repos/{args.repo}/releases", token, {
        "tag_name": args.tag,
        "target_commitish": args.branch,
        "name": args.name or args.tag,
        "body": body,
        "draft": False,
        "prerelease": False,
    })
    print(f"Release 已创建：{release['html_url']}")

    for asset_path in args.asset or []:
        filename = os.path.basename(asset_path)
        size = os.path.getsize(asset_path)
        print(f"上传 {filename}（{size / 1024 / 1024:.1f} MB）…")
        with open(asset_path, "rb") as fh:
            data = fh.read()
        quote = urllib.parse.quote(filename)
        request("POST",
                f"{UPLOADS}/repos/{args.repo}/releases/{release['id']}/assets?name={quote}",
                token, raw=data, content_type="application/vnd.android.package-archive",
                timeout=1800)
        print(f"  已上传 {filename}")


def main():
    parser = argparse.ArgumentParser(description="GitHub API 推送 / 发布工具")
    parser.add_argument("command", choices=["check", "push", "release"])
    parser.add_argument("--repo", default="wenshi0721/ailink")
    parser.add_argument("--branch", default="main")
    parser.add_argument("--local", default="public", help="push: 本地分支名")
    parser.add_argument("--workdir", default=os.getcwd())
    parser.add_argument("--tag")
    parser.add_argument("--name")
    parser.add_argument("--body-file")
    parser.add_argument("--asset", action="append", help="release: 可重复传多个附件")
    parser.add_argument("--dry-run", action="store_true", help="push: 只打印变更，不调用 API")
    args = parser.parse_args()

    token = "" if (args.command == "push" and args.dry_run) else token_from_env()
    if args.command == "check":
        cmd_check(args, token)
    elif args.command == "push":
        cmd_push(args, token)
    else:
        if not args.tag:
            sys.exit("release 需要 --tag")
        cmd_release(args, token)


if __name__ == "__main__":
    main()
