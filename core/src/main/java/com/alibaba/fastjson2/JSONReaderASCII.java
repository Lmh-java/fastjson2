package com.alibaba.fastjson2;

import com.alibaba.fastjson2.util.Fnv;
import com.alibaba.fastjson2.util.JDKUtils;
import com.alibaba.fastjson2.util.TypeUtils;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static com.alibaba.fastjson2.JSONFactory.*;
import static com.alibaba.fastjson2.util.JDKUtils.*;

class JSONReaderASCII
        extends JSONReaderUTF8 {
    final String str;

    JSONReaderASCII(Context ctx, String str, byte[] bytes, int offset, int length) {
        super(ctx, str, bytes, offset, length);
        this.str = str;
        nameAscii = true;
    }

    JSONReaderASCII(Context ctx, InputStream is) {
        super(ctx, is);
        nameAscii = true;
        str = null;
    }

    @Override
    public final void next() {
        final byte[] bytes = this.bytes;
        int offset = this.offset;
        int ch = offset >= end ? EOI : bytes[offset++];
        while (ch == '\0' || (ch > 0 && ch <= ' ' && ((1L << ch) & SPACE) != 0)) {
            ch = offset == end ? EOI : bytes[offset++];
        }

        this.offset = offset;
        this.ch = (char) (ch & 0xFF);
        if (ch == '/') {
            skipComment();
        }
    }

    public final boolean nextIfObjectStart() {
        int ch = this.ch;
        if (ch != '{') {
            return false;
        }

        int offset = this.offset;
        if (offset >= end) {
            this.ch = EOI;
            return true;
        }

        byte[] bytes = this.bytes;

        ch = bytes[offset++];
        while (ch == '\0' || (ch <= ' ' && ((1L << ch) & SPACE) != 0)) {
            if (offset >= end) {
                this.ch = EOI;
                this.offset = offset;
                return true;
            }
            ch = bytes[offset++];
        }

        this.ch = (char) (ch & 0xFF);
        this.offset = offset;

        while (this.ch == '/' && this.offset < bytes.length && bytes[this.offset] == '/') {
            skipComment();
        }
        return true;
    }

    @Override
    public final boolean nextIfNullOrEmptyString() {
        final char first = this.ch;
        final int end = this.end;
        int offset = this.offset;
        byte[] bytes = this.bytes;
        if (first == 'n'
                && offset + 2 < end
                && bytes[offset] == 'u'
                && bytes[offset + 1] == 'l'
                && bytes[offset + 2] == 'l'
        ) {
            offset += 3;
        } else if ((first == '"' || first == '\'') && offset < end && bytes[offset] == first) {
            offset++;
        } else {
            return false;
        }

        char ch = offset == end ? EOI : (char) bytes[offset];

        while (ch <= ' ' && ((1L << ch) & SPACE) != 0) {
            offset++;
            if (offset >= end) {
                this.ch = EOI;
                this.offset = offset;
                return true;
            }
            ch = (char) bytes[offset];
        }

        if (comma = (ch == ',')) {
            offset++;
            if (offset >= end) {
                ch = EOI;
            } else {
                ch = (char) bytes[offset];
            }
        }

        if (offset >= end) {
            this.ch = EOI;
            this.offset = offset;
            return true;
        }

        while (ch <= ' ' && ((1L << ch) & SPACE) != 0) {
            offset++;
            if (offset >= end) {
                this.ch = EOI;
                return true;
            }
            ch = (char) bytes[offset];
        }
        this.offset = offset + 1;
        this.ch = ch;
        return true;
    }

    @Override
    public final long readFieldNameHashCode() {
        final byte[] bytes = this.bytes;
        if (ch != '"' && ch != '\'') {
            if ((context.features & Feature.AllowUnQuotedFieldNames.mask) != 0 && isFirstIdentifier(ch)) {
                return readFieldNameHashCodeUnquote();
            }
            if (ch == '}' || isNull()) {
                return -1;
            }

            String errorMsg, preFieldName;
            if (ch == '[' && nameBegin > 0 && (preFieldName = getFieldName()) != null) {
                errorMsg = "illegal fieldName input " + ch + ", previous fieldName " + preFieldName;
            } else {
                errorMsg = "illegal fieldName input" + ch;
            }

            throw new JSONException(info(errorMsg));
        }

        final char quote = ch;

        this.stringValue = null;
        this.nameEscape = false;
        int offset = this.nameBegin = this.offset;

        long nameValue = 0;
        if (offset + 9 < end) {
            byte c0, c1, c2, c3, c4, c5, c6, c7;

            if ((c0 = bytes[offset]) == quote) {
                nameValue = 0;
            } else if ((c1 = bytes[offset + 1]) == quote && c0 != 0 && c0 != '\\') {
                nameValue = c0;
                this.nameLength = 1;
                this.nameEnd = offset + 1;
                offset += 2;
            } else if ((c2 = bytes[offset + 2]) == quote && c1 != 0
                    && c0 != '\\' && c1 != '\\'
            ) {
                nameValue = (c1 << 8)
                        + (c0 & 0xFF);
                this.nameLength = 2;
                this.nameEnd = offset + 2;
                offset += 3;
            } else if ((c3 = bytes[offset + 3]) == quote && c2 != 0
                    && c0 != '\\' && c1 != '\\' && c2 != '\\'
            ) {
                nameValue
                        = (c2 << 16)
                        + ((c1 & 0xFF) << 8)
                        + (c0 & 0xFF);
                this.nameLength = 3;
                this.nameEnd = offset + 3;
                offset += 4;
            } else if ((c4 = bytes[offset + 4]) == quote && c3 != 0
                    && c0 != '\\' && c1 != '\\' && c2 != '\\' && c3 != '\\'
            ) {
                nameValue
                        = (c3 << 24)
                        + ((c2 & 0xFF) << 16)
                        + ((c1 & 0xFF) << 8)
                        + (c0 & 0xFF);
                this.nameLength = 4;
                this.nameEnd = offset + 4;
                offset += 5;
            } else if ((c5 = bytes[offset + 5]) == quote && c4 != 0
                    && c0 != '\\' && c1 != '\\' && c2 != '\\' && c3 != '\\' && c4 != '\\'
            ) {
                nameValue
                        = (((long) c4) << 32)
                        + ((c3 & 0xFFL) << 24)
                        + ((c2 & 0xFFL) << 16)
                        + ((c1 & 0xFFL) << 8)
                        + (c0 & 0xFFL);
                this.nameLength = 5;
                this.nameEnd = offset + 5;
                offset += 6;
            } else if ((c6 = bytes[offset + 6]) == quote && c5 != 0
                    && c0 != '\\' && c1 != '\\' && c2 != '\\' && c3 != '\\' && c4 != '\\' && c5 != '\\'
            ) {
                nameValue
                        = (((long) c5) << 40)
                        + ((c4 & 0xFFL) << 32)
                        + ((c3 & 0xFFL) << 24)
                        + ((c2 & 0xFFL) << 16)
                        + ((c1 & 0xFFL) << 8)
                        + (c0 & 0xFFL);
                this.nameLength = 6;
                this.nameEnd = offset + 6;
                offset += 7;
            } else if ((c7 = bytes[offset + 7]) == quote && c6 != 0
                    && c0 != '\\' && c1 != '\\' && c2 != '\\' && c3 != '\\' && c4 != '\\' && c5 != '\\' && c6 != '\\'
            ) {
                nameValue
                        = (((long) c6) << 48)
                        + ((c5 & 0xFFL) << 40)
                        + ((c4 & 0xFFL) << 32)
                        + ((c3 & 0xFFL) << 24)
                        + ((c2 & 0xFFL) << 16)
                        + ((c1 & 0xFFL) << 8)
                        + (c0 & 0xFFL);
                this.nameLength = 7;
                this.nameEnd = offset + 7;
                offset += 8;
            } else if (bytes[offset + 8] == quote && c7 != 0
                    && c0 != '\\' && c1 != '\\' && c2 != '\\' && c3 != '\\' && c4 != '\\' && c5 != '\\' && c6 != '\\' && c7 != '\\'
            ) {
                nameValue = getLong(bytes, offset);
                this.nameLength = 8;
                this.nameEnd = offset + 8;
                offset += 9;
            }
        }

        if (nameValue == 0) {
            for (int i = 0; offset < end; offset++, i++) {
                int c = bytes[offset];

                if (c == quote) {
                    if (i == 0) {
                        offset = this.nameBegin;
                        break;
                    }

                    this.nameLength = i;
                    this.nameEnd = offset;
                    offset++;
                    break;
                }

                if (c == '\\') {
                    nameEscape = true;
                    c = bytes[offset + 1];
                    switch (c) {
                        case 'u': {
                            c = char4(bytes[offset + 2], bytes[offset + 3], bytes[offset + 4], bytes[offset + 5]);
                            offset += 5;
                            break;
                        }
                        case 'x': {
                            c = char2(bytes[offset + 2], bytes[offset + 3]);
                            offset += 3;
                            break;
                        }
                        case '\\':
                        case '"':
                        default:
                            c = char1(c);
                            offset += 1;
                            break;
                    }
                }

                if (c > 0xFF || i >= 8 || (i == 0 && c == 0)) {
                    nameValue = 0;
                    offset = this.nameBegin;
                    break;
                }

                switch (i) {
                    case 0:
                        nameValue = (byte) c;
                        break;
                    case 1:
                        nameValue = (((byte) c) << 8) + (nameValue & 0xFFL);
                        break;
                    case 2:
                        nameValue = (((byte) c) << 16) + (nameValue & 0xFFFFL);
                        break;
                    case 3:
                        nameValue = (((byte) c) << 24) + (nameValue & 0xFFFFFFL);
                        break;
                    case 4:
                        nameValue = (((long) (byte) c) << 32) + (nameValue & 0xFFFFFFFFL);
                        break;
                    case 5:
                        nameValue = (((long) (byte) c) << 40L) + (nameValue & 0xFFFFFFFFFFL);
                        break;
                    case 6:
                        nameValue = (((long) (byte) c) << 48L) + (nameValue & 0xFFFFFFFFFFFFL);
                        break;
                    case 7:
                        nameValue = (((long) (byte) c) << 56L) + (nameValue & 0xFFFFFFFFFFFFFFL);
                        break;
                    default:
                        break;
                }
            }
        }

        long hashCode;
        if (nameValue != 0) {
            hashCode = nameValue;
        } else {
            hashCode = Fnv.MAGIC_HASH_CODE;
            for (int i = 0; ; ++i) {
                int c = bytes[offset];
                if (c == '\\') {
                    nameEscape = true;
                    c = bytes[++offset];
                    switch (c) {
                        case 'u': {
                            c = char4(bytes[offset + 1], bytes[offset + 2], bytes[offset + 3], bytes[offset + 4]);
                            offset += 4;
                            break;
                        }
                        case 'x': {
                            c = char2(bytes[offset + 1], bytes[offset + 2]);
                            offset += 2;
                            break;
                        }
                        case '\\':
                        case '"':
                        default:
                            c = char1(c);
                            break;
                    }
                    offset++;
                    hashCode ^= c;
                    hashCode *= Fnv.MAGIC_PRIME;
                    continue;
                }

                if (c == quote) {
                    this.nameLength = i;
                    this.nameEnd = offset;
                    offset++;
                    break;
                }

                offset++;
                hashCode ^= (c & 0xFF);
                hashCode *= Fnv.MAGIC_PRIME;
            }
        }

        byte c;
        if (offset < end) {
            c = bytes[offset];

            while (c <= ' ' && ((1L << c) & SPACE) != 0) {
                offset++;
                c = bytes[offset];
            }
        } else {
            c = EOI;
        }

        if (c != ':') {
            throw new JSONException(info("expect ':', but " + c));
        }

        offset++;
        if (offset == end) {
            c = EOI;
        } else {
            c = bytes[offset];
        }

        while (c <= ' ' && ((1L << c) & SPACE) != 0) {
            offset++;
            c = bytes[offset];
        }

        this.offset = offset + 1;
        this.ch = (char) c;

        return hashCode;
    }

    public static long getLong(byte[] bytes, int off) {
        return UNSAFE.getLong(
                bytes,
                ARRAY_BYTE_BASE_OFFSET + off
        );
    }

    @Override
    public final long readValueHashCode() {
        final byte[] bytes = this.bytes;
        if (ch != '"' && ch != '\'') {
            return -1;
        }

        final char quote = ch;

        this.nameEscape = false;
        int offset = this.nameBegin = this.offset;
        long nameValue = 0;
        for (int i = 0; offset < end; offset++, i++) {
            int c = bytes[offset];

            if (c == quote) {
                if (i == 0) {
                    nameValue = 0;
                    offset = this.nameBegin;
                    break;
                }

                this.nameLength = i;
                this.nameEnd = offset;
                offset++;
                break;
            }

            if (c == '\\') {
                nameEscape = true;
                c = bytes[++offset];
                switch (c) {
                    case 'u': {
                        c = char4(bytes[offset + 1], bytes[offset + 2], bytes[offset + 3], bytes[offset + 4]);
                        offset += 4;
                        break;
                    }
                    case 'x': {
                        c = char2(bytes[offset + 1], bytes[offset + 2]);
                        offset += 2;
                        break;
                    }
                    case '\\':
                    case '"':
                    default:
                        c = char1(c);
                        break;
                }
            }

            if (c > 0xFF || i >= 8 || (i == 0 && c == 0)) {
                nameValue = 0;
                offset = this.nameBegin;
                break;
            }

            switch (i) {
                case 0:
                    nameValue = (byte) c;
                    break;
                case 1:
                    nameValue = (((byte) c) << 8) + (nameValue & 0xFFL);
                    break;
                case 2:
                    nameValue = (((byte) c) << 16) + (nameValue & 0xFFFFL);
                    break;
                case 3:
                    nameValue = (((byte) c) << 24) + (nameValue & 0xFFFFFFL);
                    break;
                case 4:
                    nameValue = (((long) (byte) c) << 32) + (nameValue & 0xFFFFFFFFL);
                    break;
                case 5:
                    nameValue = (((long) (byte) c) << 40L) + (nameValue & 0xFFFFFFFFFFL);
                    break;
                case 6:
                    nameValue = (((long) (byte) c) << 48L) + (nameValue & 0xFFFFFFFFFFFFL);
                    break;
                case 7:
                    nameValue = (((long) (byte) c) << 56L) + (nameValue & 0xFFFFFFFFFFFFFFL);
                    break;
                default:
                    break;
            }
        }

        long hashCode;
        if (nameValue != 0) {
            hashCode = nameValue;
        } else {
            hashCode = Fnv.MAGIC_HASH_CODE;
            for (int i = 0; ; ++i) {
                int c = bytes[offset];
                if (c == '\\') {
                    nameEscape = true;
                    c = bytes[++offset];
                    switch (c) {
                        case 'u': {
                            c = char4(bytes[offset + 1], bytes[offset + 2], bytes[offset + 3], bytes[offset + 4]);
                            offset += 4;
                            break;
                        }
                        case 'x': {
                            c = char2(bytes[offset + 1], bytes[offset + 2]);
                            offset += 2;
                            break;
                        }
                        case '\\':
                        case '"':
                        default:
                            c = char1(c);
                            break;
                    }
                    offset++;
                    hashCode ^= c;
                    hashCode *= Fnv.MAGIC_PRIME;
                    continue;
                }

                if (c == '"') {
                    this.nameLength = i;
                    this.nameEnd = offset;
                    this.stringValue = null;
                    offset++;
                    break;
                }

                offset++;
                hashCode ^= c < 0 ? (c & 0xFF) : c;
                hashCode *= Fnv.MAGIC_PRIME;
            }
        }

        byte c;
        if (offset == end) {
            c = EOI;
        } else {
            c = bytes[offset];
        }

        while (c <= ' ' && ((1L << c) & SPACE) != 0) {
            offset++;
            c = bytes[offset];
        }

        if (comma = (c == ',')) {
            offset++;
            if (offset == end) {
                c = EOI;
            } else {
                c = bytes[offset];
            }

            while (c <= ' ' && ((1L << c) & SPACE) != 0) {
                offset++;
                c = bytes[offset];
            }
        }

        this.offset = offset + 1;
        this.ch = (char) c;

        return hashCode;
    }

    @Override
    public final long getNameHashCodeLCase() {
        final byte[] bytes = this.bytes;
        int offset = nameBegin;
        long nameValue = 0;
        for (int i = 0; offset < end; offset++) {
            int c = bytes[offset];

            if (c == '\\') {
                c = bytes[++offset];
                switch (c) {
                    case 'u': {
                        c = char4(bytes[offset + 1], bytes[offset + 2], bytes[offset + 3], bytes[offset + 4]);
                        offset += 4;
                        break;
                    }
                    case 'x': {
                        c = char2(bytes[offset + 1], bytes[offset + 2]);
                        offset += 2;
                        break;
                    }
                    case '\\':
                    case '"':
                    default:
                        c = char1(c);
                        break;
                }
            } else if (c == '"') {
                break;
            }

            if (c > 0xFF || c < 0 || i >= 8 || (i == 0 && c == 0)) {
                nameValue = 0;
                offset = this.nameBegin;
                break;
            }

            if (c == '_' || c == '-' || c == ' ') {
                byte c1 = bytes[offset + 1];
                if (c1 != '"' && c1 != '\'' && c1 != c) {
                    continue;
                }
            }

            if (c >= 'A' && c <= 'Z') {
                c = (char) (c + 32);
            }

            switch (i) {
                case 0:
                    nameValue = (byte) c;
                    break;
                case 1:
                    nameValue = (((byte) c) << 8) + (nameValue & 0xFFL);
                    break;
                case 2:
                    nameValue = (((byte) c) << 16) + (nameValue & 0xFFFFL);
                    break;
                case 3:
                    nameValue = (((byte) c) << 24) + (nameValue & 0xFFFFFFL);
                    break;
                case 4:
                    nameValue = (((long) (byte) c) << 32) + (nameValue & 0xFFFFFFFFL);
                    break;
                case 5:
                    nameValue = (((long) (byte) c) << 40) + (nameValue & 0xFFFFFFFFFFL);
                    break;
                case 6:
                    nameValue = (((long) (byte) c) << 48) + (nameValue & 0xFFFFFFFFFFFFL);
                    break;
                case 7:
                    nameValue = (((long) (byte) c) << 56) + (nameValue & 0xFFFFFFFFFFFFFFL);
                    break;
                default:
                    break;
            }
            ++i;
        }

        if (nameValue != 0) {
            return nameValue;
        }

        long hashCode = Fnv.MAGIC_HASH_CODE;
        while (offset < end) {
            int c = bytes[offset];

            if (c == '\\') {
                c = bytes[++offset];
                switch (c) {
                    case 'u': {
                        c = char4(bytes[offset + 1], bytes[offset + 2], bytes[offset + 3], bytes[offset + 4]);
                        offset += 4;
                        break;
                    }
                    case 'x': {
                        c = char2(bytes[offset + 1], bytes[offset + 2]);
                        offset += 2;
                        break;
                    }
                    case '\\':
                    case '"':
                    default:
                        c = char1(c);
                        break;
                }
            } else if (c == '"') {
                break;
            }

            offset++;
            if (c == '_' || c == '-' || c == ' ') {
                byte c1 = bytes[offset];
                if (c1 != '"' && c1 != '\'' && c1 != c) {
                    continue;
                }
            }

            if (c >= 'A' && c <= 'Z') {
                c = (char) (c + 32);
            }
            hashCode ^= c < 0 ? (c & 0xFF) : c;
            hashCode *= Fnv.MAGIC_PRIME;
        }

        return hashCode;
    }

    @Override
    public final String getFieldName() {
        final byte[] bytes = this.bytes;
        int offset = nameBegin;
        int length = nameEnd - offset;
        if (!nameEscape) {
            if (this.str != null) {
                return this.str.substring(offset, nameEnd);
            } else if (ANDROID) {
                return getLatin1String(offset, length);
            } else {
                return new String(bytes, offset, length, StandardCharsets.ISO_8859_1);
            }
        }

        if (JDKUtils.STRING_CREATOR_JDK11 != null) {
            byte[] chars = new byte[nameLength];
            forStmt:
            for (int i = 0; offset < nameEnd; ++i) {
                byte b = bytes[offset];

                if (b == '\\') {
                    b = bytes[++offset];
                    switch (b) {
                        case 'u': {
                            char c = char4(bytes[offset + 1], bytes[offset + 2], bytes[offset + 3], bytes[offset + 4]);
                            offset += 4;
                            if (c > 0xFF) {
                                chars = null;
                                break forStmt;
                            }
                            b = (byte) c;
                            break;
                        }
                        case 'x': {
                            char c = char2(bytes[offset + 1], bytes[offset + 2]);
                            offset += 2;
                            if (c > 0xFF) {
                                chars = null;
                                break forStmt;
                            }
                            b = (byte) c;
                            break;
                        }
                        case '\\':
                        case '"':
                        case '.':
                        case '-':
                        case '+':
                        case '*':
                        case '/':
                        case '>':
                        case '<':
                        case '=':
                        case '@':
                        case ':':
                            break;
                        default:
                            b = (byte) char1(b);
                            break;
                    }
                } else if (b == '"') {
                    break;
                }
                chars[i] = b;
                offset++;
            }

            if (chars != null) {
                return STRING_CREATOR_JDK11.apply(chars, LATIN1);
            }
        }

        offset = nameBegin;
        char[] chars = new char[nameLength];
        for (int i = 0; offset < nameEnd; ++i) {
            char c = (char) (bytes[offset] & 0xff);

            if (c == '\\') {
                c = (char) bytes[++offset];
                switch (c) {
                    case 'u': {
                        int c1 = bytes[offset + 1];
                        int c2 = bytes[offset + 2];
                        int c3 = bytes[offset + 3];
                        int c4 = bytes[offset + 4];
                        c = char4(c1, c2, c3, c4);
                        offset += 4;
                        break;
                    }
                    case 'x': {
                        int c1 = bytes[offset + 1];
                        int c2 = bytes[offset + 2];
                        c = char2(c1, c2);
                        offset += 2;
                        break;
                    }
                    case '.':
                    case '-':
                    case '+':
                    case '*':
                    case '/':
                    case '>':
                    case '<':
                    case '=':
                    case '@':
                        break;
                    default:
                        c = char1(c);
                        break;
                }
            } else if (c == '"') {
                break;
            }
            chars[i] = c;
            offset++;
        }

        return new String(chars);
    }

    @Override
    public final String readFieldName() {
        if (ch != '"' && ch != '\'') {
            if ((context.features & Feature.AllowUnQuotedFieldNames.mask) != 0 && isFirstIdentifier(ch)) {
                return readFieldNameUnquote();
            }

            return null;
        }

        final byte[] bytes = this.bytes;
        final char quote = ch;
        this.nameEscape = false;
        int offset = this.nameBegin = this.offset;
        final int nameBegin = this.nameBegin;
        for (int i = 0; offset < end; ++i) {
            int c = bytes[offset];
            if (c == '\\') {
                nameEscape = true;
                c = bytes[offset + 1];
                offset += (c == 'u' ? 6 : (c == 'x' ? 4 : 2));
                continue;
            }

            if (c == quote) {
                this.nameLength = i;
                this.nameEnd = offset;
                offset++;
                c = bytes[offset];

                while (c <= ' ' && ((1L << c) & SPACE) != 0) {
                    offset++;
                    c = bytes[offset];
                }
                if (c != ':') {
                    throw new JSONException("syntax error : " + offset);
                }

                offset++;
                if (offset >= end) {
                    this.ch = EOI;
                    throw new JSONException("syntax error : " + offset);
                }

                c = bytes[offset];

                while (c <= ' ' && ((1L << c) & SPACE) != 0) {
                    offset++;
                    c = bytes[offset];
                }

                this.offset = offset + 1;
                this.ch = (char) c;
                break;
            }

            offset++;
        }

        if (nameEnd < nameBegin) {
            throw new JSONException("syntax error : " + offset);
        }

        if (!nameEscape) {
            long nameValue0 = -1, nameValue1 = -1;
            int length = nameEnd - nameBegin;
            switch (length) {
                case 1:
                    return TypeUtils.toString(bytes[nameBegin]);
                case 2:
                    return TypeUtils.toString(
                            bytes[nameBegin],
                            bytes[nameBegin + 1]
                    );
                case 3:
                    nameValue0
                            = (bytes[nameBegin + 2] << 16)
                            + ((bytes[nameBegin + 1] & 0xFF) << 8)
                            + (bytes[nameBegin] & 0xFF);
                    break;
                case 4:
                    nameValue0
                            = (bytes[nameBegin + 3] << 24)
                            + ((bytes[nameBegin + 2] & 0xFF) << 16)
                            + ((bytes[nameBegin + 1] & 0xFF) << 8)
                            + (bytes[nameBegin] & 0xFF);
                    break;
                case 5:
                    nameValue0
                            = (((long) bytes[nameBegin + 4]) << 32)
                            + ((bytes[nameBegin + 3] & 0xFFL) << 24)
                            + ((bytes[nameBegin + 2] & 0xFFL) << 16)
                            + ((bytes[nameBegin + 1] & 0xFFL) << 8)
                            + (bytes[nameBegin] & 0xFFL);
                    break;
                case 6:
                    nameValue0
                            = (((long) bytes[nameBegin + 5]) << 40)
                            + ((bytes[nameBegin + 4] & 0xFFL) << 32)
                            + ((bytes[nameBegin + 3] & 0xFFL) << 24)
                            + ((bytes[nameBegin + 2] & 0xFFL) << 16)
                            + ((bytes[nameBegin + 1] & 0xFFL) << 8)
                            + (bytes[nameBegin] & 0xFFL);
                    break;
                case 7:
                    nameValue0
                            = (((long) bytes[nameBegin + 6]) << 48)
                            + ((bytes[nameBegin + 5] & 0xFFL) << 40)
                            + ((bytes[nameBegin + 4] & 0xFFL) << 32)
                            + ((bytes[nameBegin + 3] & 0xFFL) << 24)
                            + ((bytes[nameBegin + 2] & 0xFFL) << 16)
                            + ((bytes[nameBegin + 1] & 0xFFL) << 8)
                            + (bytes[nameBegin] & 0xFFL);
                    break;
                case 8:
                    nameValue0
                            = (((long) bytes[nameBegin + 7]) << 56)
                            + ((bytes[nameBegin + 6] & 0xFFL) << 48)
                            + ((bytes[nameBegin + 5] & 0xFFL) << 40)
                            + ((bytes[nameBegin + 4] & 0xFFL) << 32)
                            + ((bytes[nameBegin + 3] & 0xFFL) << 24)
                            + ((bytes[nameBegin + 2] & 0xFFL) << 16)
                            + ((bytes[nameBegin + 1] & 0xFFL) << 8)
                            + (bytes[nameBegin] & 0xFFL);
                    break;
                case 9:
                    nameValue0 = bytes[nameBegin];
                    nameValue1
                            = (((long) bytes[nameBegin + 8]) << 56)
                            + ((bytes[nameBegin + 7] & 0xFFL) << 48)
                            + ((bytes[nameBegin + 6] & 0xFFL) << 40)
                            + ((bytes[nameBegin + 5] & 0xFFL) << 32)
                            + ((bytes[nameBegin + 4] & 0xFFL) << 24)
                            + ((bytes[nameBegin + 3] & 0xFFL) << 16)
                            + ((bytes[nameBegin + 2] & 0xFFL) << 8)
                            + (bytes[nameBegin + 1] & 0xFFL);
                    break;
                case 10:
                    nameValue0
                            = (bytes[nameBegin + 1] << 8)
                            + (bytes[nameBegin]);
                    nameValue1
                            = (((long) bytes[nameBegin + 9]) << 56)
                            + ((bytes[nameBegin + 8] & 0xFFL) << 48)
                            + ((bytes[nameBegin + 7] & 0xFFL) << 40)
                            + ((bytes[nameBegin + 6] & 0xFFL) << 32)
                            + ((bytes[nameBegin + 5] & 0xFFL) << 24)
                            + ((bytes[nameBegin + 4] & 0xFFL) << 16)
                            + ((bytes[nameBegin + 3] & 0xFFL) << 8)
                            + (bytes[nameBegin + 2] & 0xFFL);
                    break;
                case 11:
                    nameValue0
                            = (bytes[nameBegin + 2] << 16)
                            + (bytes[nameBegin + 1] << 8)
                            + (bytes[nameBegin]);
                    nameValue1
                            = (((long) bytes[nameBegin + 10]) << 56)
                            + ((bytes[nameBegin + 9] & 0xFFL) << 48)
                            + ((bytes[nameBegin + 8] & 0xFFL) << 40)
                            + ((bytes[nameBegin + 7] & 0xFFL) << 32)
                            + ((bytes[nameBegin + 6] & 0xFFL) << 24)
                            + ((bytes[nameBegin + 5] & 0xFFL) << 16)
                            + ((bytes[nameBegin + 4] & 0xFFL) << 8)
                            + (bytes[nameBegin + 3] & 0xFFL);
                    break;
                case 12:
                    nameValue0
                            = (bytes[nameBegin + 3] << 24)
                            + (bytes[nameBegin + 2] << 16)
                            + (bytes[nameBegin + 1] << 8)
                            + (bytes[nameBegin]);
                    nameValue1
                            = (((long) bytes[nameBegin + 11]) << 56)
                            + ((bytes[nameBegin + 10] & 0xFFL) << 48)
                            + ((bytes[nameBegin + 9] & 0xFFL) << 40)
                            + ((bytes[nameBegin + 8] & 0xFFL) << 32)
                            + ((bytes[nameBegin + 7] & 0xFFL) << 24)
                            + ((bytes[nameBegin + 6] & 0xFFL) << 16)
                            + ((bytes[nameBegin + 5] & 0xFFL) << 8)
                            + (bytes[nameBegin + 4] & 0xFFL);
                    break;
                case 13:
                    nameValue0
                            = (((long) bytes[nameBegin + 4]) << 32)
                            + (((long) bytes[nameBegin + 3]) << 24)
                            + (((long) bytes[nameBegin + 2]) << 16)
                            + (((long) bytes[nameBegin + 1]) << 8)
                            + ((long) bytes[nameBegin]);
                    nameValue1
                            = (((long) bytes[nameBegin + 12]) << 56)
                            + ((bytes[nameBegin + 11] & 0xFFL) << 48)
                            + ((bytes[nameBegin + 10] & 0xFFL) << 40)
                            + ((bytes[nameBegin + 9] & 0xFFL) << 32)
                            + ((bytes[nameBegin + 8] & 0xFFL) << 24)
                            + ((bytes[nameBegin + 7] & 0xFFL) << 16)
                            + ((bytes[nameBegin + 6] & 0xFFL) << 8)
                            + (bytes[nameBegin + 5] & 0xFFL);
                    break;
                case 14:
                    nameValue0
                            = (((long) bytes[nameBegin + 5]) << 40)
                            + ((bytes[nameBegin + 4] & 0xFFL) << 32)
                            + ((bytes[nameBegin + 3] & 0xFFL) << 24)
                            + ((bytes[nameBegin + 2] & 0xFFL) << 16)
                            + ((bytes[nameBegin + 1] & 0xFFL) << 8)
                            + (bytes[nameBegin] & 0xFFL);
                    nameValue1
                            = (((long) bytes[nameBegin + 13]) << 56)
                            + ((bytes[nameBegin + 12] & 0xFFL) << 48)
                            + ((bytes[nameBegin + 11] & 0xFFL) << 40)
                            + ((bytes[nameBegin + 10] & 0xFFL) << 32)
                            + ((bytes[nameBegin + 9] & 0xFFL) << 24)
                            + ((bytes[nameBegin + 8] & 0xFFL) << 16)
                            + ((bytes[nameBegin + 7] & 0xFFL) << 8)
                            + (bytes[nameBegin + 6] & 0xFFL);
                    break;
                case 15:
                    nameValue0
                            = (((long) bytes[nameBegin + 6]) << 48)
                            + ((bytes[nameBegin + 5] & 0xFFL) << 40)
                            + ((bytes[nameBegin + 4] & 0xFFL) << 32)
                            + ((bytes[nameBegin + 3] & 0xFFL) << 24)
                            + ((bytes[nameBegin + 2] & 0xFFL) << 16)
                            + ((bytes[nameBegin + 1] & 0xFFL) << 8)
                            + (bytes[nameBegin] & 0xFFL);
                    nameValue1
                            = (((long) bytes[nameBegin + 14]) << 56)
                            + ((bytes[nameBegin + 13] & 0xFFL) << 48)
                            + ((bytes[nameBegin + 12] & 0xFFL) << 40)
                            + ((bytes[nameBegin + 11] & 0xFFL) << 32)
                            + ((bytes[nameBegin + 10] & 0xFFL) << 24)
                            + ((bytes[nameBegin + 9] & 0xFFL) << 16)
                            + ((bytes[nameBegin + 8] & 0xFFL) << 8)
                            + (bytes[nameBegin + 7] & 0xFFL);
                    break;
                case 16:
                    nameValue0
                            = (((long) bytes[nameBegin + 7]) << 56)
                            + ((bytes[nameBegin + 6] & 0xFFL) << 48)
                            + ((bytes[nameBegin + 5] & 0xFFL) << 40)
                            + ((bytes[nameBegin + 4] & 0xFFL) << 32)
                            + ((bytes[nameBegin + 3] & 0xFFL) << 24)
                            + ((bytes[nameBegin + 2] & 0xFFL) << 16)
                            + ((bytes[nameBegin + 1] & 0xFFL) << 8)
                            + (bytes[nameBegin] & 0xFFL);
                    nameValue1
                            = (((long) bytes[nameBegin + 15]) << 56)
                            + ((bytes[nameBegin + 14] & 0xFFL) << 48)
                            + ((bytes[nameBegin + 13] & 0xFFL) << 40)
                            + ((bytes[nameBegin + 12] & 0xFFL) << 32)
                            + ((bytes[nameBegin + 11] & 0xFFL) << 24)
                            + ((bytes[nameBegin + 10] & 0xFFL) << 16)
                            + ((bytes[nameBegin + 9] & 0xFFL) << 8)
                            + (bytes[nameBegin + 8] & 0xFFL);
                    break;
                default:
                    break;
            }

            if (nameValue0 != -1) {
                if (nameValue1 != -1) {
                    long nameValue01 = nameValue0 ^ nameValue1;
                    int indexMask = ((int) (nameValue01 ^ (nameValue01 >>> 32))) & (NAME_CACHE2.length - 1);
                    JSONFactory.NameCacheEntry2 entry = NAME_CACHE2[indexMask];
                    if (entry == null) {
                        char[] chars = new char[length];
                        for (int i = 0; i < length; ++i) {
                            chars[i] = (char) (bytes[nameBegin + i] & 0xFF);
                        }

                        String name;
                        if (STRING_CREATOR_JDK8 != null) {
                            name = STRING_CREATOR_JDK8.apply(chars, Boolean.TRUE);
                        } else {
                            name = new String(chars);
                        }

                        NAME_CACHE2[indexMask] = new JSONFactory.NameCacheEntry2(name, nameValue0, nameValue1);
                        return name;
                    } else if (entry.value0 == nameValue0 && entry.value1 == nameValue1) {
                        return entry.name;
                    }
                } else {
                    int indexMask = ((int) (nameValue0 ^ (nameValue0 >>> 32))) & (NAME_CACHE.length - 1);
                    JSONFactory.NameCacheEntry entry = NAME_CACHE[indexMask];
                    if (entry == null) {
                        char[] chars = new char[length];
                        for (int i = 0; i < length; ++i) {
                            chars[i] = (char) (bytes[nameBegin + i] & 0xFF);
                        }

                        String name;
                        if (STRING_CREATOR_JDK8 != null) {
                            name = STRING_CREATOR_JDK8.apply(chars, Boolean.TRUE);
                        } else {
                            name = new String(chars);
                        }

                        NAME_CACHE[indexMask] = new JSONFactory.NameCacheEntry(name, nameValue0);
                        return name;
                    } else if (entry.value == nameValue0) {
                        return entry.name;
                    }
                }
            }
        }

        return getFieldName();
    }

    @Override
    protected final void readString0() {
        final byte[] bytes = this.bytes;
        char quote = this.ch;
        final int start = offset;
        int offset = this.offset;
        int valueLength;
        valueEscape = false;

        for (int i = 0; ; ++i) {
            int c = bytes[offset];
            if (c == '\\') {
                valueEscape = true;
                c = bytes[++offset];
                switch (c) {
                    case 'u': {
                        offset += 4;
                        break;
                    }
                    case 'x': {
                        offset += 2;
                        break;
                    }
                    default:
                        break;
                }
                offset++;
                continue;
            }

            if (c == quote) {
                valueLength = i;
                break;
            }
            offset++;
        }

        String str;
        if (valueEscape) {
            char[] chars = new char[valueLength];
            offset = start;
            for (int i = 0; ; ++i) {
                char c = (char) (bytes[offset] & 0xff);
                if (c == '\\') {
                    c = (char) (bytes[++offset]);
                    switch (c) {
                        case 'u': {
                            c = char4(bytes[offset + 1], bytes[offset + 2], bytes[offset + 3], bytes[offset + 4]);
                            offset += 4;
                            break;
                        }
                        case 'x': {
                            c = char2(bytes[offset + 1], bytes[offset + 2]);
                            offset += 2;
                            break;
                        }
                        case '\\':
                        case '"':
                            break;
                        default:
                            c = char1(c);
                            break;
                    }
                } else if (c == '"') {
                    break;
                }
                chars[i] = c;
                offset++;
            }

            str = new String(chars);
        } else {
            if (STRING_CREATOR_JDK11 != null) {
                byte[] buf = Arrays.copyOfRange(bytes, start, offset);
                str = STRING_CREATOR_JDK11.apply(buf, LATIN1);
            } else {
                str = new String(bytes, start, offset - start, StandardCharsets.ISO_8859_1);
            }
        }

        int b = bytes[++offset];
        while (b <= ' ' && ((1L << b) & SPACE) != 0) {
            b = bytes[++offset];
        }

        this.offset = offset + 1;
        if (comma = (b == ',')) {
            next();
        } else {
            this.ch = (char) b;
        }

        stringValue = str;
    }

    @Override
    public String readString() {
        if (ch == '"' || ch == '\'') {
            final byte[] bytes = this.bytes;
            final byte quote = (byte) ch;
            final byte slash = (byte) '\\';

            int offset = this.offset;
            final int start = offset;
            int valueLength;
            boolean valueEscape = false;

            _for:
            {
                int i = 0;
                byte c0 = 0, c1 = 0, c2 = 0, c3;

                // vector optimize
                boolean quoted = false;
                int upperBound = offset + ((end - offset) & ~3);
                while (offset < upperBound) {
                    c0 = bytes[offset];
                    c1 = bytes[offset + 1];
                    c2 = bytes[offset + 2];
                    c3 = bytes[offset + 3];
                    if (c0 == slash || c1 == slash || c2 == slash || c3 == slash) {
                        break;
                    }
                    if (c0 == quote || c1 == quote || c2 == quote || c3 == quote) {
                        quoted = true;
                        break;
                    }
                    offset += 4;
                    i += 4;
                }

                if (quoted) {
                    if (c0 == quote) {
                        // skip
                    } else if (c1 == quote) {
                        offset++;
                        i++;
                    } else if (c2 == quote) {
                        offset += 2;
                        i += 2;
                    } else {
                        offset += 3;
                        i += 3;
                    }
                    valueLength = i;
                } else {
                    for (; ; ++i) {
                        if (offset >= end) {
                            throw new JSONException("invalid escape character EOI");
                        }

                        byte c = bytes[offset];
                        if (c == slash) {
                            valueEscape = true;
                            c = bytes[offset + 1];
                            offset += (c == 'u' ? 6 : (c == 'x' ? 4 : 2));
                            continue;
                        }

                        if (c == quote) {
                            valueLength = i;
                            break _for;
                        }
                        offset++;
                    }
                }
            }

            String str;
            if (valueEscape) {
                char[] buf = new char[valueLength];
                offset = start;
                for (int i = 0; ; ++i) {
                    char c = (char) (bytes[offset] & 0xff);
                    if (c == '\\') {
                        c = (char) bytes[++offset];
                        switch (c) {
                            case 'u': {
                                c = char4(bytes[offset + 1], bytes[offset + 2], bytes[offset + 3], bytes[offset + 4]);
                                offset += 4;
                                break;
                            }
                            case 'x': {
                                c = char2(bytes[offset + 1], bytes[offset + 2]);
                                offset += 2;
                                break;
                            }
                            case '\\':
                            case '"':
                                break;
                            case 'b':
                                c = '\b';
                                break;
                            case 't':
                                c = '\t';
                                break;
                            case 'n':
                                c = '\n';
                                break;
                            case 'f':
                                c = '\f';
                                break;
                            case 'r':
                                c = '\r';
                                break;
                            default:
                                c = char1(c);
                                break;
                        }
                    } else if (c == quote) {
                        break;
                    }
                    buf[i] = c;
                    offset++;
                }

                str = new String(buf);
            } else {
                if (this.str != null) {
                    str = this.str.substring(start, offset);
                } else if (STRING_CREATOR_JDK11 != null) {
                    str = STRING_CREATOR_JDK11.apply(Arrays.copyOfRange(bytes, start, offset), LATIN1);
                } else if (ANDROID) {
                    str = getLatin1String(start, offset - start);
                } else {
                    str = new String(bytes, start, offset - start, StandardCharsets.ISO_8859_1);
                }
            }

            if ((context.features & Feature.TrimString.mask) != 0) {
                str = str.trim();
            }

            int ch = ++offset == end ? EOI : bytes[offset++];
            while (ch <= ' ' && (1L << ch & SPACE) != 0) {
                ch = offset == end ? EOI : bytes[offset++];
            }

            if (comma = ch == ',') {
                ch = offset == end ? EOI : bytes[offset++];
                while (ch <= ' ' && (1L << ch & SPACE) != 0) {
                    ch = offset == end ? EOI : bytes[offset++];
                }
            }

            this.ch = (char) (ch & 0xFF);
            this.offset = offset;
            return str;
        }

        switch (ch) {
            case '[':
                return toString(
                        readArray());
            case '{':
                return toString(
                        readObject());
            case '-':
            case '+':
            case '0':
            case '1':
            case '2':
            case '3':
            case '4':
            case '5':
            case '6':
            case '7':
            case '8':
            case '9':
                readNumber0();
                Number number = getNumber();
                return number.toString();
            case 't':
            case 'f':
                boolValue = readBoolValue();
                return boolValue ? "true" : "false";
            case 'n': {
                readNull();
                return null;
            }
            default:
                throw new JSONException(info("illegal input : " + ch));
        }
    }
}
