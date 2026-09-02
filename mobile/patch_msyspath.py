import io

p = r"C:\Users\<user>\Desktop\aw-android\mobile\build.gradle"
raw = io.open(p, 'rb').read()
crlf = b'\r\n' in raw
s = raw.decode('utf-8').replace('\r\n', '\n')

old = '            spec.environment("PATH", ndkBin + File.pathSeparator + (System.getenv("PATH") ?: ""))'
new = ('            // openssl-src 用 MSYS 的 make 跑 OpenSSL，make 需要 sh 在 PATH 里才能执行\n'
       '            // recipes；clang.exe 归一化后也从 PATH 找。故把 NDK bin 和 MSYS bin 都前置到 PATH。\n'
       '            def msysBin = "C:/msys64/usr/bin"\n'
       '            spec.environment("PATH", ndkBin + File.pathSeparator + msysBin + File.pathSeparator + (System.getenv("PATH") ?: ""))')
assert old in s, "PATH line not found"
s = s.replace(old, new, 1)

out = s.encode('utf-8')
if crlf:
    out = out.replace(b'\n', b'\r\n')
io.open(p, 'wb').write(out)
print("OK: build.gradle PATH + msys bin")
