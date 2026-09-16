package dev.opencode.mobile.ui.common

import com.github.difflib.DiffUtils
import com.github.difflib.patch.ChangeDelta
import com.github.difflib.patch.DeleteDelta
import com.github.difflib.patch.InsertDelta
import com.github.difflib.patch.EqualDelta

enum class DiffKind { CONTEXT, ADD, DEL }

data class DiffLine(
    val kind: DiffKind,
    val oldNo: Int?,
    val newNo: Int?,
    val text: String,
)

object DiffLines {

    fun compute(before: String, after: String): List<DiffLine> {
        val oldLines = before.split("\n")
        val newLines = after.split("\n")
        val result = try {
            DiffUtils.diff(oldLines, newLines)
        } catch (t: Throwable) {
            return oldLines.mapIndexed { i, line -> DiffLine(DiffKind.CONTEXT, i + 1, i + 1, line) }
        }

        val deltas = result.deltas
        val out = mutableListOf<DiffLine>()
        var oldIndex = 0
        var newIndex = 0

        for (delta in deltas) {
            val source = delta.source.position
            val target = delta.target.position
            while (oldIndex < source && newIndex < target) {
                out.add(DiffLine(DiffKind.CONTEXT, oldIndex + 1, newIndex + 1, oldLines[oldIndex]))
                oldIndex++
                newIndex++
            }
            when (delta) {
                is ChangeDelta<*> -> {
                    for (line in delta.source.lines) out.add(DiffLine(DiffKind.DEL, oldIndex + 1, null, line))
                    for (line in delta.target.lines) out.add(DiffLine(DiffKind.ADD, null, newIndex + 1, line))
                    oldIndex += delta.source.lines.size
                    newIndex += delta.target.lines.size
                }
                is DeleteDelta<*> -> {
                    for (line in delta.source.lines) out.add(DiffLine(DiffKind.DEL, oldIndex + 1, null, line))
                    oldIndex += delta.source.lines.size
                }
                is InsertDelta<*> -> {
                    for (line in delta.target.lines) out.add(DiffLine(DiffKind.ADD, null, newIndex + 1, line))
                    newIndex += delta.target.lines.size
                }
                is EqualDelta<*> -> {
                    for (line in delta.target.lines) out.add(DiffLine(DiffKind.CONTEXT, oldIndex + 1, newIndex + 1, line))
                    oldIndex += delta.target.lines.size
                    newIndex += delta.target.lines.size
                }
            }
        }
        while (oldIndex < oldLines.size) {
            out.add(DiffLine(DiffKind.CONTEXT, oldIndex + 1, newIndex + 1, oldLines[oldIndex]))
            oldIndex++
            newIndex++
        }
        return out
    }
}