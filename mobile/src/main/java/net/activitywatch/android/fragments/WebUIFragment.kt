package net.activitywatch.android.fragments

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Bundle
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.ValueCallback

import android.content.Intent.ACTION_VIEW
import android.util.Log
import android.webkit.URLUtil
import android.webkit.WebResourceRequest
import android.webkit.WebViewClient
import net.activitywatch.android.R
import java.lang.Thread.sleep

private const val TAG = "WebUI"

private const val ARG_URL = "url"

/**
 * A simple [Fragment] subclass.
 * Activities that contain this fragment must implement the
 * [WebUIFragment.OnFragmentInteractionListener] interface
 * to handle interaction events.
 * Use the [WebUIFragment.newInstance] factory method to
 * create an instance of this fragment.
 *
 */
class WebUIFragment : Fragment() {
    // TODO: Rename and change types of parameters
    private var listener: OnFragmentInteractionListener? = null
    private var webView: WebView? = null

    fun getCurrentUrl(): String? = webView?.url

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Inflate the layout for this fragment
        val view = inflater.inflate(R.layout.fragment_web_ui, container, false)

        // Enables WebView debugging, in testing builds
        // https://developers.google.com/web/tools/chrome-devtools/remote-debugging/webviews
        if (0 != view.context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) {
            WebView.setWebContentsDebuggingEnabled(true)
        }

        webView = view.findViewById(R.id.webview) as WebView

