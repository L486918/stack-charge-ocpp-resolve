package com.charge.ocpp.resolve;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.CollectionType;
import com.charge.ocpp.resolve.entity.AgreementVersion;
import com.charge.ocpp.resolve.entity.ContentTemplate;
import com.charge.ocpp.resolve.entity.MessageContent;
import com.charge.ocpp.resolve.entity.TypeTemplate;
import com.charge.ocpp.resolve.utils.CodeUtils;
import com.charge.ocpp.resolve.utils.HexUtils;
import com.charge.ocpp.resolve.utils.HttpClientTool;
import com.charge.ocpp.resolve.utils.VersionHexDecoderUtils;
import lombok.extern.slf4j.Slf4j;
import java.io.Serializable;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public class OcppDecoder implements Serializable {

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


    private final Map<String, TypeTemplate> templateIndex;
    private final Map<String, String> hiveKeyMap;
    private final Map<String,String> signalsTypeKeyMap;
    private final String defaultVersion;

    /**
     * 根据协议版本配置构造解码器
     *
     * @param agreementVersionList 协议版本配置列表（从外部存储加载后反序列化得到）
     */
    public OcppDecoder(List<AgreementVersion> agreementVersionList) {
        Map<String, TypeTemplate> index = new HashMap<>();
        Map<String,String> hive_key_map = new HashMap<>();
        Map<String,String> singals_key_map = new HashMap<>();
        for (AgreementVersion av : agreementVersionList) {
            String version = av.getVersion();
            for (TypeTemplate t : av.getAcceptTypeTemplateList()) {
                index.put(indexKey("ACCEPT", version, t.getCode()), t);
                for(ContentTemplate ct:t.getContentTemplateList()){
                    hive_key_map.put(indexKey("ACCEPT", version, t.getCode(),ct.getKey()), ct.getHiveKey());
                    singals_key_map.put(indexKey("ACCEPT", version, t.getCode(),ct.getKey()), ct.getMapKey());
                }
            }
            for (TypeTemplate t : av.getSendTypeTemplateList()) {
                index.put(indexKey("SEND", version, t.getCode()), t);
                for(ContentTemplate ct:t.getContentTemplateList()){
                    hive_key_map.put(indexKey("SEND", version, t.getCode(),ct.getKey()), ct.getHiveKey());
                    singals_key_map.put(indexKey("SEND", version, t.getCode(),ct.getKey()), ct.getMapKey());
                }
            }
        }
        this.templateIndex = Collections.unmodifiableMap(index);
        this.hiveKeyMap=hive_key_map;
        this.signalsTypeKeyMap=singals_key_map;
        this.defaultVersion = agreementVersionList.get(agreementVersionList.size() - 1).getVersion();
    }

    /**
     * 直接从业务系统接口获取协议数据
     *
     * @param url 业务系统接口
     */
    public OcppDecoder(String url){
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

    private static String indexKey(String direction, String version, String messageId) {
        return direction + ":" + version + ":" + messageId.toUpperCase();
    }

    private static  String indexKey(String direction, String version, String messageId,String signalName) {
        return direction + ":" + version + ":" + messageId.toUpperCase() + ":" + signalName.toUpperCase();
    }


    /**
     * 解码十六进制报文字符串为 JSON
     *
     * @param message   十六进制报文 body
     * @param messageId 报文类型标识（如 "TerminalStatusData"）
     * @param version   协议版本号（如 "X1.0"）
     * @param  isChinese 是否是中文，决定的是发出来的json key是中文还是英文
     * @return 解析后的 JSON 字符串
     */
    public String decode(String message, String messageId, String version,boolean isChinese) {
        TypeTemplate typeTemplate = templateIndex.get(indexKey("ACCEPT", version, messageId));
        if (typeTemplate == null) {
            typeTemplate = templateIndex.get(indexKey("SEND", version, messageId));
        }
        if (typeTemplate == null) {
            log.error("未找到对应的解析模板, messageId={}, version={}", messageId, version);
            return "{}";
        }
        System.out.println("=====================================================================");
        System.out.println(templateIndex.toString());
        System.out.println("=====================================================================");
        Map<String, Object> jsonObject = analysisMessage(message, typeTemplate,isChinese);
        String res = writeValueAsString(jsonObject);
        if(messageId.equals("SysModuleVer")){
            res = VersionHexDecoderUtils.extractVersionHexToDec(res);
        }
        return res;
    }

    public Map<String,Object> decodeForHive(String message, String messageId, String version){
        TypeTemplate typeTemplate = templateIndex.get(indexKey("ACCEPT", version, messageId));
        if (typeTemplate == null) {
            typeTemplate = templateIndex.get(indexKey("SEND", version, messageId));
        }
        if (typeTemplate == null) {
            log.error("未找到对应的解析模板, messageId={}, version={}", messageId, version);
            return new HashMap<>();
        }

        Map<String, Object> res = analysisMessage(message, typeTemplate,false);
        if(messageId.equals("SysModuleVer")){
            res = VersionHexDecoderUtils.extractVersionHexToDec(res);
        }

        return res;
    }

    /**
     * 获取所有已加载的协议版本号
     */
    public Set<String> getLoadedVersions() {
        Set<String> versions = new HashSet<>();
        for (String key : templateIndex.keySet()) {
            // key format: "DIRECTION:VERSION:MESSAGE_ID"
            String[] parts = key.split(":");
            if (parts.length >= 2) {
                versions.add(parts[1]);
            }
        }
        return versions;
    }



    private static String writeValueAsString(Map<String, Object> map) {
        try {
            return MAPPER.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            log.error("序列化Map失败", e);
            return "{}";
        }
    }

    /**
     * 报文解析主函数
     *
     * @param message 十六进制报文
     * @param typeTemplate 解析类型和每个func_code对应的解析协议，决定是按字节解析还是按位解析以及解析模板
     * @return
     */
    private Map<String, Object> analysisMessage(String message, TypeTemplate typeTemplate,boolean isChinese) {
        if (typeTemplate.isUseBitParsing()) {
            return analysisMessageBit(message, typeTemplate,isChinese);
        } else {
            return analysisMessageByte(message, typeTemplate,isChinese);
        }
    }

    /**
     * 按字节解析原始报文
     *
     * @param message 十六进制报文
     * @param typeTemplate 解析类型和每个func_code对应的解析协议，决定是按字节解析还是按位解析以及解析模板
     * @return 解析出来的解析报文 json格式
     */
    private Map<String, Object> analysisMessageByte(String message, TypeTemplate typeTemplate,boolean isChinese) {
        Map<String, Object> result = new HashMap<>();
        String funcCode = typeTemplate.getCode();
        result.put(KEY_FUNC_CODE, funcCode);
        String hexBody = message.toUpperCase();

        int analysisIndex = 0;
        for (int i = 0; i < typeTemplate.getContentTemplateList().size(); i++) {
            ContentTemplate currentContent = typeTemplate.getContentTemplateList().get(i);
            String key = currentContent.getKey();
            if(isChinese){
                key = currentContent.getName();
            }
            String format = currentContent.getFormat();
            int byteLength = currentContent.getByteLength();

            int strLength;
            if (byteLength == SPECIAL_LENGTH) {
                strLength = hexBody.length() - analysisIndex;
            } else {
                strLength = byteLength * 2;
            }
            if (analysisIndex > hexBody.length() - 1 || analysisIndex + strLength > hexBody.length()) {
                return result;
            }
            String hexStr = hexBody.substring(analysisIndex, analysisIndex + strLength);
            Object value = analysisHex2Format(hexStr, currentContent, format, funcCode);
            result.put(key, value);
            analysisIndex = analysisIndex + strLength;
        }
        return result;
    }

    /**
     * 按位解析原始报文
     *
     * @param message 十六进制报文
     * @param typeTemplate 解析类型和每个func_code对应的解析协议，决定是按字节解析还是按位解析以及解析模板
     * @return 解析出来的解析报文 json格式
     */
    private Map<String, Object> analysisMessageBit(String message, TypeTemplate typeTemplate,boolean isChinese) {
        Map<String, Object> result = new HashMap<>();
        String funcCode = typeTemplate.getCode();
        result.put(KEY_FUNC_CODE, funcCode);
        String hexBody = message.toUpperCase();
        byte[] fullBytes = CodeUtils.hexStringToByteArray(hexBody);
        for (ContentTemplate template : typeTemplate.getContentTemplateList()) {
            String key = template.getKey();
            if(isChinese){
                key = template.getName();
            }
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
                result.put(key, value);
            }
        }
        return result;
    }

    /**
     *从字节数组中提取指定位段的数据，并将其转换为long类型
     *
     * @param bytes 字节数组
     * @param bitOffset 偏移量
     * @param bitLength 需要读取的位的数量
     * @return 提取并组合后的 long 类型数值。如果读取过程中超出数组边界，超出部分视为 0。
     */
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

    /**
     *将长整型数值转换为指定比特长度对应的十六进制字符串表示。
     *
     * @param value 需要转换的长整型数值
     * @param bitLength 该数值有效的比特位数
     * @return 格式化后的十六进制字符串 (大写)。
     */
    private static String bitsToHexString(long value, int bitLength) {
        int hexLen = (bitLength + 3) / 4;
        return String.format("%0" + hexLen + "X", value);
    }


    /**
     * 将字节数组的指定范围转换为十六进制字符串。
     *
     * @param bytes  源字节数组
     * @param offset 起始索引（从该位置开始读取）
     * @param length 需要转换的字节数量
     * @return 大写十六进制字符串（每字节两位，不足补零）
     */
    private static String bytesToHexString(byte[] bytes, int offset, int length) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < length; i++) {
            sb.append(String.format("%02X", bytes[offset + i]));
        }
        return sb.toString();
    }





    /**
     *  按照格式路由到不同的解析方法类中
     * @param hexStr  十六进制原始报文
     * @param contentTemplate 报文内容类
     * @param format 报文格式
     * @param funcCode funcCode
     * @return 返回解析后的报文对象
     */
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
            if (MessageContent.CA.equalsIgnoreCase(funcCode)) {
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
        String result = sb.reverse().toString();
        return result;
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

    private static Object analysisHex2ArrayInt(String hexStr, ContentTemplate currentContent) {
        int splitLength = currentContent.getSplitLength();
        int arrayLength = hexStr.length() / splitLength;
        int[] resultArray = new int[arrayLength];
        for (int i = 0; i < arrayLength; i++) {
            if ((i + 1) * splitLength > hexStr.length()) break;
            String splitHex = hexStr.substring(i * splitLength, (i + 1) * splitLength);
            resultArray[i] = analysisHex2Int(splitHex, currentContent);
        }
        return resultArray;
    }

    private static Object analysisHex2ArraySignedInt(String hexStr, ContentTemplate currentContent) {
        int splitLength = currentContent.getSplitLength();
        int arrayLength = hexStr.length() / splitLength;
        long[] resultArray = new long[arrayLength];
        for (int i = 0; i < arrayLength; i++) {
            if ((i + 1) * splitLength > hexStr.length()) break;
            String splitHex = hexStr.substring(i * splitLength, (i + 1) * splitLength);
            resultArray[i] = analysisHex2SignedInt(splitHex, currentContent);
        }
        return resultArray;
    }

    private static Object analysisHex2ArrayDouble(String hexStr, ContentTemplate currentContent) {
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

    // ==================== CA 报文特殊解析 ====================

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