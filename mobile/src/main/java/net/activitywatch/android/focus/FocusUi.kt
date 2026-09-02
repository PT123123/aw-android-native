package net.activitywatch.android.focus

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.Fragment
import com.google.android.material.appbar.MaterialToolbar
import net.activitywatch.android.R

/** 专注模块共用的程序化 UI 脚手架（全部 aw_* 语义色） */
object FocusUi {

    fun dp(context: Context, v: Int): Int = (v * context.resources.displayMetrics.density).toInt()

    fun color(context: Context, id: Int): Int = ContextCompat.getColor(context, id)

    fun label(
        context: Context,
        text: String,
        sizeSp: Float = 14f,
        colorId: Int = R.color.aw_text_primary,
        bold: Boolean = false,
    ): TextView = TextView(context).apply {
        this.text = text
        textSize = sizeSp
        setTextColor(color(context, colorId))
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    fun input(context: Context, hint: String): EditText = EditText(context).apply {
        this.hint = hint
        textSize = 14f
        setTextColor(color(context, R.color.aw_text_primary))
        setHintTextColor(color(context, R.color.aw_text_disabled))
        background = ContextCompat.getDrawable(context, R.drawable.aw_field_bg)
        setPadding(dp(context, 12), dp(context, 10), dp(context, 12), dp(context, 10))
    }

    fun card(context: Context): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = ContextCompat.getDrawable(context, R.drawable.todo_task_bg)
        setPadding(dp(context, 14), dp(context, 12), dp(context, 14), dp(context, 12))
    }

    /** 视图 chips（复用 todo 的胶囊 drawable） */
    fun chip(
        context: Context,
        text: String,
        selected: Boolean,
        onClick: () -> Unit,
    ): TextView = TextView(context).apply {
        this.text = text
        textSize = 13f
        setTextColor(color(context, if (selected) R.color.aw_accent else R.color.aw_text_secondary))
        background = ContextCompat.getDrawable(
            context,
            if (selected) R.drawable.todo_chip_bg_selected else R.drawable.todo_chip_bg
        )
        setPadding(dp(context, 14), dp(context, 6), dp(context, 14), dp(context, 6))
        setOnClickListener { onClick() }
    }

    /**
     * 页面根布局：MaterialToolbar（抽屉键 + 模块开关菜单）+ 内容容器。
     * 返回 (toolbar, content)；content 已占满剩余空间。
     */
    fun buildRoot(fragment: Fragment, titleText: String): Pair<MaterialToolbar, LinearLayout> {
        val ctx = fragment.requireContext()
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(color(ctx, R.color.aw_bg))
        }
        val toolbar = MaterialToolbar(ctx).apply {
            setTitle(titleText)
            setTitleTextColor(color(ctx, R.color.aw_text_primary))
            setBackgroundColor(color(ctx, R.color.aw_bg))
            navigationIcon = ContextCompat.getDrawable(ctx, R.drawable.ic_menu)
            setNavigationIconTint(color(ctx, R.color.aw_text_primary))
            inflateMenu(R.menu.menu_focus_modules)
        }
        toolbar.setNavigationOnClickListener {
            fragment.requireActivity()
                .findViewById<DrawerLayout>(R.id.drawer_layout)
                ?.openDrawer(GravityCompat.START)
        }
        root.addView(
            toolbar,
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(ctx, 56)
        )
        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 16), dp(ctx, 12), dp(ctx, 16), dp(ctx, 16))
        }
        root.addView(
            content,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                1f
            )
        )
        return toolbar to content
    }

    /** 模块停用时的占位提示 */
    fun disabledHint(fragment: Fragment, key: String): LinearLayout {
        val ctx = fragment.requireContext()
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(
                label(ctx, "「${FocusModules.TITLES[key] ?: key}」模块已在设置中停用", 14f, R.color.aw_text_disabled),
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
    }

    /** 「⚙ 模块开关」弹窗：8 个 bool，默认全开（契约 §5.8） */
    fun showModulesDialog(fragment: Fragment, onChanged: () -> Unit) {
        val ctx = fragment.requireContext()
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 20), dp(ctx, 8), dp(ctx, 20), dp(ctx, 8))
        }
        val switches = mutableMapOf<String, Switch>()
        for (key in FocusModules.KEYS) {
            val sw = Switch(ctx).apply {
                text = FocusModules.TITLES[key] ?: key
                textSize = 15f
                setTextColor(color(ctx, R.color.aw_text_primary))
                isChecked = FocusModules.enabled(ctx, key)
                setOnCheckedChangeListener { _, checked ->
                    FocusModules.setEnabled(ctx, key, checked)
                }
            }
            container.addView(
                sw,
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            switches[key] = sw
        }
        AlertDialog.Builder(ctx)
            .setTitle("专注模块开关")
            .setView(ScrollView(ctx).apply { addView(container) })
            .setPositiveButton("完成") { _, _ ->
                onChanged()
                // 若当前页依赖的模块被关闭，toast 提示一次
                val offCount = switches.count { !it.value.isChecked }
                if (offCount > 0) Toast.makeText(ctx, "已停用 $offCount 个模块", Toast.LENGTH_SHORT).show()
            }
            .show()
    }
}
