package com.charge.ocpp.resolve;


import com.charge.ocpp.resolve.entity.AgreementVersion;
import com.charge.ocpp.resolve.entity.ContentTemplate;
import com.charge.ocpp.resolve.entity.TypeTemplate;
import com.charge.ocpp.resolve.utils.CodeUtils;
import com.charge.ocpp.resolve.utils.HexUtils;
import com.charge.ocpp.resolve.utils.HttpClientTool;
import com.charge.ocpp.resolve.utils.VersionHexDecoderUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.CollectionType;
import lombok.extern.slf4j.Slf4j;

import java.io.Serializable;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * OcppDecoder V3：面向 Spark Structured Streaming 的强类型、高性能解析器。
 *
 * 与 V1 的差异：
 *  1. 解析结果不再是一个合并大 Map，而是三个桶：
 *       signals    —— 数值类型字段
 *       extra_info —— 字符串类型字段
 *       map_array  —— 数值数组类型字段
 *  2. 字段归属哪个桶，由 ContentTemplate.getMapKey()（即原 signalsTypeKeyMap 的 value：
 *     "signals" / "extra_info" / "map_array"）决定；
 *     输出 key 使用 ContentTemplate.getHiveKey()（即原 hiveKeyMap 的 value）。
 *  3. 上述映射在【构造期】编译成 CompiledField 静态计划，运行期不再拼 indexKey
 *     字符串去查 signalsTypeKeyMap / hiveKeyMap —— 每个字段省掉一次字符串拼接
 *     + toUpperCase + 哈希查找，这是大数据量下的主要优化点。
 *  4. 输出采用 Spark 可稳定推导的类型：
 *     signals=Map<String, Double>、extra_info=Map<String, String>、
 *     map_array=Map<String, double[]>。
 *  5. 数值数组在解析阶段直接写入 double[]，不进行二次遍历和装箱。
 */
@Slf4j
public class OcppDecoderV3 implements Serializable {

    private static final long serialVersionUID = 1L;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ENCODE_TYPE_ASCII = "ASCII";
    private static final String ENCODE_TYPE_BIN = "BIN";
    private static final String ENCODE_TYPE_BCD = "BCD";

    private static final String FORMAT_STR = "STR";
    private static final String FORMAT_BINARY = "BINARY";
    private static final String FORMAT_BINARY_HIGH = "BINARY_HIGH";
    private static final String FORMAT_INT = "INT";
    private static final String FORMAT_SIGNED_INT = "SIGNED_INT";
    private static final String FORMAT_DOUBLE = "DOUBLE";
    private static final String FORMAT_SIGNED_DOUBLE = "SIGNED_DOUBLE";
    private static final String FORMAT_TIME = "TIME";
    private static final String FORMAT_ARRAY_INT = "ARRAY_INT";
    private static final String FORMAT_ARRAY_SIGNED_INT = "ARRAY_SIGNED_INT";
    private static final String FORMAT_ARRAY_DOUBLE = "ARRAY_DOUBLE";
    private static final String FORMAT_ARRAY_STR = "ARRAY_STR";
    private static final String FORMAT_ARRAY_BIN = "ARRAY_BIN";

    private static final String KEY_FUNC_CODE = "funcCode";
    private static final int SPECIAL_LENGTH = -1;
    private static final String CP56TIME2A_DEFAULT_HEX = "00000000000000";
    private static final String CP56TIME2A_DEFAULT_TIME = "1970-01-02 08:00:01";

    // 桶编号
    private static final int BUCKET_SIGNALS = 0;
    private static final int BUCKET_EXTRA_INFO = 1;
    private static final int BUCKET_MAP_ARRAY = 2;
    private static final int BUCKET_BY_VALUE = -1; // mapKey 未配置：按运行时类型分类

    // ==================== 编译期结构 ====================

    /** 单个字段的解析计划：输出 key 与目标桶在构造期固化 */
    private static final class CompiledField implements Serializable {
        final ContentTemplate ct;
        final String outKey;   // hiveKey（为空回退为字段原始 key）
        final int bucket;      // 0/1/2，或 -1 表示按值类型分类

        CompiledField(ContentTemplate ct, String outKey, int bucket) {
            this.ct = ct;
            this.outKey = outKey;
            this.bucket = bucket;
        }
    }

