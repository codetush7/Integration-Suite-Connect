import com.sap.gateway.ip.core.customdev.util.Message
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Arrays

// ── EBCDIC lookup table (ASCII byte index → EBCDIC byte) ─────────────────────
// Ported 1:1 from CharSetConversion.toEBCDIC[]
class Constants {
    static final int[] TO_EBCDIC = [
        0x00,0x01,0x02,0x03,0x37,0x2D,0x2E,0x2F,0x16,0x05,0x25,0x0B,0x0C,0x0D,0x0E,0x0F,
        0x10,0x11,0x12,0x13,0x3C,0x3D,0x32,0x26,0x18,0x19,0x3F,0x27,0x1C,0x1D,0x1E,0x1F,
        0x40,0x4F,0x7F,0x7B,0x5B,0x6C,0x50,0x7D,0x4D,0x5D,0x5C,0x4E,0x6B,0x60,0x4B,0x61,
        0xF0,0xF1,0xF2,0xF3,0xF4,0xF5,0xF6,0xF7,0xF8,0xF9,0x7A,0x5E,0x4C,0x7E,0x6E,0x6F,
        0x7C,0xC1,0xC2,0xC3,0xC4,0xC5,0xC6,0xC7,0xC8,0xC9,0xD1,0xD2,0xD3,0xD4,0xD5,0xD6,
        0xD7,0xD8,0xD9,0xE2,0xE3,0xE4,0xE5,0xE6,0xE7,0xE8,0xE9,0x4A,0xE0,0x5A,0x5F,0x6D,
        0x79,0x81,0x82,0x83,0x84,0x85,0x86,0x87,0x88,0x89,0x91,0x92,0x93,0x94,0x95,0x96,
        0x97,0x98,0x99,0xA2,0xA3,0xA4,0xA5,0xA6,0xA7,0xA8,0xA9,0xC0,0x6A,0xD0,0xA1,0x07,
        0x20,0x21,0x22,0x23,0x24,0x15,0x06,0x17,0x28,0x29,0x2A,0x2B,0x2C,0x09,0x0A,0x1B,
        0x30,0x31,0x1A,0x33,0x34,0x35,0x36,0x08,0x38,0x39,0x3A,0x3B,0x04,0x14,0x3E,0xE1,
        0x41,0x42,0x43,0x44,0x45,0x46,0x47,0x48,0x49,0x51,0x52,0x53,0x54,0x55,0x56,0x57,
        0x58,0x59,0x62,0x63,0x64,0x65,0x66,0x67,0x68,0x69,0x70,0x71,0xB7,0xB8,0xB9,0x75,
        0x64,0x65,0x62,0x80,0x63,0x8B,0x8C,0x68,0x74,0x71,0x72,0x73,0x78,0x75,0x76,0x77,
        0x9F,0x20,0xED,0xEE,0xEB,0xAD,0xEC,0xAF,0xB0,0xFD,0xFE,0xFB,0xFC,0xB5,0xB6,0xB7,
        0x44,0x45,0x42,0xBB,0x43,0xBD,0xBE,0x48,0x54,0x51,0x52,0x53,0x58,0x55,0x56,0x57,
        0xDC,0xDD,0xCD,0xCE,0xCB,0xEB,0xCC,0xED,0xEE,0xDD,0xDE,0xDB,0xDC,0xFD,0xFE,0xFF
    ] as int[]

    static final int TYPE_BIN    = 1
    static final int TYPE_COMP   = 2
    static final int TYPE_SCOMP  = 3
    static final int TYPE_COMP3  = 4
    static final int TYPE_SCOMP3 = 5
    static final int TYPE_BINNU  = 6
    static final int TYPE_FILLER = 7
    static final int TYPE_VFILL  = 8
    static final int TYPE_SIGN   = 9
    static final int TYPE_TEXT   = 10
}

