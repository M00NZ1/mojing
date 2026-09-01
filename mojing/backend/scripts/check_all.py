import urllib.request, json

# 人物页所有API调用
urls = [
    "http://localhost:8000/api/characters",
    "http://localhost:8000/api/characters/templates/defaults",
    "http://localhost:8000/api/providers/catalog",
    "http://localhost:8000/api/assets",
    "http://localhost:8000/api/assets?category=avatar",
    "http://localhost:8000/api/voices",
    "http://localhost:8000/api/costs?days=30",
    "http://localhost:8000/api/jobs",
]
for url in urls:
    label = url.split("/api/")[1]
    try:
        req = urllib.request.Request(url)
        resp = urllib.request.urlopen(req, timeout=5)
        d = json.loads(resp.read())
        print(f"200 {label}  ({type(d).__name__})")
    except urllib.error.HTTPError as e:
        body = e.read().decode()
        print(f"{e.code} {label} -> {body[:200]}")
    except Exception as e:
        print(f"ERR {label} -> {e}")