    /** 一个 TypeTemplate 编译后的完整计划，含各桶预分配容量 */
    private static final class CompiledTemplate implements Serializable {
        final TypeTemplate typeTemplate;
        final CompiledField[] fields;
        final int signalsSize;
        final int extraInfosSize;
        final int mapArraySize;
        final int signalsCap;
        final int extraInfoCap;
        final int mapArrayCap;

        CompiledTemplate(TypeTemplate t, CompiledField[] fields,
                         int signalsSize,int extraInfosSize,int mapArraySize,
                         int signalsCap, int extraInfoCap, int mapArrayCap) {
            this.typeTemplate = t;
            this.fields = fields;
            this.signalsSize = signalsSize;
            this.extraInfosSize = extraInfosSize;
            this.mapArraySize = mapArraySize;
            this.signalsCap = signalsCap;
            this.extraInfoCap = extraInfoCap;
            this.mapArrayCap = mapArrayCap;
        }
    }

    // ==================== 结果结构 ====================

    /** 三桶解析结果 */
    public static final class DecodeResult implements Serializable {
        private Map<String, Double> signals;
        private Map<String, String> extraInfo;
        private Map<String, double[]> mapArray;

        /** Spark JavaBean Encoder 反序列化所需。 */
        public DecodeResult() {
            this(4, 4, 4);
        }

        public DecodeResult(int signalsCap, int extraInfoCap, int mapArrayCap) {
            this.signals = new HashMap<>(Math.max(signalsCap, 4));
            this.extraInfo = new HashMap<>(Math.max(extraInfoCap, 4));
            this.mapArray = new HashMap<>(Math.max(mapArrayCap, 4));
        }

        public Map<String, Double> getSignals() { return signals; }
        public void setSignals(Map<String, Double> signals) { this.signals = signals; }
        public Map<String, String> getExtraInfo() { return extraInfo; }
        public void setExtraInfo(Map<String, String> extraInfo) { this.extraInfo = extraInfo; }
        public Map<String, double[]> getMapArray() { return mapArray; }
        public void setMapArray(Map<String, double[]> mapArray) { this.mapArray = mapArray; }

        public boolean isEmpty() {
            return signals.isEmpty() && extraInfo.size() <= 1 && mapArray.isEmpty();
        }
    }

    // ==================== 成员与构造 ====================

    private final Map<String, CompiledTemplate> compiledIndex;
    private final String defaultVersion;


    public OcppDecoderV3(String url){
        this(fetchAgreement(url));
    }
    private static List<AgreementVersion> fetchAgreement(String url){
        try{
            ConcurrentHashMap<String,Object> header = new ConcurrentHashMap<>();
            String json = HttpClientTool.jsonPost(url,"",header);
            CollectionType listType = MAPPER.getTypeFactory().constructCollectionType(List.class, AgreementVersion.class);
            return MAPPER.readValue(json, listType);
        }catch (Exception e){
            log.error("获取协议配置失败,url={}",url, e);
            throw new RuntimeException("OcppDecoder 初始化失败", e);
        }
    }
    public OcppDecoderV3(List<AgreementVersion> agreementVersionList) {
        Map<String, CompiledTemplate> index = new HashMap<>();
        for (AgreementVersion av : agreementVersionList) {
            String version = av.getVersion();
            for (TypeTemplate t : av.getAcceptTypeTemplateList()) {
                index.put(indexKey("ACCEPT", version, t.getCode()), compile(t));
            }
            for (TypeTemplate t : av.getSendTypeTemplateList()) {
                index.put(indexKey("SEND", version, t.getCode()), compile(t));
            }
        }
        this.compiledIndex = Collections.unmodifiableMap(index);
        this.defaultVersion = agreementVersionList.get(agreementVersionList.size() - 1).getVersion();
    }

