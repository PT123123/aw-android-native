import io

p = "aw-server-rust/aw-server/src/android/mod.rs"   # 相对仓库根
raw = io.open(p, 'rb').read()
crlf = b'\r\n' in raw
s = raw.decode('utf-8').replace('\r\n', '\n')

old = '''                let shared_db: aw_inbox_rust::SharedDb =
                    std::sync::Arc::new(std::sync::Mutex::new(pool));
                info!("[AW_INBOX] 注册 inbox 路由到 Rocket...");
                let rocket = crate::plugins::register_all_plugins(rocket, shared_db);
                info!("[AW_INBOX] inbox 路由注册完成");
                rocket'''

new = '''                let shared_db: aw_inbox_rust::SharedDb =
                    std::sync::Arc::new(std::sync::Mutex::new(pool));
                // TODO 使用独立 DB 文件（todo.db），初始化独立连接池并注入插件
                let todo_pool = aw_inbox_rust::db::init_todo_pool()
                    .await
                    .expect("Failed to init todo db pool");
                match aw_inbox_rust::db::migrate_todo(&todo_pool) {
                    Ok(_) => info!("[AW_INBOX] todo 数据库迁移完成"),
                    Err(e) => error!("[AW_INBOX] todo 数据库迁移失败: {:?}", e),
                }
                let shared_todo_db: aw_inbox_rust::SharedTodoDb = aw_inbox_rust::SharedTodoDb(
                    std::sync::Arc::new(std::sync::Mutex::new(todo_pool)),
                );
                info!("[AW_INBOX] 注册 inbox 路由到 Rocket...");
                let rocket =
                    crate::plugins::register_all_plugins(rocket, shared_db, shared_todo_db);
                info!("[AW_INBOX] inbox 路由注册完成");
                rocket'''

assert old in s, "android/mod.rs anchor not found"
s = s.replace(old, new, 1)

out = s.encode('utf-8')
if crlf:
    out = out.replace(b'\n', b'\r\n')
io.open(p, 'wb').write(out)
print("OK: android/mod.rs register_all_plugins 3-arg")
