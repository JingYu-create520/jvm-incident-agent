package dev.jingyu.jia.parse;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnmappableCharacterException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Reading incident files robustly: they come from other people's machines. */
public final class TextFiles {

    /** Windows Java services and older Chinese teams frequently emit GBK logs. */
    private static final Charset GBK = Charset.forName("GBK");

    private TextFiles() {
    }

    public record Read(String fileName, List<String> lines, Charset charset) {
    }

    public static List<String> readLines(Path file) throws IOException {
        byte[] raw = Files.readAllBytes(file);
        return decode(raw);
    }

    /**
     * UTF-8 when it is genuinely UTF-8, GBK as a fallback, and never a crash:
     * a log with a few mangled bytes still yields usable stacks.
     */
    public static List<String> decode(byte[] raw) {
        byte[] body = stripBom(raw);
        Charset picked = StandardCharsets.UTF_8;
        if (!canDecode(StandardCharsets.UTF_8, body)) {
            picked = GBK;
        }
        String text;
        try {
            text = new String(body, picked);
        } catch (RuntimeException e) {
            text = new String(body, StandardCharsets.ISO_8859_1);
            picked = StandardCharsets.ISO_8859_1;
        }
        return splitLines(text, picked);
    }

    public static List<String> splitLines(String text, Charset picked) {
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        List<String> out = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < normalized.length(); i++) {
            if (normalized.charAt(i) == '\n') {
                out.add(normalized.substring(start, i));
                start = i + 1;
            }
        }
        if (start < normalized.length()) {
            out.add(normalized.substring(start));
        }
        while (!out.isEmpty() && out.get(out.size() - 1).isEmpty()) {
            out.remove(out.size() - 1);
        }
        return out;
    }

    private static byte[] stripBom(byte[] raw) {
        if (raw.length >= 3 && (raw[0] & 0xFF) == 0xEF && (raw[1] & 0xFF) == 0xBB && (raw[2] & 0xFF) == 0xBF) {
            byte[] out = new byte[raw.length - 3];
            System.arraycopy(raw, 3, out, 0, out.length);
            return out;
        }
        return raw;
    }

    private static boolean canDecode(Charset cs, byte[] raw) {
        CharsetDecoder dec = cs.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            dec.decode(ByteBuffer.wrap(raw));
            return true;
        } catch (CharacterCodingException e) {
            if (e instanceof UnmappableCharacterException) {
                return true;
            }
            return false;
        }
    }
}