// ── Field descriptor ─────────────────────────────────────────────────────────
class FieldDef {
    int pos       // 1-based position within record
    int len       // field length in bytes (source)
    int type
    int number    // COMP: output byte-width; COMP3: output byte count; TEXT: 0

    FieldDef(int pos, int len, int type, int number) {
        this.pos = pos; this.len = len; this.type = type; this.number = number
    }
}

// ── Parse "pos,len,TYPE,num;..." field config string ─────────────────────────
List<FieldDef> parseFields(String cfg) {
    if (!cfg?.trim()) return []
    cfg.split(";").collect { part ->
        def v = part.trim().split(",")
        int pos = v[0].toInteger()
        int len = v[1].toInteger()
        String t = v[2].trim().toUpperCase()
        int num  = (v.size() > 3) ? v[3].toInteger() : 0
        int type = Constants.TYPE_TEXT
        switch (t) {
            case "COMP":    type = Constants.TYPE_COMP;    break
            case "SCOMP":   type = Constants.TYPE_SCOMP;   break
            case "COMP3":   type = Constants.TYPE_COMP3;   break
            case "SCOMP3":  type = Constants.TYPE_SCOMP3;  break
            case "FILLER":  type = Constants.TYPE_FILLER;  break
            case "VFILLER": type = Constants.TYPE_VFILL;   break
            case "SIGN":    type = Constants.TYPE_SIGN;    break
            case "BIN":     type = Constants.TYPE_BIN;     break
            case "BINNU":   type = Constants.TYPE_BINNU;   break
        }
        new FieldDef(pos, len, type, num)
    }
}

// ── ASCII → EBCDIC (byte array) ───────────────────────────────────────────────
byte[] asciiToEbcdic(byte[] src) {
    byte[] out = new byte[src.length]
    for (int i = 0; i < src.length; i++) {
        out[i] = (byte)(Constants.TO_EBCDIC[src[i] & 0xFF] & 0xFF)
    }
    return out
}

// ── COMP / SCOMP → big-endian binary integer ─────────────────────────────────
// Ported from ConversionBin.stringToNumber + longToByteArray
byte[] stringToNumber(String inp, int fieldLen) {
    int byteSize = (fieldLen >= 8) ? 8 : ((fieldLen >= 4) ? 4 : 2)
    long val = 0L
    try { val = Long.parseLong(inp.trim()) } catch (Exception ignored) {}
    byte[] out = new byte[byteSize]
    for (int i = 0; i < byteSize; i++) {
        int shift = byteSize * 8 - 8 * (i + 1)
        out[i] = (byte)((val >>> shift) & 0xFF)
    }
    return out
}

// ── COMP3 / SCOMP3 → IBM packed BCD ─────────────────────────────────────────
// Ported from ConversionBin.stringToComp3AsInt + longToByteArray
byte[] stringToComp3(String inp, int fieldLen) {
    if (!inp?.trim()) return new byte[fieldLen]
    String s = inp.trim()
    if (s.contains("-")) {
        s = s.substring(s.indexOf("-") + 1) + "D"
    } else {
        s = s + "C"
    }
    long val = Long.parseLong(s, 16)
    byte[] out = new byte[fieldLen]
    for (int i = 0; i < fieldLen; i++) {
        int shift = fieldLen * 8 - 8 * (i + 1)
        out[i] = (byte)((val >>> shift) & 0xFF)
    }
    return out
}

// ── FILLER → 0xFF × len ──────────────────────────────────────────────────────
byte[] fillerBytes(int len) {
    byte[] out = new byte[len]
    Arrays.fill(out, (byte) 0xFF)
    return out
}