        class MyWebViewClient : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                injectCustomScript(view)
            }

            override fun onReceivedError(
                view: WebView,
                errorCode: Int,
                description: String,
                failingUrl: String
            ) {
                // Retry
                // TODO: Find way to not show the blinking Android error page
                Log.e(TAG, "WebView received error: $description")
                sleep(100);
                arguments?.let {
                    it.getString(ARG_URL)?.let { it1 -> webView?.loadUrl(it1) }
                }
            }

            // Open external links in external browser
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url.toString()
                if (URLUtil.isNetworkUrl(url)) {
                    if (url.startsWith("http://") || url.startsWith("https://")) {
                        // 本机 webui 可能以 localhost 或 127.0.0.1 访问，两者都必须留在 WebView 内。
                        // 否则页内导航（如原生表单提交）会被误判为外部链接而调起浏览器。
                        val isLocal = url.contains("//localhost:") || url.contains("//127.0.0.1:") || url.contains("//10.0.2.2:")
                        if (!isLocal) {
                            // Open the URL in an external browser
                            val i = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                            startActivity(i)
                            return true
                        }
                    }
                    // For all other URLs, load them inside the WebView
                    return false
                }
                return true
            }
        }
        webView?.webViewClient = MyWebViewClient()

        // Enable console.log output for debugging
        webView?.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage): Boolean {
                Log.d("WebUI_Console", "${consoleMessage.message()} -- From line ${consoleMessage.lineNumber()} of ${consoleMessage.sourceId()}")
                return true
            }
        }

        webView?.setDownloadListener { url, _, _, _, _ ->
            val i = Intent(ACTION_VIEW)
            i.data = Uri.parse(url)
            startActivity(i)
        }

        webView?.settings?.javaScriptEnabled = true
        webView?.settings?.domStorageEnabled = true
        arguments?.let {
            it.getString(ARG_URL)?.let { it1 -> webView?.loadUrl(it1) }
        }

        return view
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        if (context is OnFragmentInteractionListener) {
            listener = context
        } else {
            throw RuntimeException(context.toString() + " must implement OnFragmentInteractionListener")
        }
    }

    override fun onDetach() {
        super.onDetach()
        listener = null
    }

    private fun injectCustomScript(webView: WebView) {
        val animDuration = 300
        val storageKey = "aw_filter_bar_expanded"
        val script = """
            (function() {
                'use strict';

                function applyModifications() {
                    console.log('[AW Inject] applyModifications start');

                    // === 1. Find and hide triangle ◀ (sidebar toggle) ===
                    var allSpans = document.querySelectorAll('span');
                    for (var i = 0; i < allSpans.length; i++) {
                        var s = allSpans[i];
                        if (s.textContent.trim() === '◀') {
                            s.style.setProperty('display', 'none', 'important');
                            console.log('[AW Inject] Hidden triangle ◀');
                            break;
                        }
                    }

                    // === 2. Find checkmark ✅ and add = button next to it ===
                    var checkmark = null;
                    for (var i = 0; i < allSpans.length; i++) {
                        if (allSpans[i].textContent.trim() === '✅') {
                            checkmark = allSpans[i];
                            break;
                        }
                    }
                    var equalsBtn = null;
                    if (checkmark) {
                        // Don't add duplicate equals button
                        if (document.getElementById('aw-equals-btn')) {
                            console.log('[AW Inject] Equals button already exists');
                            equalsBtn = document.getElementById('aw-equals-btn');
                        } else {
                            equalsBtn = document.createElement('span');
                            equalsBtn.id = 'aw-equals-btn';
                            equalsBtn.textContent = '=';
                            equalsBtn.style.cssText = 'font-size:1.2em;font-weight:bold;cursor:pointer;padding:2px 6px;margin-left:4px;border-radius:4px;transition:transform 0.3s ease;user-select:none;display:inline-block;';
                            checkmark.parentNode.insertBefore(equalsBtn, checkmark.nextSibling);
                            console.log('[AW Inject] Added = button after ✅');
                        }
                    } else {
                        console.log('[AW Inject] Checkmark ✅ not found, skipping equals button');
                        equalsBtn = document.getElementById('aw-equals-btn');
                    }

                    // === 3. Find the toolbar and its buttons ===
                    var toolbar = checkmark ? checkmark.parentElement : null;
                    if (!toolbar) {
                        // Fallback: find toolbar by class
                        toolbar = document.querySelector('.toolbar, .inbox-controls, .controls-bar, [class*="control"], header');
                    }
                    if (!toolbar) {
                        console.log('[AW Inject] Toolbar not found, skipping panel creation');
                    } else {

                    // Find the specific buttons to hide
                    var sortOptions = toolbar.querySelector('.sort-options');
                    var copyBtn = toolbar.querySelector('.copy-btn');
                    var refreshBtn = null;
                    var searchBtn = toolbar.querySelector('button[title="搜索"]');

                    // Find refresh button (the one with text "刷新")
                    var toolbarBtns = toolbar.querySelectorAll('button');
                    for (var i = 0; i < toolbarBtns.length; i++) {
                        if (toolbarBtns[i].textContent.trim() === '刷新') {
                            refreshBtn = toolbarBtns[i];
                            break;
                        }
                    }

                    var buttonsToHide = [sortOptions, copyBtn, refreshBtn, searchBtn].filter(Boolean);
                    console.log('[AW Inject] Found', buttonsToHide.length, 'buttons to hide');

                    // === 4. Create slide-out panel ===
                    var panel = document.getElementById('aw-slide-panel');
                    if (!panel) {
                        panel = document.createElement('div');
                        panel.id = 'aw-slide-panel';
                        panel.style.cssText = 'position:fixed;top:0;left:0;width:220px;height:100%;background:#1a1a1a;border-right:1px solid #333;padding:60px 16px 16px;box-shadow:4px 0 20px rgba(0,0,0,0.5);z-index:10000;transform:translateX(-100%);transition:transform ' + $animDuration + 'ms ease;display:flex;flex-direction:column;gap:12px;';

                        var btnConfigs = [
                            { text: '⏱ 创建时间', src: sortOptions ? sortOptions.querySelector('button') : null },
                            { text: '📋 复制', src: copyBtn },
                            { text: '🔄 刷新', src: refreshBtn },
                            { text: '🔍 搜索', src: searchBtn },
                        ];

                        btnConfigs.forEach(function(cfg) {
                            if (!cfg.src) return;
                            var panelBtn = document.createElement('button');
                            panelBtn.textContent = cfg.text;
                            panelBtn.style.cssText = 'background:#2a2a2a;border:1px solid #333;color:#fff;padding:12px 16px;border-radius:8px;cursor:pointer;font-size:15px;text-align:left;transition:background 0.2s;';
                            panelBtn.onmouseenter = function() { panelBtn.style.background = '#333'; };
                            panelBtn.onmouseleave = function() { panelBtn.style.background = '#2a2a2a'; };
                            panelBtn.addEventListener('click', function() {
                                cfg.src.click();
                                // Close panel after action
                                togglePanel(false);
                            });
                            panel.appendChild(panelBtn);
                        });

                        document.body.appendChild(panel);
                        console.log('[AW Inject] Created slide panel');
                    }

                    // === 5. Hide the buttons in the toolbar ===
                    buttonsToHide.forEach(function(btn) {
                        btn.style.setProperty('display', 'none', 'important');
                    });

                    // === 6. Toggle function ===
                    function togglePanel(forceState) {
                        var expanded = forceState !== undefined ? forceState : !panel.classList.contains('aw-open');
                        if (expanded) {
                            panel.classList.add('aw-open');
                            panel.style.transform = 'translateX(0)';
                        } else {
                            panel.classList.remove('aw-open');
                            panel.style.transform = 'translateX(-100%)';
                        }
                        if (equalsBtn) {
                            equalsBtn.style.transform = expanded ? 'rotate(90deg)' : 'rotate(0deg)';
                        }
                        localStorage.setItem('$storageKey', expanded);
                    }

                    // === 7. Click handler for equals button ===
                    equalsBtn.addEventListener('click', function(e) {
                        e.stopPropagation();
                        togglePanel();
                    });

                    // Close panel on outside click
                    document.addEventListener('click', function(e) {
                        if (panel.classList.contains('aw-open') && !panel.contains(e.target) && e.target !== equalsBtn) {
                            togglePanel(false);
                        }
                    });

                    // === 8. Restore persisted state ===
                    if (localStorage.getItem('$storageKey') === 'true') {
                        requestAnimationFrame(function() {
                            togglePanel(true);
                        });
                    }
                    }

                    // === 9. Render relation previews (Comment/Reference) ===
                    function renderRelationPreviews() {
                        console.log('[AW Inject] renderRelationPreviews called');
                        var noteItems = document.querySelectorAll('.note-item[data-note-id]');
                        console.log('[AW Inject] Found', noteItems.length, 'note-items with data-note-id');

                        // First, test XHR to a known working endpoint
                        var testXhr = new XMLHttpRequest();
                        testXhr.open('GET', '/inbox/notes?limit=1', true);
                        testXhr.onreadystatechange = function() {
                            if (testXhr.readyState === 4) {
                                console.log('[AW Inject] TEST XHR status:', testXhr.status, 'response:', testXhr.responseText.substring(0, 100));
                            }
                        };
                        testXhr.send();

                        noteItems.forEach(function(item) {
                            var noteId = item.getAttribute('data-note-id');
                            console.log('[AW Inject] Processing note-id:', noteId);
                            if (!noteId || item.hasAttribute('data-aw-relation-done')) return;
                            item.setAttribute('data-aw-relation-done', '1');

                            // Use XMLHttpRequest instead of fetch for better WebView compatibility
                            var xhr = new XMLHttpRequest();
                            var url = '/inbox/notes/' + noteId + '/relations';
                            console.log('[AW Inject] Sending relations XHR to:', url);
                            xhr.open('GET', url, true);
                            xhr.timeout = 5000;
                            xhr.onreadystatechange = function() {
                                if (xhr.readyState === 4) {
                                    console.log('[AW Inject] Relations XHR readyState=4 status:', xhr.status, 'for note:', noteId, 'response:', xhr.responseText ? xhr.responseText.substring(0, 200) : '(empty)');
                                    if (xhr.status === 200) {
                                        try {
                                            var relations = JSON.parse(xhr.responseText);
                                            console.log('[AW Inject] Relations for', noteId, ':', JSON.stringify(relations));
                                            if (!Array.isArray(relations) || relations.length === 0) return;
                                            var relevant = relations.filter(function(r) {
                                                return r.relation_type === 'Comment' || r.relation_type === 'Reference';
                                            });
                                            console.log('[AW Inject] Relevant relations:', JSON.stringify(relevant));
                                            if (relevant.length === 0) return;

                                            var targetId = relevant[0].target_note_id;
                                            // Fetch target note
                                            var xhr2 = new XMLHttpRequest();
                                            xhr2.open('GET', '/inbox/notes/' + targetId, true);
                                            xhr2.onreadystatechange = function() {
                                                if (xhr2.readyState === 4) {
                                                    console.log('[AW Inject] Target note XHR status:', xhr2.status, 'response:', xhr2.responseText ? xhr2.responseText.substring(0, 200) : '(empty)');
                                                    if (xhr2.status === 200) {
                                                        try {
                                                            var targetNote = JSON.parse(xhr2.responseText);
                                                            console.log('[AW Inject] Target note:', JSON.stringify(targetNote));
                                                            if (!targetNote || !targetNote.content) return;
                                                            var preview = targetNote.content.slice(0, 100) + '\u2026';
                                                            var previewEl = document.createElement('div');
                                                            previewEl.className = 'aw-relation-preview';
                                                            previewEl.textContent = '\u2196\ufe0f ' + preview;
                                                            previewEl.style.cssText = 'font-size:11px;color:#888;margin-top:8px;line-height:1.4;';
                                                            var contentEl = item.querySelector('.note-content, .content-text');
                                                            if (contentEl) {
                                                                contentEl.appendChild(previewEl);
                                                                console.log('[AW Inject] Preview inserted for note:', noteId);
                                                            }
                                                        } catch (e) {
                                                            console.warn('[AW Inject] Target note parse failed:', e.message);
                                                        }
                                                    } else {
                                                        console.warn('[AW Inject] Target note fetch failed:', xhr2.status, xhr2.statusText);
                                                    }
                                                }
                                            };
                                            xhr2.send();
                                        } catch (e) {
                                            console.warn('[AW Inject] Relations parse failed:', e.message);
                                        }
                                    } else {
                                        console.warn('[AW Inject] Relations fetch failed:', xhr.status, xhr.statusText, 'response:', xhr.responseText ? xhr.responseText.substring(0, 200) : '(empty)');
                                    }
                                }
                            };
                            xhr.ontimeout = function() {
                                console.warn('[AW Inject] Relations XHR timeout for note:', noteId);
                            };
                            xhr.onerror = function() {
                                console.warn('[AW Inject] Relations XHR error for note:', noteId);
                            };
                            xhr.send();
                        });
                    }
                    renderRelationPreviews();

                    // Re-apply on new notes (infinite scroll)
                    var noteList = document.querySelector('.note-list');
                    if (noteList) {
                        var noteObserver = new MutationObserver(function() {
                            document.querySelectorAll('.note-item[data-note-id]:not([data-aw-relation-done])').forEach(function(item) {
                                renderRelationPreviews();
                            });
                        });
                        noteObserver.observe(noteList, { childList: true, subtree: true });
                    }

                    // === 10. Style note-item as a whole - uniform dark background ===
                    function styleNoteItem(item) {
                        item.style.setProperty('background-color', '#111', 'important');
                        item.style.setProperty('color', '#e0e0e0', 'important');
                        item.style.setProperty('border-color', '#222', 'important');
                        item.style.setProperty('position', 'relative', 'important');
                    }
                    var noteItems = document.querySelectorAll('.note-item');
                    noteItems.forEach(styleNoteItem);

                    // === 11. Move three-dot menu to top-right, rotate horizontal ===
                    function repositionDots(item) {
                        if (item.hasAttribute('data-aw-dots-done')) return;
                        item.setAttribute('data-aw-dots-done', '1');
                        var actions = item.querySelector('.note-actions');
                        if (!actions) return;
                        // Move note-actions to be a direct child of note-item, positioned top-right
                        actions.style.cssText = 'position:absolute;top:8px;right:8px;z-index:10;';
                        // Rotate the dropdown button so vertical dots become horizontal
                        var toggle = actions.querySelector('.dropdown-toggle');
                        if (toggle) {
                            toggle.style.setProperty('transform', 'rotate(90deg)', 'important');
                            toggle.style.setProperty('padding', '2px 6px', 'important');
                        }
                    }
                    noteItems.forEach(repositionDots);
                    console.log('[AW Inject] Styled', noteItems.length, 'note-item cards');

                    // Re-apply on new notes (infinite scroll)
                    var noteList = document.querySelector('.note-list');
                    if (noteList) {
                        var noteObserver = new MutationObserver(function() {
                            document.querySelectorAll('.note-item:not([data-aw-styled])').forEach(function(item) {
                                item.setAttribute('data-aw-styled', '1');
                                styleNoteItem(item);
                                repositionDots(item);
                            });
                        });
                        noteObserver.observe(noteList, { childList: true, subtree: true });
                    }

                    console.log('[AW Inject] All modifications applied');
                }

                // Wait for DOM to be ready
                if (document.readyState === 'loading') {
                    document.addEventListener('DOMContentLoaded', function() {
                        setTimeout(applyModifications, 500);
                    });
                } else {
                    setTimeout(applyModifications, 500);
                }

                // Re-apply on SPA navigation
                var lastUrl = location.href;
                var navObserver = new MutationObserver(function() {
                    if (location.href !== lastUrl) {
                        lastUrl = location.href;
                        setTimeout(applyModifications, 500);
                    }
                });
                navObserver.observe(document, { subtree: true, childList: true });

                // Hook history API for SPA
                var origPush = history.pushState;
                history.pushState = function() {
                    origPush.apply(this, arguments);
                    setTimeout(applyModifications, 500);
                };
                var origReplace = history.replaceState;
                history.replaceState = function() {
                    origReplace.apply(this, arguments);
                    setTimeout(applyModifications, 500);
                };
                window.addEventListener('popstate', function() {
                    setTimeout(applyModifications, 500);
                });

                console.log('[AW Inject] Script installed');
            })();
        """.trimIndent()

        webView.evaluateJavascript(script, object : ValueCallback<String> {
            override fun onReceiveValue(value: String) {
                Log.d(TAG, "Script injection result: $value")
            }
        })
    }

    /**
     * This interface must be implemented by activities that contain this
     * fragment to allow an interaction in this fragment to be communicated
     * to the activity and potentially other fragments contained in that
     * activity.
     *
     *
     * See the Android Training lesson [Communicating with Other Fragments]
     * (http://developer.android.com/training/basics/fragments/communicating.html)
     * for more information.
     */
    interface OnFragmentInteractionListener {
        // TODO: Update argument type and name
        fun onFragmentInteraction(uri: Uri)
    }

    companion object {
        // TODO: Rename and change types and number of parameters
        @JvmStatic
        fun newInstance(url: String) =
            WebUIFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_URL, url)
                }
            }
    }
}
