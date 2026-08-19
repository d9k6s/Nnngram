/*
 * Copyright (C) 2019-2026 qwq233 <qwq233@qwq2333.top>
 * https://github.com/qwq233/Nullgram
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 */

package xyz.nextalone.nnngram.translate.providers

/** Pure text segmentation used by DeepLX before network requests are made. */
internal object DeepLxTextProcessor {
    data class Part(val text: String, val translate: Boolean)

    fun split(text: String, maxCharacters: Int, preserveFormatting: Boolean): List<Part> {
        if (text.isEmpty()) {
            return listOf(Part("", false))
        }
        val limit = maxCharacters.coerceAtLeast(1)
        val structuralParts = if (preserveFormatting) splitStructuralWhitespace(text) else listOf(Part(text, true))
        val result = ArrayList<Part>()
        structuralParts.forEach { part ->
            if (!part.translate) {
                appendPart(result, part)
            } else {
                splitLongPart(part.text, limit, preserveFormatting).forEach { appendPart(result, it) }
            }
        }
        return result
    }

    private fun splitStructuralWhitespace(text: String): List<Part> {
        val result = ArrayList<Part>()
        var textStart = 0
        var index = 0
        while (index < text.length) {
            val structuralEnd = structuralWhitespaceEnd(text, index)
            if (structuralEnd < 0) {
                index += Character.charCount(text.codePointAt(index))
                continue
            }
            if (textStart < index) {
                result.add(Part(text.substring(textStart, index), true))
            }
            result.add(Part(text.substring(index, structuralEnd), false))
            index = structuralEnd
            textStart = structuralEnd
        }
        if (textStart < text.length) {
            result.add(Part(text.substring(textStart), true))
        }
        return result
    }

    private fun structuralWhitespaceEnd(text: String, start: Int): Int {
        val codePoint = text.codePointAt(start)
        if (codePoint == '\r'.code) {
            return if (start + 1 < text.length && text[start + 1] == '\n') start + 2 else start + 1
        }
        if (codePoint == '\n'.code || codePoint == '\t'.code) {
            var end = start + Character.charCount(codePoint)
            while (end < text.length) {
                val next = text.codePointAt(end)
                if (next != '\n'.code && next != '\r'.code && next != '\t'.code) break
                end += Character.charCount(next)
            }
            return end
        }
        if (!Character.isWhitespace(codePoint)) return -1

        var end = start + Character.charCount(codePoint)
        var count = 1
        while (end < text.length) {
            val next = text.codePointAt(end)
            if (!Character.isWhitespace(next) || next == '\n'.code || next == '\r'.code || next == '\t'.code) break
            end += Character.charCount(next)
            count++
        }
        return if (count >= 2) end else -1
    }

    private fun splitLongPart(text: String, limit: Int, preserveFormatting: Boolean): List<Part> {
        if (text.codePointCount(0, text.length) <= limit) {
            return listOf(Part(text, hasTranslatableContent(text)))
        }

        val result = ArrayList<Part>()
        var start = 0
        while (start < text.length) {
            val remainingCount = text.codePointCount(start, text.length)
            if (remainingCount <= limit) {
                result.add(Part(text.substring(start), hasTranslatableContent(text.substring(start))))
                break
            }

            val hardEnd = text.offsetByCodePoints(start, limit)
            val preferredEnd = findPreferredEnd(text, start, hardEnd, limit)
            var end = if (preferredEnd > start) preferredEnd else hardEnd

            if (preserveFormatting && end < text.length && Character.isWhitespace(text.codePointAt(end))) {
                val chunk = text.substring(start, end)
                if (chunk.isNotEmpty()) result.add(Part(chunk, hasTranslatableContent(chunk)))
                var whitespaceEnd = end
                while (whitespaceEnd < text.length && Character.isWhitespace(text.codePointAt(whitespaceEnd))) {
                    whitespaceEnd += Character.charCount(text.codePointAt(whitespaceEnd))
                }
                result.add(Part(text.substring(end, whitespaceEnd), false))
                start = whitespaceEnd
                continue
            }

            if (end <= start) end = hardEnd
            val chunk = text.substring(start, end)
            result.add(Part(chunk, hasTranslatableContent(chunk)))
            start = end
        }
        return result
    }

    private fun findPreferredEnd(text: String, start: Int, hardEnd: Int, limit: Int): Int {
        val minimum = text.offsetByCodePoints(start, (limit / 2).coerceAtLeast(1))
        var index = hardEnd
        var whitespace = -1
        while (index > minimum) {
            val codePoint = text.codePointBefore(index)
            val codePointStart = index - Character.charCount(codePoint)
            if (isSentenceBoundary(codePoint)) return index
            if (whitespace < 0 && Character.isWhitespace(codePoint)) whitespace = codePointStart
            index = codePointStart
        }
        return whitespace
    }

    private fun isSentenceBoundary(codePoint: Int): Boolean = when (codePoint) {
        '.'.code, '!'.code, '?'.code, ';'.code, ':'.code,
        '。'.code, '！'.code, '？'.code, '；'.code, '：'.code -> true
        else -> false
    }

    private fun hasTranslatableContent(text: String): Boolean {
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            if (Character.isLetterOrDigit(codePoint)) return true
            index += Character.charCount(codePoint)
        }
        return false
    }

    private fun appendPart(parts: MutableList<Part>, part: Part) {
        if (part.text.isEmpty()) return
        val last = parts.lastOrNull()
        if (last != null && !last.translate && !part.translate) {
            parts[parts.lastIndex] = Part(last.text + part.text, false)
        } else {
            parts.add(part)
        }
    }
}
