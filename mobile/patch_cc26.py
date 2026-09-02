import io

p = r"C:\Users\<user>\Desktop\aw-android\mobile\build.gradle"
raw = io.open(p, 'rb').read()
crlf = b'\r\n' in raw
s = raw.decode('utf-8').replace('\r\n', '\n')

old = '            spec.environment("CC_armv7-linux-androideabi", "armv7a-linux-androideabi24-clang")\n            spec.environment("CC_aarch64-linux-android", "aarch64-linux-android24-clang")'
new = ('            // 只用 NDK 提供的 .exe 变体（26）：cc crate 在 Windows 上按 PATHEXT 查找，\n'
       '            // 24 只有无扩展名 + .cmd，会 "failed to find tool"；26 有 .exe 副本。\n'
       '            spec.environment("CC_armv7-linux-androideabi", "armv7a-linux-androideabi26-clang.exe")\n'
       '            spec.environment("CC_aarch64-linux-android", "aarch64-linux-android26-clang.exe")')
assert old in s, "CC anchor not found"
s = s.replace(old, new, 1)

out = s.encode('utf-8')
if crlf:
    out = out.replace(b'\n', b'\r\n')
io.open(p, 'wb').write(out)
print("OK: build.gradle CC -> 26-clang.exe")
