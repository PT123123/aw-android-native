import io

p = r"C:\Users\<user>\Desktop\aw-android\mobile\src\main\java\net\activitywatch\android\MainActivity.kt"
raw = io.open(p, 'rb').read()
crlf = b'\r\n' in raw
s = raw.decode('utf-8').replace('\r\n', '\n')

old_import = ("import net.activitywatch.android.sync.SyncFragment\n"
              "import net.activitywatch.android.watcher.UsageStatsWatcher")
new_import = ("import net.activitywatch.android.sync.SyncFragment\n"
              "import net.activitywatch.android.todo.TodoFragment\n"
              "import net.activitywatch.android.watcher.UsageStatsWatcher")
assert old_import in s, "import anchor not found"
s = s.replace(old_import, new_import, 1)

old_row = ('            NavRow(\n'
           '                R.id.nav_inbox,\n'
           '                ContextCompat.getDrawable(this, android.R.drawable.ic_menu_edit)!!,\n'
           '                "Inbox",\n'
           '                InboxFragment::class.java\n'
           '            ),\n'
           '            NavRow(\n'
           '                R.id.nav_inbox_settings,')
new_row = ('            NavRow(\n'
           '                R.id.nav_inbox,\n'
           '                ContextCompat.getDrawable(this, android.R.drawable.ic_menu_edit)!!,\n'
           '                "Inbox",\n'
           '                InboxFragment::class.java\n'
           '            ),\n'
           '            NavRow(\n'
           '                R.id.nav_todo,\n'
           '                ContextCompat.getDrawable(this, android.R.drawable.ic_menu_agenda)!!,\n'
           '                "任务",\n'
           '                TodoFragment::class.java\n'
           '            ),\n'
           '            NavRow(\n'
           '                R.id.nav_inbox_settings,')
assert old_row in s, "row anchor not found"
s = s.replace(old_row, new_row, 1)

out = s.encode('utf-8')
if crlf:
    out = out.replace(b'\n', b'\r\n')
io.open(p, 'wb').write(out)
print("OK: MainActivity patched")
