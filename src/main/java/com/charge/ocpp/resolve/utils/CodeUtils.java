package com.charge.ocpp.resolve.utils;

import java.nio.charset.StandardCharsets;
import java.util.Date;

public class CodeUtils {


    /**
     * 十六进制bcd的数字字符串编成BCD格式字节数组
     * BCD: 使用4bit表示1个10进制的位
     *
     * @param str 字符串
     * @return BCD码
     */
    public static byte[] hex2bcd(String str) {
//        int length = str.length();
//        long l = Long.parseLong(str, 16);
//        String l2 = String.valueOf(l);
//
//        StringBuilder sb = new StringBuilder();
//        int length1 = l2.length();
//
//        // 补位
//        if(length1 < length) {
//            for (int i = 0; i < length - length1; i++) {
//                sb.append("0");
//            }
//        }
//        sb.append(l2);
//
//        // 转byte 数组
//        String s = sb.toString();
//        String[] split = s.split("");
//        byte[] bytes = new byte[length / 2];
//        for (int i = 0; i < split.length; i+=2) {
//            byte b = (byte) ((Byte.parseByte(split[i], 10) << 4) | Byte.parseByte(split[i+1], 10));
//            bytes[i / 2] = b;
//        }
        // 16进制已经是bcd格式的了，直接转byte数组
        byte[] bytes = new byte[str.length() / 2];
        int j = 0;
        for (int i = 0; i < bytes.length; i++) {
            char c0 = str.charAt(j++);
            char c1 = str.charAt(j++);
            bytes[i] = (byte) ((parse(c0) << 4) | parse(c1));
        }
        return bytes;
    }

    public static int parse(char c) {
        if (c >= 'a') {
            return (c - 'a' + 10) & 0x0f;
        }
        if (c >= 'A') {
            return (c - 'A' + 10) & 0x0f;
        }
        return (c - '0') & 0x0f;
    }

    /**
     * bcd字节码数组解码为十六进制数字字符串
     *
     * @param bytes 字节码数组, BCD编码的
     * @return 字符串
     */
    public static String bcd2hex(byte[] bytes) {
        StringBuilder stringBuilder = new StringBuilder();
        for (byte b : bytes) {
            int high = (b & 0xF0) >>> 4;
            int low = b & 0x0F;
            stringBuilder.append(Integer.toHexString(high));
            stringBuilder.append(Integer.toHexString(low));
        }
        return stringBuilder.toString();

    }