    /**
     * 构造期编译：把 hiveKey / mapKey 固化进字段计划，并统计各桶容量。
     * 运行期不再访问 signalsTypeKeyMap / hiveKeyMap。
     */
    private static CompiledTemplate compile(TypeTemplate t) {
        List<ContentTemplate> list = t.getContentTemplateList();
        CompiledField[] fields = new CompiledField[list.size()];
        int s = 0, x = 1, a = 0, flex = 0; // extra_info 预留 funcCode 一席
        for (int i = 0; i < list.size(); i++) {
            ContentTemplate ct = list.get(i);
            String hiveKey = ct.getHiveKey();
            String outKey = (hiveKey != null && !hiveKey.isEmpty()) ? hiveKey : ct.getKey();
            int bucket = bucketOf(ct.getMapKey());
            fields[i] = new CompiledField(ct, outKey, bucket);
            switch (bucket) {
                case BUCKET_SIGNALS: s++; break;
                case BUCKET_EXTRA_INFO: x++; break;
                case BUCKET_MAP_ARRAY: a++; break;
                default: flex++; // 未配置桶的字段，三个桶都预留余量
            }
        }
        return new CompiledTemplate(t, fields,
                s,x,a,
                (s + flex) * 4 / 3 + 1,
                (x + flex) * 4 / 3 + 1,
                (a + flex) * 4 / 3 + 1);
    }

    private static int bucketOf(String mapKey) {
        if (mapKey == null) return BUCKET_BY_VALUE;
        switch (mapKey) {
            case "signals": return BUCKET_SIGNALS;
            case "extra_info": return BUCKET_EXTRA_INFO;
            case "map_array": return BUCKET_MAP_ARRAY;
            default: return BUCKET_BY_VALUE;
        }
    }

    private static String indexKey(String direction, String version, String messageId) {
        return direction + ":" + version + ":" + messageId.toUpperCase();
    }

    // ==================== 对外入口 ====================

    /**
     * 解码十六进制报文，按模板配置分桶输出。
     *
     * @param message   十六进制报文 body
     * @param messageId 报文类型标识（如 "TerminalStatusData"）
     * @param version   协议版本号（如 "X1.0"）
     * @return 三桶结果：signals / extra_info / map_array
     */
    public DecodeResult decodeForHive(String message, String messageId, String version) {
        CompiledTemplate tpl = compiledIndex.get(indexKey("ACCEPT", version, messageId));
        if (tpl == null) {
            tpl = compiledIndex.get(indexKey("SEND", version, messageId));
        }
        if (tpl == null) {
            log.error("未找到对应的解析模板, messageId={}, version={}", messageId, version);
            return new DecodeResult(0, 1, 0);
        }

        if("SysModuleVer".equals(messageId)){
            return decodeSysModuleVer(message, tpl);
        }
        DecodeResult res = tpl.typeTemplate.isUseBitParsing()
                ? analysisMessageBit(message, tpl)
                : analysisMessageByte(message, tpl);

        return res;
    }


    // ==================== 解析主流程（就地分类） ====================

    private DecodeResult analysisMessageByte(String message, CompiledTemplate tpl) {
        DecodeResult result = new DecodeResult(tpl.signalsCap, tpl.extraInfoCap, tpl.mapArrayCap);
        String funcCode = tpl.typeTemplate.getCode();
        result.extraInfo.put(KEY_FUNC_CODE, funcCode); // funcCode 是字符串，归入 extra_info
        String hexBody = message.toUpperCase();

        int analysisIndex = 0;
        for (CompiledField field : tpl.fields) {
            ContentTemplate currentContent = field.ct;
            int byteLength = currentContent.getByteLength();
            int strLength = (byteLength == SPECIAL_LENGTH)
                    ? hexBody.length() - analysisIndex
                    : byteLength * 2;
            if (analysisIndex > hexBody.length() - 1 || analysisIndex + strLength > hexBody.length()) {
                return result; // 报文截断，返回已解析部分（与 V1 行为一致）
            }
            String hexStr = hexBody.substring(analysisIndex, analysisIndex + strLength);
            Object value = analysisHex2Format(hexStr, currentContent, currentContent.getFormat(), funcCode);
            put(result, field, value);
            analysisIndex += strLength;
        }
        return result;
    }

