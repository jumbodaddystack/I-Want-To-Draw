package com.aichat.sandbox.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.text.Spanned
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import io.noties.markwon.Markwon

@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    // While streaming we render with a Markwon instance that omits the
    // JLatexMath plugin, which throws on unbalanced `$…$` fragments that
    // appear naturally as tokens arrive. Once the stream finalizes, the
    // call site flips this back to false and the full renderer (with
    // LaTeX) takes over.
    isStreaming: Boolean = false,
    markwonProvider: MarkwonProvider = remember { MarkwonProvider() }
) {
    val context = LocalContext.current
    val isDarkTheme = isSystemInDarkTheme()
    val markwon = remember(isDarkTheme, isStreaming) {
        if (isStreaming) markwonProvider.provideStreaming(context, isDarkTheme)
        else markwonProvider.provide(context, isDarkTheme)
    }
    val linkColor = MaterialTheme.colorScheme.primary.toArgb()
    val textColorArgb = if (color != Color.Unspecified) color.toArgb() else null
    val codeBlocks = remember(text) { extractCodeBlocks(text) }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
        },
        update = { container ->
            container.removeAllViews()

            if (codeBlocks.isEmpty()) {
                // No code blocks - render as single TextView
                val textView = createStyledTextView(container.context, textColorArgb, linkColor)
                container.addView(textView)
                renderMarkdownSafe(markwon, textView, text)
            } else {
                // Split content around code blocks and render each section
                var lastEnd = 0
                for (block in codeBlocks) {
                    // Render text before this code block
                    if (block.startIndex > lastEnd) {
                        val before = text.substring(lastEnd, block.startIndex).trim()
                        if (before.isNotEmpty()) {
                            val tv = createStyledTextView(container.context, textColorArgb, linkColor)
                            container.addView(tv)
                            renderMarkdownSafe(markwon, tv, before)
                        }
                    }

                    // Render code block with copy button
                    val codeContainer = createCodeBlockWithCopyButton(
                        container.context,
                        markwon,
                        block.fullMarkdown,
                        block.rawCode,
                        textColorArgb,
                        linkColor,
                        isDarkTheme
                    )
                    container.addView(codeContainer)

                    lastEnd = block.endIndex
                }

                // Render remaining text after last code block
                if (lastEnd < text.length) {
                    val after = text.substring(lastEnd).trim()
                    if (after.isNotEmpty()) {
                        val tv = createStyledTextView(container.context, textColorArgb, linkColor)
                        container.addView(tv)
                        renderMarkdownSafe(markwon, tv, after)
                    }
                }
            }
        }
    )
}

// Markwon (especially the LaTeX plugin) can throw on malformed input.
// Falling back to the raw text keeps the message readable instead of
// taking down the whole composition.
private fun renderMarkdownSafe(markwon: Markwon, textView: TextView, text: String) {
    try {
        markwon.setMarkdown(textView, text)
    } catch (t: Throwable) {
        Log.w("MarkdownText", "markwon.setMarkdown failed; falling back to plain text", t)
        textView.text = text
    }
}

private fun createStyledTextView(
    context: Context,
    textColorArgb: Int?,
    linkColor: Int
): TextView {
    return TextView(context).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        textColorArgb?.let { setTextColor(it) }
        setLinkTextColor(linkColor)
    }
}

private fun createCodeBlockWithCopyButton(
    context: Context,
    markwon: Markwon,
    fullMarkdown: String,
    rawCode: String,
    textColorArgb: Int?,
    linkColor: Int,
    isDarkTheme: Boolean
): FrameLayout {
    val density = context.resources.displayMetrics.density
    val padding = (8 * density).toInt()

    return FrameLayout(context).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = (4 * density).toInt()
            bottomMargin = (4 * density).toInt()
        }

        // Code block text view
        val codeTextView = TextView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
            textColorArgb?.let { setTextColor(it) }
            setLinkTextColor(linkColor)
        }
        addView(codeTextView)
        renderMarkdownSafe(markwon, codeTextView, fullMarkdown)

        // Copy button overlay
        val copyButton = TextView(context).apply {
            text = "Copy"
            textSize = 12f
            val btnPaddingH = (12 * density).toInt()
            val btnPaddingV = (4 * density).toInt()
            setPadding(btnPaddingH, btnPaddingV, btnPaddingH, btnPaddingV)

            if (isDarkTheme) {
                setTextColor(0xFFBBBBBB.toInt())
                setBackgroundColor(0xFF3A3A3A.toInt())
            } else {
                setTextColor(0xFF555555.toInt())
                setBackgroundColor(0xFFE8E8E8.toInt())
            }

            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END
            ).apply {
                topMargin = padding
                marginEnd = padding
            }

            setOnClickListener {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("code", rawCode)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(context, "Code copied", Toast.LENGTH_SHORT).show()
            }
        }
        addView(copyButton)
    }
}

private data class CodeBlock(
    val startIndex: Int,
    val endIndex: Int,
    val fullMarkdown: String,
    val rawCode: String
)

private fun extractCodeBlocks(text: String): List<CodeBlock> {
    val blocks = mutableListOf<CodeBlock>()
    val pattern = Regex("```(\\w*)\n([\\s\\S]*?)```", RegexOption.MULTILINE)

    for (match in pattern.findAll(text)) {
        blocks.add(
            CodeBlock(
                startIndex = match.range.first,
                endIndex = match.range.last + 1,
                fullMarkdown = match.value,
                rawCode = match.groupValues[2].trimEnd()
            )
        )
    }
    return blocks
}
