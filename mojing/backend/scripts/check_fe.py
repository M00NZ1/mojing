import urllib.request
resp = urllib.request.urlopen("http://localhost:5175", timeout=5)
html = resp.read().decode()
print("size:", len(html))
print("has root:", "root" in html)
print("has app-shell:", "app-shell" in html)