    private DecodeResult analysisMessageBit(String message, CompiledTemplate tpl) {
        DecodeResult result = new DecodeResult(tpl.signalsCap, tpl.extraInfoCap, tpl.mapArrayCap);
        String funcCode = tpl.typeTemplate.getCode();
        result.extraInfo.put(KEY_FUNC_CODE, funcCode);
        String hexBody = message.toUpperCase();
        byte[] fullBytes = CodeUtils.hexStringToByteArray(hexBody);

        for (CompiledField field : tpl.fields) {
            ContentTemplate template = field.ct;
            Object value = null;
            if (Boolean.TRUE.equals(template.getIsBitField()) && template.getBitLength() > 0) {
                int bitOffset = template.getBitOffset();
                int bitLength = template.getBitLength();
                long rawBits = readBitsFromBytes(fullBytes, bitOffset, bitLength);
                String hexStr = bitsToHexString(rawBits, bitLength);
                value = analysisHex2Format(hexStr, template, template.getFormat(), funcCode);
            } else {
                int bitOffset = template.getBitOffset();
                int byteOffset = bitOffset / 8;
                int byteLen = template.getByteLength();
                if (byteOffset + byteLen > fullBytes.length) {
                    break;
                }
                String hexStr = bytesToHexString(fullBytes, byteOffset, byteLen);
                value = analysisHex2Format(hexStr, template, template.getFormat(), funcCode);
            }
            if (value != null) {
                put(result, field, value);
            }
        }
        return result;
    }

    /** 按编译期计划落桶；未配置桶时按值的运行时类型分类 */
    private static void put(DecodeResult r, CompiledField f, Object v) {
        int bucket = f.bucket;
        if (bucket == BUCKET_BY_VALUE) {
            bucket = classifyByValue(v);
        }
        switch (bucket) {
            case BUCKET_SIGNALS:
                if (!(v instanceof Number)) {
                    throw bucketTypeError(f.outKey, "signals", "Number", v);
                }
                r.signals.put(f.outKey, ((Number) v).doubleValue());
                break;
            case BUCKET_MAP_ARRAY:
                if (!(v instanceof double[])) {
                    throw bucketTypeError(f.outKey, "map_array", "double[]", v);
                }
                r.mapArray.put(f.outKey, (double[]) v);
                break;
            default:
                r.extraInfo.put(f.outKey, v == null ? null : String.valueOf(v));
        }
    }

    private static IllegalArgumentException bucketTypeError(
            String key, String bucket, String expected, Object value) {
        String actual = value == null ? "null" : value.getClass().getName();
        return new IllegalArgumentException(
                "字段 '" + key + "' 配置到 " + bucket
                        + "，期望 " + expected + "，实际类型为 " + actual);
    }

    private static int classifyByValue(Object v) {
        if (v instanceof Number) return BUCKET_SIGNALS;
        if (v != null && v.getClass().isArray()) return BUCKET_MAP_ARRAY;
        if( v instanceof List) return BUCKET_MAP_ARRAY;
        return BUCKET_EXTRA_INFO; // String 及其他一律兜底进 extra_info
    }

    // ==================== SysModuleVer 专用路径 ====================
    private DecodeResult decodeSysModuleVer(String message, CompiledTemplate tpl) {
        Map<String, Object> legacy = tpl.typeTemplate.isUseBitParsing()
                ? analysisMessageBitLegacy(message, tpl)
                : analysisMessageByteLegacy(message, tpl);
        Map<String, Object> converted = VersionHexDecoderUtils.extractVersionHexToDec(legacy);

        DecodeResult result = new DecodeResult(tpl.signalsCap, tpl.extraInfoCap, tpl.mapArrayCap);
        result.getExtraInfo().put(KEY_FUNC_CODE, tpl.typeTemplate.getCode());
        for (CompiledField f : tpl.fields) {
            String rawKey = f.ct.getKey();
            if (rawKey != null && converted.containsKey(rawKey)) {
                put(result, f, converted.get(rawKey));
            }
        }
        return result;
    }

    /** 单 Map 解析（原字段 key），仅供 SysModuleVer 专用路径使用 */
    private Map<String, Object> analysisMessageByteLegacy(String message, CompiledTemplate tpl) {
        Map<String, Object> result = new HashMap<>();
        String funcCode = tpl.typeTemplate.getCode();
        result.put(KEY_FUNC_CODE, funcCode);
        String hexBody = message.toUpperCase();

        int analysisIndex = 0;
        for (CompiledField field : tpl.fields) {
            ContentTemplate currentContent = field.ct;
            int byteLength = currentContent.getByteLength();
            int strLength = (byteLength == SPECIAL_LENGTH)
                    ? hexBody.length() - analysisIndex
                    : byteLength * 2;
            if (analysisIndex > hexBody.length() - 1 || analysisIndex + strLength > hexBody.length()) {
                return result;
            }
            String hexStr = hexBody.substring(analysisIndex, analysisIndex + strLength);
            Object value = analysisHex2Format(hexStr, currentContent, currentContent.getFormat(), funcCode);
            result.put(currentContent.getKey(), value);
            analysisIndex += strLength;
        }
        return result;
    }

