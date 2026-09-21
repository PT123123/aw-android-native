import io

def patch(path, old, new, desc):
    raw = io.open(path, 'rb').read()
    crlf = b'\r\n' in raw
    s = raw.decode('utf-8').replace('\r\n', '\n')
    assert old in s, f"{desc}: anchor not found"
    s = s.replace(old, new, 1)
    out = s.encode('utf-8')
    if crlf:
        out = out.replace(b'\n', b'\r\n')
    io.open(path, 'wb').write(out)
    print(f"OK: {desc}")

# 1) aw-server: remove openssl-sys vendored
patch(
    "aw-server-rust/aw-server/Cargo.toml",   # 相对仓库根
    'android_logger = "0.13"\nopenssl-sys = { version = "0.9.82", features = ["vendored"]}\naw-client-rust = { path = "../aw-client-rust" }',
    'android_logger = "0.13"\naw-client-rust = { path = "../aw-client-rust" }',
    "aw-server remove openssl-sys vendored"
)

# 2) aw-sync-rust: add rustls TLS backend to reqwest (match aw-client-rust)
patch(
    "aw-server-rust/aw-sync-rust/Cargo.toml",   # 相对仓库根
    'reqwest = { version = "0.12", default-features = false, features = ["json", "blocking"] }',
    'reqwest = { version = "0.12", default-features = false, features = ["json", "blocking", "rustls-tls-native-roots"] }',
    "aw-sync-rust reqwest + rustls-tls-native-roots"
)
