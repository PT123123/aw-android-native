import io

p = r"C:\Users\<user>\Desktop\aw-android\mobile\build.gradle"
raw = io.open(p, 'rb').read()
crlf = b'\r\n' in raw
s = raw.decode('utf-8').replace('\r\n', '\n')

old = """    exec { spec, toolchain ->
        def osName = System.properties['os.name'].toLowerCase()
        if (osName.contains('windows')) {
            spec.environment("OPENSSL_SRC_PERL", "C:/msys64/usr/bin/perl.exe")
        }
    }"""

new = """    exec { spec, toolchain ->
        def osName = System.properties['os.name'].toLowerCase()
        if (osName.contains('windows')) {
            spec.environment("OPENSSL_SRC_PERL", "C:/msys64/usr/bin/perl.exe")
            // openssl-src 在 MSYS 里编 OpenSSL 时，若 CC 是 Windows 反斜杠路径，会被 /bin/sh
            // 吞成 "C:Usersted..." 导致 clang 找不到（Error 127）。改为裸 basename 并把 NDK
            // bin 放进 PATH，Configure 生成的 Makefile 里 CC 即 basename，MSYS 可直接执行。
            // 思路与 aw-server-rust/compile-android.sh 的本地修复一致。
            def ndkBin = new File(android.ndkDirectory, "toolchains/llvm/prebuilt/windows-x86_64/bin").path
            spec.environment("CC_armv7-linux-androideabi", "armv7a-linux-androideabi24-clang")
            spec.environment("CC_aarch64-linux-android", "aarch64-linux-android24-clang")
            spec.environment("PATH", ndkBin + File.pathSeparator + (System.getenv("PATH") ?: ""))
        }
    }"""

assert old in s, "cargo exec anchor not found"
s = s.replace(old, new, 1)

out = s.encode('utf-8')
if crlf:
    out = out.replace(b'\n', b'\r\n')
io.open(p, 'wb').write(out)
print("OK: build.gradle patched")