    /** 位解析单 Map 版本（原字段 key），仅供 SysModuleVer 专用路径使用 */
    private Map<String, Object> analysisMessageBitLegacy(String message, CompiledTemplate tpl) {
        Map<String, Object> result = new HashMap<>();
        String funcCode = tpl.typeTemplate.getCode();
        result.put(KEY_FUNC_CODE, funcCode);
        String hexBody = message.toUpperCase();
        byte[] fullBytes = CodeUtils.hexStringToByteArray(hexBody);

        for (CompiledField field : tpl.fields) {
            ContentTemplate template = field.ct;
            Object value = null;
            if (Boolean.TRUE.equals(template.getIsBitField()) && template.getBitLength() > 0) {
                long rawBits = readBitsFromBytes(fullBytes, template.getBitOffset(), template.getBitLength());
                String hexStr = bitsToHexString(rawBits, template.getBitLength());
                value = analysisHex2Format(hexStr, template, template.getFormat(), funcCode);
            } else {
                int byteOffset = template.getBitOffset() / 8;
                int byteLen = template.getByteLength();
                if (byteOffset + byteLen > fullBytes.length) {
                    break;
                }
                String hexStr = bytesToHexString(fullBytes, byteOffset, byteLen);
                value = analysisHex2Format(hexStr, template, template.getFormat(), funcCode);
            }
            if (value != null) {
                result.put(template.getKey(), value);
            }
        }
        return result;
    }

    // ==================== 位/字节工具 ====================

    private static long readBitsFromBytes(byte[] bytes, int bitOffset, int bitLength) {
        long result = 0;
        for (int i = 0; i < bitLength; i++) {
            int globalBitPos = bitOffset + i;
            int byteIdx = globalBitPos / 8;
            int bitIdx = globalBitPos % 8;
            if (byteIdx >= bytes.length) break;
            int bitVal = (bytes[byteIdx] >> bitIdx) & 1;
            result |= ((long) bitVal) << i;
        }
        return result;
    }

    private static String bitsToHexString(long value, int bitLength) {
        int hexLen = (bitLength + 3) / 4;
        return String.format("%0" + hexLen + "X", value);
    }

    private static String bytesToHexString(byte[] bytes, int offset, int length) {
        StringBuilder sb = new StringBuilder(length * 2);
        for (int i = 0; i < length; i++) {
            sb.append(String.format("%02X", bytes[offset + i]));
        }
        return sb.toString();
    }


    private static Object analysisHex2Format(String hexStr, ContentTemplate contentTemplate, String format, String funcCode) {
        Object value = "";
        switch (format) {
            case FORMAT_STR:
                value = analysisHex2Str(hexStr, contentTemplate, funcCode);
                break;
            case FORMAT_BINARY:
                value = analysisHex2Binary(hexStr, contentTemplate);
                break;
            case FORMAT_BINARY_HIGH:
                value = analysisHex2BinaryHigh(hexStr, contentTemplate);
                break;
            case FORMAT_INT:
                value = analysisHex2Int(hexStr, contentTemplate);
                break;
            case FORMAT_SIGNED_INT:
                value = analysisHex2SignedInt(hexStr, contentTemplate);
                break;
            case FORMAT_DOUBLE:
                value = analysisHex2Double(hexStr, contentTemplate);
                break;
            case FORMAT_SIGNED_DOUBLE:
                value = analysisHex2SignedDouble(hexStr, contentTemplate);
                break;
            case FORMAT_TIME:
                value = analysisHex2Time(hexStr, contentTemplate);
                break;
            case FORMAT_ARRAY_INT:
                value = analysisHex2ArrayInt(hexStr, contentTemplate);
                break;
            case FORMAT_ARRAY_SIGNED_INT:
                value = analysisHex2ArraySignedInt(hexStr, contentTemplate);
                break;
            case FORMAT_ARRAY_DOUBLE:
                value = analysisHex2ArrayDouble(hexStr, contentTemplate);
                break;
            case FORMAT_ARRAY_STR:
                value = analysisHex2ArrayStr(hexStr, contentTemplate);
                break;
            case FORMAT_ARRAY_BIN:
                value = analysisHex2ArrayBin(hexStr, contentTemplate);
                break;
            default:
                log.error("error: no such a method to analysis this format :{}", format);
                break;
        }
        return value;
    }

