import io

p = "mobile/src/main/java/net/activitywatch/android/todo/TodoModels.kt"   # 相对仓库根
raw = io.open(p, 'rb').read()
crlf = b'\r\n' in raw
s = raw.decode('utf-8').replace('\r\n', '\n')

old = "/** 清单（由 tag 派生，供侧栏展示；color 为语义色资源 id） */\ndata class TodoListInfo(val id: Long, val name: String, val colorRes: Int)"
new = "/** 清单（由 tag 派生，供侧栏展示；color 为 ARGB 色值） */\ndata class TodoListInfo(val id: Long, val name: String, val color: Int)"
assert old in s, "TodoListInfo anchor not found"
s = s.replace(old, new, 1)

out = s.encode('utf-8')
if crlf:
    out = out.replace(b'\n', b'\r\n')
io.open(p, 'wb').write(out)
print("OK")
