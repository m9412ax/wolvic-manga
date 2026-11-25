
# -*- coding: utf-8 -*-
# Pushes local file contents to GitHub via the git/trees API (raw UTF-8 text,
# NOT base64 - the trees API accepts raw content). Creates one commit on main.
import sys, json, urllib.request, urllib.error, time

TOKEN = open(r'C:\Users\Administrator\.gh_pat', encoding='utf-8').read().strip()
REPO = 'm9412ax/wolvic-manga'
API = 'https://api.github.com/repos/' + REPO
opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))

def req(url, method='GET', data=None):
    body = json.dumps(data).encode('utf-8') if data is not None else None
    r = urllib.request.Request(url, data=body, method=method)
    r.add_header('Authorization', 'Bearer ' + TOKEN)
    r.add_header('Accept', 'application/vnd.github+json')
    r.add_header('Content-Type', 'application/json')
    for attempt in range(4):
        try:
            return json.load(opener.open(r, timeout=60))
        except urllib.error.HTTPError as e:
            if e.code in (429, 500, 502, 503):
                time.sleep(3 * (attempt + 1)); continue
            raise
        except Exception:
            time.sleep(3 * (attempt + 1))
    raise RuntimeError('request failed: ' + url)

message = sys.argv[1]
paths = sys.argv[2:]

ref = req(API + '/git/ref/heads/main')
base_sha = ref['object']['sha']
commit = req(API + '/git/commits/' + base_sha)
base_tree = commit['tree']['sha']

tree = []
for p in paths:
    content = open(p, 'r', encoding='utf-8').read()
    tree.append({'path': p, 'mode': '100644', 'type': 'blob', 'content': content})

new_tree = req(API + '/git/trees', 'POST', {'base_tree': base_tree, 'tree': tree})
new_commit = req(API + '/git/commits', 'POST', {
    'message': message, 'tree': new_tree['sha'], 'parents': [base_sha]})
req(API + '/git/refs/heads/main', 'PATCH', {'sha': new_commit['sha'], 'force': False})
print('pushed', new_commit['sha'][:10], '-', message)