    private static String analysisHex2Time(String hexStr, ContentTemplate contentTemplate) {
        if (CP56TIME2A_DEFAULT_HEX.equals(hexStr)) {
            return CP56TIME2A_DEFAULT_TIME;
        }
        return CodeUtils.hexTime2StrTime(hexStr);
    }

    private static Double analysisHex2Double(String hexStr, ContentTemplate currentContent) {
        String encodeType = currentContent.getEncodeType();
        long value = 0;
        if (ENCODE_TYPE_BIN.equals(encodeType)) {
            hexStr = HexUtils.HighLowFlipFilter(hexStr);
            String tenStr = CodeUtils.hex2ten(hexStr);
            value = Long.valueOf(tenStr);
        } else if (ENCODE_TYPE_BCD.equals(encodeType)) {
            boolean b = CodeUtils.CanBCD(hexStr);
            if (!b) {
                log.error("error analysisHex2Double: hexStr too big cannot transform to bcd: {}, {}", hexStr, currentContent.getName());
                return 0d;
            }
            value = Long.valueOf(hexStr);
        }
        BigDecimal bd = getBigDecimal(currentContent, value);
        return bd.doubleValue();
    }

    private static BigDecimal getBigDecimal(ContentTemplate currentContent, long value) {
        BigDecimal rate = currentContent.getRate();
        int offset = currentContent.getOffset();
        BigDecimal bigDecimal = BigDecimal.valueOf(value).multiply(rate).add(BigDecimal.valueOf(offset));
        return bigDecimal.setScale(rate.stripTrailingZeros().scale(), RoundingMode.HALF_UP);
    }

    private static Double analysisHex2SignedDouble(String hexStr, ContentTemplate currentContent) {
        String encodeType = currentContent.getEncodeType();
        long value = 0;
        if (ENCODE_TYPE_BIN.equals(encodeType)) {
            hexStr = HexUtils.HighLowFlipFilter(hexStr);
            String tenStr = CodeUtils.hex2ten(hexStr);
            value = Long.valueOf(tenStr);
        } else if (ENCODE_TYPE_BCD.equals(encodeType)) {
            boolean b = CodeUtils.CanBCD(hexStr);
            if (!b) {
                log.error("error analysisHex2SignedDouble: hexStr too big cannot transform to bcd: {}, {}", hexStr, currentContent.getName());
                return 0d;
            }
            value = Long.valueOf(hexStr);
        }
        value = CodeUtils.transferSignedNumber(value, hexStr.length() / 2);
        BigDecimal bd = getBigDecimal(currentContent, value);
        return bd.doubleValue();
    }

    private static Integer analysisHex2Int(String hexStr, ContentTemplate currentContent) {
        String encodeType = currentContent.getEncodeType();
        long value = 0;
        if (ENCODE_TYPE_BIN.equals(encodeType)) {
            hexStr = HexUtils.HighLowFlipFilter(hexStr);
            String tenStr = CodeUtils.hex2ten(hexStr);
            value = Long.valueOf(tenStr);
        }
        if (ENCODE_TYPE_BCD.equals(encodeType)) {
            boolean b = CodeUtils.CanBCD(hexStr);
            if (!b) {
                log.error("error analysisHex2Int: hexStr too big cannot transform to bcd: {}, {}", hexStr, currentContent.getName());
                return 0;
            }
            value = Long.valueOf(hexStr);
        }
        double rate = currentContent.getRate().doubleValue();
        int offset = currentContent.getOffset();
        return (int) (value * rate + offset);
    }

