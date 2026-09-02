import io

def patch(path, replacements):
    raw = io.open(path, 'rb').read()
    crlf = b'\r\n' in raw
    s = raw.decode('utf-8').replace('\r\n', '\n')
    for old, new in replacements:
        assert old in s, "anchor not found in %s: %r" % (path, old[:60])
        s = s.replace(old, new, 1)
    out = s.encode('utf-8')
    if crlf:
        out = out.replace(b'\n', b'\r\n')
    io.open(path, 'wb').write(out)
    print("OK:", path)

patch(r"C:\Users\<user>\Desktop\aw-android\mobile\src\main\java\net\activitywatch\android\todo\TodoAdapter.kt", [
    ("    private fun dueColor(ctx: android.content.Context, due: String, completed: Boolean): Int {",
     "    private fun dueColor(due: String, completed: Boolean): Int {"),
    ("b.dueDate.setTextColor(ContextCompat.getColor(ctx, dueColor(ctx, due, task.completed)))",
     "b.dueDate.setTextColor(ContextCompat.getColor(ctx, dueColor(due, task.completed)))"),
])

patch(r"C:\Users\<user>\Desktop\aw-android\mobile\src\main\java\net\activitywatch\android\todo\TodoDetailDialog.kt", [
    ("                cal.time = SimpleDateFormat(\"yyyy-MM-dd\", Locale.US).parse(it)",
     "                SimpleDateFormat(\"yyyy-MM-dd\", Locale.US).parse(it)?.let { parsed -> cal.time = parsed }"),
])