// ── Convert one fixed-width record using its field list ───────────────────────
void convertRecord(byte[] payload, int recordStart, int recordStride,
                   List<FieldDef> fields, ByteArrayOutputStream out,
                   boolean debug, StringBuilder log) throws IOException {

    for (FieldDef f : fields) {
        int fStart = recordStart + (f.pos - 1)   // absolute 0-based start
        int fEnd   = fStart + f.len

        // Guard: field must not exceed the record boundary
        int recordEnd = recordStart + recordStride
        if (fEnd > recordEnd) {
            throw new RuntimeException(
                "Field pos=${f.pos} len=${f.len} exceeds record boundary " +
                "(recordStart=${recordStart}, recordStride=${recordStride}, " +
                "payloadLen=${payload.length})"
            )
        }

        byte[] chunk  = Arrays.copyOfRange(payload, fStart, fEnd)
        byte[] outVal

        switch (f.type) {
            case Constants.TYPE_FILLER:
                outVal = fillerBytes(f.len)
                break
            case Constants.TYPE_VFILL:
                String sv = new String(chunk, "ISO-8859-1")
                outVal = sv.trim().length() > 0 ? asciiToEbcdic(chunk) : fillerBytes(f.len)
                break
            case Constants.TYPE_COMP:
            case Constants.TYPE_SCOMP:
                outVal = stringToNumber(new String(chunk, "ISO-8859-1"), f.number)
                break
            case Constants.TYPE_COMP3:
            case Constants.TYPE_SCOMP3:
                outVal = stringToComp3(new String(chunk, "ISO-8859-1"), f.number)
                break
            // TEXT, SIGN, BIN, BINNU, default → ASCII→EBCDIC
            default:
                outVal = asciiToEbcdic(chunk)
                break
        }

        if (debug) {
            log.append("  [pos=${f.pos} len=${f.len} type=${f.type}] " +
                "'${new String(chunk, 'ISO-8859-1')}' → " +
                "${outVal.collect { String.format('%02X', it & 0xFF) }.join(' ')}\n")
        }

        out.write(outVal)
    }
}