    private static long analysisHex2SignedInt(String hexStr, ContentTemplate contentTemplate) {
        String encodeType = contentTemplate.getEncodeType();
        long value = 0;
        if (ENCODE_TYPE_BIN.equals(encodeType)) {
            hexStr = HexUtils.HighLowFlipFilter(hexStr);
            String tenStr = CodeUtils.hex2ten(hexStr);
            value = Long.valueOf(tenStr);
        }
        if (ENCODE_TYPE_BCD.equals(encodeType)) {
            boolean b = CodeUtils.CanBCD(hexStr);
            if (!b) {
                log.error("error analysisHex2Int: hexStr too big cannot transform to bcd: {}, {}", hexStr, contentTemplate.getName());
                return 0;
            }
            value = Long.valueOf(hexStr);
        }
        value = CodeUtils.transferSignedNumber(value, hexStr.length() / 2);
        double rate = contentTemplate.getRate().doubleValue();
        int offset = contentTemplate.getOffset();
        double result = value * rate + offset;
        return (long) result;
    }

    private static String analysisHex2Str(String hexStr, ContentTemplate currentContent, String funcCode) {
        String encodeType = currentContent.getEncodeType();
        if (ENCODE_TYPE_ASCII.equals(encodeType)) {
            byte[] bytes = CodeUtils.hex2ascii(hexStr);
            return CodeUtils.ascii2Str(bytes);
        }
        if (ENCODE_TYPE_BIN.equals(encodeType)) {
            hexStr = HexUtils.HighLowFlipFilter(hexStr);
            if (funcCode != null && "CA".equalsIgnoreCase(funcCode)) {
                hexStr = transferCAMessage(hexStr);
            }
            return hexStr;
        }
        return hexStr;
    }

    private static String analysisHex2Binary(String hexStr, ContentTemplate contentTemplate) {
        String encodeType = contentTemplate.getEncodeType();
        if (ENCODE_TYPE_BIN.equals(encodeType)) {
            hexStr = HexUtils.HighLowFlipFilter(hexStr);
        }
        StringBuilder sb = new StringBuilder();
        int length = hexStr.length();
        for (int i = 0; i < length; i = i + 2) {
            if ((i + 2) > length) break;
            String doubleHex = hexStr.substring(i, i + 2);
            int i1 = Integer.parseInt(doubleHex, 16);
            String binaryString = Integer.toBinaryString(i1 & 0xFF);
            String binary = CodeUtils.prefixZero(binaryString, 8);
            sb.append(binary);
        }
        return sb.reverse().toString();
    }

    private static String analysisHex2BinaryHigh(String hexStr, ContentTemplate contentTemplate) {
        String encodeType = contentTemplate.getEncodeType();
        if (ENCODE_TYPE_BIN.equals(encodeType)) {
            hexStr = HexUtils.HighLowFlipFilter(hexStr);
        }
        StringBuilder sb = new StringBuilder();
        int length = hexStr.length();
        for (int i = 0; i < length; i = i + 2) {
            if ((i + 2) > length) break;
            String doubleHex = hexStr.substring(i, i + 2);
            int i1 = Integer.parseInt(doubleHex, 16);
            String binaryString = Integer.toBinaryString(i1 & 0xFF);
            String binary = CodeUtils.prefixZero(binaryString, 8);
            sb.append(binary);
        }
        return sb.toString();
    }

    private static double[] analysisHex2ArrayInt(String hexStr, ContentTemplate currentContent) {
        int splitLength = currentContent.getSplitLength();
        int arrayLength = hexStr.length() / splitLength;
        double[] resultArray = new double[arrayLength];
        for (int i = 0; i < arrayLength; i++) {
            if ((i + 1) * splitLength > hexStr.length()) break;
            String splitHex = hexStr.substring(i * splitLength, (i + 1) * splitLength);
            resultArray[i] = analysisHex2Int(splitHex, currentContent);
        }
        return resultArray;
    }

    private static double[] analysisHex2ArraySignedInt(String hexStr, ContentTemplate currentContent) {
        int splitLength = currentContent.getSplitLength();
        int arrayLength = hexStr.length() / splitLength;
        double[] resultArray = new double[arrayLength];
        for (int i = 0; i < arrayLength; i++) {
            if ((i + 1) * splitLength > hexStr.length()) break;
            String splitHex = hexStr.substring(i * splitLength, (i + 1) * splitLength);
            resultArray[i] = analysisHex2SignedInt(splitHex, currentContent);
        }
        return resultArray;
    }