    /**
     * 十六进制字符串编码为BIN编码字节数组
     *
     * @param str 字符串
     * @return bin编码
     */
    public static byte[] hex2bin(String str) {
        int len = str.length();
        byte[] bytes = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            int firstDigit = Character.digit(str.charAt(i), 16);
            int secondDigit = Character.digit(str.charAt(i + 1), 16);
            int value = (firstDigit << 4) + secondDigit;
            bytes[i / 2] = (byte) value;
        }
        return bytes;
    }

    public static byte hex2binSingle(String str) {
        if(str.length() < 2) throw new RuntimeException("hex2binSingle: " + str);
        int firstDigit = Character.digit(str.charAt(0), 16);
        int secondDigit = Character.digit(str.charAt(1), 16);
        int value = (firstDigit << 4) + secondDigit;
        return (byte) value;
    }

    /**
     * BIN字节码数组解码为十六进制字符串
     *
     * @param bytes bin编码
     * @return 字符串
     */
    public static String bin2hex(byte[] bytes) {
        StringBuilder stringBuilder = new StringBuilder();
        for (byte b : bytes) {
            stringBuilder.append(String.format("%02x", b));
        }
        return stringBuilder.toString();
    }

    /**
     * ASCII字节码数组解码为十六进制字符串
     *
     * @param bytes ascii编码
     * @return 十六进制字符串
     */
    public static String ascii2Hex(byte[] bytes) {
        StringBuilder stringBuilder = new StringBuilder();
        for (byte b : bytes) {
            stringBuilder.append(String.format("%02x", b));
        }
        return stringBuilder.toString();
    }

    /**
     * 十六进制字符串编码为ASCII字节码数组
     *
     * @param str 字符串
     * @return ASCII字节码数组
     */
    public static byte[] hex2ascii(String str) {
        int length = str.length();
        byte[] bytes = new byte[length / 2];
        for (int i = 0; i < length; i += 2) {
            bytes[i / 2] = (byte) ((Character.digit(str.charAt(i), 16) << 4) + Character.digit(str.charAt(i + 1), 16));
        }
        return bytes;
    }

    public static byte[] hexStringToByteArray(String hex) {
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                    + Character.digit(hex.charAt(i+1), 16));
        }
        return data;
    }

    /**
     * 字节码按 UTF-8解码为字符串
     *
     * @param bytes 字节码
     * @return 字符串
     */
    public static String utf82str(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /**
     * 字符串按UTF-8编码为字节码数组
     *
     * @param str 字符串
     * @return 字节码数组
     */
    public static byte[] str2utf8(String str) {
        return str.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 十进制字符串转十六进制字符串
     *
     * @param ten 十进制字符串
     * @return 十六进制字符串 长度为8的倍数
     */
    public static String ten2hex(String ten) {
        String format = String.format("%08x", Long.parseLong(ten));
        return format;
    }

    /**
     * 十六进制字符串转十进制字符串
     *
     * @param hex 十六进制字符串
     * @return 十进制字符串
     */
    public static String hex2ten(String hex) {
        String ten = String.valueOf(Long.parseLong(hex, 16));
        return ten;
    }

    /**
     * 十六进制字符串转二进制字符串
     *
     * @param hexString 十六进制字符串
     * @return 二进制字符串 长度为8 的倍数
     */
    public static String hexStrToBinaryStr(String hexString) {

        if (hexString == null || hexString.equals("")) {
            return null;
        }
        StringBuffer sb = new StringBuffer();
        // 将每一个十六进制字符分别转换成一个四位的二进制字符
        for (int i = 0; i < hexString.length(); i++) {
            String indexStr = hexString.substring(i, i + 1);
            String binaryStr = Integer.toBinaryString(Integer.parseInt(indexStr, 16));
            while (binaryStr.length() < 4) {
                binaryStr = "0" + binaryStr;
            }
            sb.append(binaryStr);
        }

        return sb.toString();
    }

    /**
     * 二进制字符串转十进制字符串
     *
     * @param binaryString 二进制字符串
     * @return 十进制字符串
     */
    public static String binaryToDecimal(String binaryString) {
        int sum = 0;
        for (int i = 0; i < binaryString.length(); i++) {
            char ch = binaryString.charAt(i);
            if (ch > '2' || ch < '0')
                throw new NumberFormatException(String.valueOf(i));
            sum = sum * 2 + (binaryString.charAt(i) - '0');
        }
        return String.valueOf(sum);
    }

    /**
     * 十六进制时间【byte通过bin码转十六进制字符串的结果】
     *
     * @param hexTime 十六进制字符串
     * @return 十进制年月日时分秒明文字符串
     */
    public static String hexTime2StrTime(String hexTime) {
        byte[] timeBinBytes = CodeUtils.hex2bin(hexTime);
        Date date = Cp56Time2aUtil.toDate(timeBinBytes);
        return date.toInstant().toString();
    }


    /**
     * ASCII码转字符 【与十六进制无关】
     *
     * @param bytes ascii码
     * @return ascii码表对应字符
     */
    public static String ascii2Str(byte[] bytes) {
        StringBuilder str = new StringBuilder();
        for (byte aByte : bytes) {
            if (aByte == 0){
                break;
            }
            str.append((char) aByte);
        }
        return str.toString();
    }

    /**
     * 字符转ASCII码 【与十六进制无关】
     *
     * @param str 字符
     * @return ASCII码
     */
    public static byte[] str2ascii(String str) {
        byte[] bytes = new byte[str.length()];
        for (int i = 0; i < str.length(); i++) {
            bytes[i] = (byte) str.charAt(i);
        }
        return bytes;
    }


    /**
     * @param resource
     * @param length   补齐到的长度
     * @return
     * @des 左补0
     */
    public static String prefixZero(String resource, int length) {
        int resourceLength = resource.length();
        if (resourceLength >= length) return resource;

        StringBuilder targetStr = new StringBuilder();
        int zeroNum = length - resourceLength;
        for (int i = 0; i < zeroNum; i++) {
            targetStr.append("0");
        }
        targetStr.append(resource);
        return targetStr.toString();
    }

    /**
     * @param hexStr
     * @return
     * @des 判断16进制字符串 是否可以转成 BCD字符串
     */
    public static boolean CanBCD(String hexStr) {
        for (int i = 0; i < hexStr.length(); i++) {
            char c = hexStr.charAt(i);
            if (c > '9') {
                return false;
            }
        }

        return true;
    }

    /**
     * @param value
     * @param byteLength
     * @return
     * @des 将无符号long数转成 有符号数
     */
    public static long transferSignedNumber(long value, int byteLength) {
        if (byteLength == 8) return value;

        int length = byteLength * 8;

        if (length <= 0 || length > 64) {
            throw new IllegalArgumentException("长度必须在 1 到 64 之间");
        }
        // 生成位掩码
        long mask = (1L << length) - 1;
        // 截取指定长度的位
        long maskedValue = value & mask;
        // 检查最高位是否为 1
        if ((maskedValue & (1L << (length - 1))) != 0) {
            // 将高位全部扩展为1
            maskedValue |= ~mask;
        }

        return maskedValue;
    }
}