package com.charge.ocpp.resolve.utils;

/**
 * 十六进制字符串工具类
 * 【高低位翻转】
 * 由于协议要求 低位在前，高位在后。所以对于非编码类型的值
 * 接收时：先byte[]转十六进制字符串，再做高低位翻转，最后根据需要十六进制转十进制/二进制
 * 发送时：先二进制/十进制，转十六进制字符串，再做高低位翻转，最后再做字符串转byte[]
 */
public class HexUtils {

    /**
     * 十六进制字符串高低位翻转
     *
     * @param hexStr 这里的十六进制字符串长度一定是双数！
     * @return 翻转后的十六进制字符串
     */
    public static String HighLowFlip(String hexStr) {
        StringBuilder targetStr = new StringBuilder();
        int length = hexStr.length();
        for (int i = length - 2; i >= 0; i = i - 2) {
            targetStr.append(hexStr, i, i + 2);
        }
        return targetStr.toString();
    }

    /**
     * @des 需要高低位翻转的翻转。否则不翻转 : 2,4,7字节才需要翻转，否则不需要翻转
     * @param hexStr
     * @return
     */
    public static String HighLowFlipFilter(String hexStr) {
        int byteLength = hexStr.length() / 2;
        if(byteLength == 2 || byteLength == 4 || byteLength == 7) {
            return HighLowFlip(hexStr);
        }

        return hexStr;
    }

    /**
     * 判断BIN类型长度是否需要翻转
     * @param byteLength
     * @return
     */
    public static boolean isHighLowFlip(int byteLength) {
        if(byteLength == 2 || byteLength == 4 || byteLength == 7) {
            return true;
        }

        return false;
    }

}