    private static double[] analysisHex2ArrayDouble(String hexStr, ContentTemplate currentContent) {
        int splitLength = currentContent.getSplitLength();
        int arrayLength = hexStr.length() / splitLength;
        double[] resultArray = new double[arrayLength];
        for (int i = 0; i < arrayLength; i++) {
            if ((i + 1) * splitLength > hexStr.length()) break;
            String splitHex = hexStr.substring(i * splitLength, (i + 1) * splitLength);
            resultArray[i] = analysisHex2Double(splitHex, currentContent);
        }
        return resultArray;
    }

    private static Object analysisHex2ArrayStr(String hexStr, ContentTemplate currentContent) {
        int splitLength = currentContent.getSplitLength();
        int arrayLength = hexStr.length() / splitLength;
        String[] resultArray = new String[arrayLength];
        for (int i = 0; i < arrayLength; i++) {
            if ((i + 1) * splitLength > hexStr.length()) break;
            String splitHex = hexStr.substring(i * splitLength, (i + 1) * splitLength);
            resultArray[i] = analysisHex2Str(splitHex, currentContent, null);
        }
        return resultArray;
    }

    private static Object analysisHex2ArrayBin(String hexStr, ContentTemplate currentContent) {
        int splitLength = currentContent.getSplitLength();
        int arrayLength = hexStr.length() / splitLength;
        String[] resultArray = new String[arrayLength];
        for (int i = 0; i < arrayLength; i++) {
            if ((i + 1) * splitLength > hexStr.length()) break;
            String splitHex = hexStr.substring(i * splitLength, (i + 1) * splitLength);
            String binStr = analysisHex2Binary(splitHex, currentContent);
            resultArray[i] = binStr;
        }
        return resultArray;
    }

    // ==================== CA 报文特殊解析（与 V1 一致） ====================

    public static String transferCAMessage(String hexStr) {
        if (hexStr == null) return null;

        StringBuilder sb = new StringBuilder();
        // 软件版本号 6字节
        if (hexStr.length() == 6 * 2) {
            String bigVersion = hexStr.substring(0, 4);
            String bigVersionNumber = String.valueOf(Long.parseLong(bigVersion, 16));
            if (bigVersionNumber.length() < 5) {
                bigVersionNumber = CodeUtils.prefixZero(bigVersionNumber, 5);
            }
            int length = bigVersionNumber.length();
            sb.append(bigVersionNumber);
            sb.insert(length - 4, ".").insert(length + 1 - 2, ".");

            sb.append("_");
            for (int i = 0; i < 3; i++) {
                String s = hexStr.substring(4 + i * 2, 4 + (i + 1) * 2);
                String smallVersion = String.valueOf(Long.parseLong(s, 16));
                smallVersion = CodeUtils.prefixZero(smallVersion, 2);
                sb.append(smallVersion);
            }
            sb.append("(");
            String lastNumber = String.valueOf(Long.parseLong(hexStr.substring(10, 12), 16));
            sb.append(CodeUtils.prefixZero(lastNumber, 2));
            sb.append(")");
            return sb.toString();
        }
        // 硬件版本号 5字节
        if (hexStr.length() == 5 * 2) {
            String bigVersion = hexStr.substring(0, 2);
            String bigVersionNumber = String.valueOf(Long.parseLong(bigVersion, 16));
            if (bigVersionNumber.length() < 3) {
                bigVersionNumber = CodeUtils.prefixZero(bigVersionNumber, 3);
            }
            int length = bigVersionNumber.length();
            sb.append(bigVersionNumber);
            sb.insert(length - 2, ".").insert(length + 1 - 1, ".");

            sb.append("_");
            for (int i = 0; i < 3; i++) {
                String s = hexStr.substring(2 + i * 2, 2 + (i + 1) * 2);
                String smallVersion = String.valueOf(Long.parseLong(s, 16));
                smallVersion = CodeUtils.prefixZero(smallVersion, 2);
                sb.append(smallVersion);
            }
            sb.append("(");
            String lastNumber = String.valueOf(Long.parseLong(hexStr.substring(8, 10), 16));
            sb.append(CodeUtils.prefixZero(lastNumber, 2));
            sb.append(")");
            return sb.toString();
        }
        return hexStr;
    }
}
