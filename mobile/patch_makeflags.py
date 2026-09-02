import io

p = r"C:\Users\<user>\Desktop\aw-android\mobile\build.gradle"
raw = io.open(p, 'rb').read()
crlf = b'\r\n' in raw
s = raw.decode('utf-8').replace('\r\n', '\n')

old = '            spec.environment("MAKEFLAGS", "-j8")'
new = ('            // -j8 与双 target 并行曾把系统拖到无响应；降为 -j4，arm/arm64 改为串行构建。\n'
       '            spec.environment("MAKEFLAGS", "-j4")')
assert old in s, "MAKEFLAGS line not found"
s = s.replace(old, new, 1)

out = s.encode('utf-8')
if crlf:
    out = out.replace(b'\n', b'\r\n')
io.open(p, 'wb').write(out)
print("OK: MAKEFLAGS -j8 -> -j4")