// ═════════════════════════════════════════════════════════════════════════════
// CPI entry point
// ═════════════════════════════════════════════════════════════════════════════
def Message processData(Message message) {

    def props = message.getProperties()

    // ── Read configuration from message properties ───────────────────────
    String  moduleMode = props.get("conversionModule") ?: "default"
    String  fieldsStr  = props.get("fields")           ?: ""
    int     recordLen  = (props.get("recordLen") ?: "0").toInteger()
    boolean addEOL     = (props.get("addEOL")    ?: "false").equalsIgnoreCase("true")
    boolean debug      = (props.get("debug")     ?: "false").equalsIgnoreCase("true")

    // ── Read payload as ISO-8859-1 bytes ─────────────────────────────────
    // getBody(String) decodes using the exchange charset (UTF-8 after mapping).
    // getBytes("ISO-8859-1") then gives correct single-byte values for all
    // ISO-8859-1 characters, including accented chars like É, à, etc.
    String bodyStr  = message.getBody(String.class)
    byte[] payload  = bodyStr.getBytes("ISO-8859-1")

    // ── Strip trailing CR / LF added by the mapping step ─────────────────
    // The mapping step typically appends \n (0x0A) or \r\n (0x0D 0x0A).
    // Strip them so the payload length matches the declared recordLen exactly.
    // This way recordLen in the property only needs to reflect the data width.
    int payloadEnd = payload.length
    while (payloadEnd > 0 && (payload[payloadEnd - 1] == 0x0A || payload[payloadEnd - 1] == 0x0D)) {
        payloadEnd--
    }
    if (payloadEnd < payload.length) {
        payload = Arrays.copyOfRange(payload, 0, payloadEnd)
    }

    if (debug) {
        message.setProperty("cpc_payloadLen", String.valueOf(payload.length))
    }

    ByteArrayOutputStream obuffer = new ByteArrayOutputStream()
    StringBuilder         log     = new StringBuilder()

    // ════════════════════════════════════════════════════════════════════
    // DYNAMIC mode — record type selected by a key byte within each record
    // ════════════════════════════════════════════════════════════════════
  if (moduleMode == "dynamic") {

    // Use message.getProperty() directly — more reliable in CPI than map lookup
    String keysStr = message.getProperty("dynamic.recordKeys") 
                     ?: props.get("dynamic.recordKeys") ?: ""
    int keyPos     = ((message.getProperty("dynamic.keyPos") 
                     ?: props.get("dynamic.keyPos") ?: "0") as String).toInteger()
    int keyLen     = ((message.getProperty("dynamic.keyLen") 
                     ?: props.get("dynamic.keyLen") ?: "1") as String).toInteger()

    Map<String, List<FieldDef>> fieldConfigs = [:]
    Map<String, Integer>        strides      = [:]

    keysStr.split(",").each { key ->
        String trimKey = key.trim()
        String cfg = (message.getProperty("dynamic.${trimKey}.fields")
                     ?: props.get("dynamic.${trimKey}.fields") ?: "")
        
        if (debug) {
            message.setProperty("dbg_cfg_${trimKey}", cfg ?: "NULL")
        }
        
        List<FieldDef> flist = parseFields(cfg)
        int stride = flist ? (flist.sum { it.len } as int) : 0
        if (stride == 0) {
            throw new RuntimeException(
                "CodePageConversion [dynamic]: no fields configured for key '${trimKey}'. " +
                "Check property 'dynamic.${trimKey}.fields' is set correctly. " +
                "Raw cfg value: '${cfg}'"
            )
        }
        fieldConfigs[trimKey] = flist
        strides[trimKey]      = stride
    }

  int current = 0
while (current < payload.length) {

    // ── Skip any line-ending bytes between records ──────────────────────
    while (current < payload.length &&
           (payload[current] == (byte)0x0A || payload[current] == (byte)0x0D)) {
        current++
    }
    if (current >= payload.length) break

    // ── Read the key from the current record ────────────────────────────
    if (current + keyPos + keyLen > payload.length) {
        throw new RuntimeException(
            "CodePageConversion [dynamic]: payload too short to read key " +
            "at offset ${current} (payloadLen=${payload.length})"
        )
    }

    byte[] keyChunk = Arrays.copyOfRange(payload,
                                         current + keyPos,
                                         current + keyPos + keyLen)
    String key = new String(keyChunk, "ISO-8859-1").trim()

    if (!fieldConfigs.containsKey(key)) {
        throw new RuntimeException(
            "CodePageConversion [dynamic]: unknown record key '${key}' " +
            "at byte offset ${current}. " +
            "Configured keys: ${fieldConfigs.keySet()}"
        )
    }

    int stride = strides[key]
    if (debug) log.append("Record at ${current}, key='${key}', stride=${stride}\n")
    convertRecord(payload, current, stride, fieldConfigs[key], obuffer, debug, log)
    if (addEOL) obuffer.write(0x0A)
    current += stride
}
    }
    else {

        List<FieldDef> fields = parseFields(fieldsStr)

        // If recordLen not set, derive it from the sum of field lengths
        if (recordLen <= 0) {
            recordLen = fields.sum { it.len } as int
        }

        int current = 0
        while (current < payload.length) {

            // Last record may be shorter than recordLen (edge case)
            int stride = Math.min(recordLen, payload.length - current)

            if (debug) log.append("Record at offset ${current}, stride=${stride}\n")
            convertRecord(payload, current, stride, fields, obuffer, debug, log)
            if (addEOL) obuffer.write(0x0A)
            current += recordLen
        }
    }

    // ── Set output ───────────────────────────────────────────────────────
    byte[] result = obuffer.toByteArray()

  if (debug) {
    message.setProperty("cpc_outputLen",  String.valueOf(result.length))
    message.setProperty("cpc_debugLog",   log.toString())
    byte[] snippet = Arrays.copyOfRange(result, 0, Math.min(32, result.length))
    String hexSnippet = snippet.collect {
        String.format('%02X', it & 0xFF)
    }.join(' ')
    message.setProperty("cpc_first32hex", hexSnippet)
}

    message.setBody(new ByteArrayInputStream(result))
    message.setHeader("Content-Type", "application/octet-stream")

    return message
}