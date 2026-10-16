import Foundation

/// The single source of truth for what a dictionary's bytes actually contain.
///
/// It is pure and filesystem-free so the store, the cracking engine and the tests all agree on
/// one definition of "a line", "an entry" and "a decoding error". It handles the cases the old
/// ad-hoc counters disagreed about: a UTF-8/UTF-16 byte-order mark, LF, CRLF and lone-CR line
/// endings, a missing trailing newline, blank lines, duplicate entries and bytes that are not
/// valid in the detected encoding.
enum DictionaryText {
    enum Encoding: String, Equatable {
        case utf8
        case utf8WithByteOrderMark
        case utf16LittleEndian
        case utf16BigEndian
    }

    /// Counts only — no text is kept, so this is safe to call just to render a list row.
    struct Analysis: Equatable {
        /// Physical lines including blank ones. Empty data has zero lines (never one).
        let totalLines: Int
        /// Lines that are empty after a trailing carriage return is removed.
        let blankLines: Int
        /// Non-blank lines before de-duplication: exactly the candidates the engine tests.
        let entryLines: Int
        /// Distinct non-blank entries.
        let uniqueEntries: Int
        let hadByteOrderMark: Bool
        let encoding: Encoding
        /// The bytes were not valid in the detected encoding and had to be decoded lossily.
        let hadDecodingErrors: Bool
        let endedWithNewline: Bool
    }

    /// Counts plus the decoded text, used by the editor and the engine.
    struct Decoded {
        let analysis: Analysis
        /// Non-blank lines in file order, byte-order mark removed. Duplicates are preserved so
        /// the engine still tests every physical line exactly as written.
        let entries: [String]
        /// Distinct non-blank entries in first-seen order.
        let uniqueEntries: [String]
    }

    static func analyze(_ bytes: [UInt8]) -> Analysis { decode(bytes).analysis }

    static func candidates(_ bytes: [UInt8]) -> [String] { decode(bytes).entries }

    static func decode(_ bytes: [UInt8]) -> Decoded {
        let decoded = decodeText(bytes)
        let lines = splitLines(decoded.text)
        let entries = lines.filter { !$0.isEmpty }
        var seen = Set<String>()
        var unique: [String] = []
        for entry in entries where seen.insert(entry).inserted { unique.append(entry) }
        let analysis = Analysis(
            totalLines: lines.count,
            blankLines: lines.count - entries.count,
            entryLines: entries.count,
            uniqueEntries: unique.count,
            hadByteOrderMark: decoded.hadByteOrderMark,
            encoding: decoded.encoding,
            hadDecodingErrors: decoded.hadDecodingErrors,
            endedWithNewline: decoded.text.isEmpty ? false : hasTrailingNewline(decoded.text))
        return Decoded(analysis: analysis, entries: entries, uniqueEntries: unique)
    }

    // MARK: - Decoding

    private static func decodeText(_ bytes: [UInt8])
        -> (text: String, encoding: Encoding, hadByteOrderMark: Bool, hadDecodingErrors: Bool) {
        if bytes.count >= 3, bytes[0] == 0xEF, bytes[1] == 0xBB, bytes[2] == 0xBF {
            let body = Array(bytes.dropFirst(3))
            return decode(body, as: .utf8, encoding: .utf8WithByteOrderMark, bom: true)
        }
        if bytes.count >= 2, bytes[0] == 0xFF, bytes[1] == 0xFE {
            let body = Array(bytes.dropFirst(2))
            return decode(body, as: .utf16LittleEndian, encoding: .utf16LittleEndian, bom: true)
        }
        if bytes.count >= 2, bytes[0] == 0xFE, bytes[1] == 0xFF {
            let body = Array(bytes.dropFirst(2))
            return decode(body, as: .utf16BigEndian, encoding: .utf16BigEndian, bom: true)
        }
        if bytes.isEmpty {
            return ("", .utf8, false, false)
        }
        // UTF-16 without a mark is guessed from the null-byte pattern a BOM-less writer leaves.
        if let guessed = guessMarklessUTF16(bytes) {
            let encoding: Encoding = guessed ? .utf16LittleEndian : .utf16BigEndian
            let order: String.Encoding = guessed ? .utf16LittleEndian : .utf16BigEndian
            return decode(bytes, as: order, encoding: encoding, bom: false)
        }
        return decode(bytes, as: .utf8, encoding: .utf8, bom: false)
    }

    private static func decode(_ bytes: [UInt8], as stringEncoding: String.Encoding,
                               encoding: Encoding, bom: Bool)
        -> (text: String, encoding: Encoding, hadByteOrderMark: Bool, hadDecodingErrors: Bool) {
        if let text = String(bytes: bytes, encoding: stringEncoding) {
            return (stripLeadingSegmentScalar(text), encoding, bom, false)
        }
        // Fall back to the lossy UTF-8 decoder so the app never crashes on odd bytes, but flag
        // the file so it is reported as damaged rather than silently "supported".
        return (String(decoding: bytes, as: UTF8.self), encoding, bom, true)
    }

    private static func guessMarklessUTF16(_ bytes: [UInt8]) -> Bool? {
        let sample = bytes.prefix(64)
        guard sample.count >= 4 else { return nil }
        var evenNul = 0
        var oddNul = 0
        for (index, byte) in sample.enumerated() where byte == 0x00 {
            if index % 2 == 0 { evenNul += 1 } else { oddNul += 1 }
        }
        if oddNul >= 2 && evenNul == 0 { return true }  // ASCII-heavy UTF-16LE
        if evenNul >= 2 && oddNul == 0 { return false } // ASCII-heavy UTF-16BE
        return nil
    }

    private static func stripLeadingSegmentScalar(_ text: String) -> String {
        guard text.first == "\u{FEFF}" else { return text }
        return String(text.dropFirst())
    }

    // MARK: - Lines

    /// Splits on LF, CRLF and lone CR. Every physical line is returned, blank ones included.
    private static func splitLines(_ text: String) -> [String] {
        if text.isEmpty { return [] }
        var lines: [String] = []
        var current = ""
        var previousWasCR = false
        for scalar in text.unicodeScalars {
            switch scalar {
            case "\r":
                lines.append(current)
                current = ""
                previousWasCR = true
            case "\n":
                if previousWasCR {
                    previousWasCR = false      // the LF half of a CRLF: already counted
                } else {
                    lines.append(current)
                    current = ""
                }
            default:
                current.unicodeScalars.append(scalar)
                previousWasCR = false
            }
        }
        if !current.isEmpty { lines.append(current) }
        return lines
    }

    private static func hasTrailingNewline(_ text: String) -> Bool {
        guard let last = text.unicodeScalars.last else { return false }
        return last == "\n" || last == "\r"
    }
}
