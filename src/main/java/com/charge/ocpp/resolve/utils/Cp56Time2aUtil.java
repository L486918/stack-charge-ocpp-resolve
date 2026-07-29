package com.charge.ocpp.resolve.utils;

import java.util.Calendar;
import java.util.Date;

public class Cp56Time2aUtil {
    public static byte[] toBytes(Date date) {
        byte[] result = new byte[7];
        final Calendar aTime = Calendar.getInstance();
        aTime.setTime(date);
        final int milliseconds = aTime.get(Calendar.SECOND) *1000+ aTime.get(Calendar.MILLISECOND);
        result[0] = (byte) (milliseconds % 256);
        result[1] = (byte) (milliseconds / 256);
        result[2] = (byte) aTime.get(Calendar.MINUTE);
        result[3] = (byte) aTime.get(Calendar.HOUR_OF_DAY);
        result[4] = (byte) aTime.get(Calendar.DAY_OF_MONTH);
        result[5] = (byte) (aTime.get(Calendar.MONTH)+1);
        result[6] = (byte) (aTime.get(Calendar.YEAR) % 100);
        return result;
    }

    /**
     * @param date
     * @return
     * @des 严格按照协议格式：
     * 2字节： 毫秒
     * 1字节：6bit & 0x3f 分钟
     * 1字节：5bit & 0x1f小时
     * 1字节：3bit & 0xe0周   5bit & 0x1f日
     * 1字节：4bit & 0x0f月
     * 1字节: 7bit & 0x7f 年
     */
    public static byte[] toStandardBytes(Date date) {
        byte[] result = new byte[7];
        final Calendar aTime = Calendar.getInstance();
        aTime.setTime(date);
        final int milliseconds = aTime.get(Calendar.SECOND) * 1000 + aTime.get(Calendar.MILLISECOND);
        result[0] = (byte) ((milliseconds >> 8) & 0xFF );
        result[1] = (byte) (milliseconds & 0xFF);
        result[2] = (byte) (aTime.get(Calendar.MINUTE) & 0x3f);
        result[3] = (byte) (aTime.get(Calendar.HOUR_OF_DAY) & 0x1f);
//        0-4是日期， 5-7是星期
        int weekday = aTime.get(Calendar.DAY_OF_WEEK);
        if (weekday == 1) {
            weekday = 7;
        } else {
            weekday = weekday - 1;
        }
        int day = (byte) aTime.get(Calendar.DAY_OF_MONTH);
        result[4] = (byte) ((weekday << 5) | (day & 0xff));
        result[5] = (byte) ((aTime.get(Calendar.MONTH) + 1) & 0x0f);
        result[6] = (byte) ((aTime.get(Calendar.YEAR) % 100) & 0x7f);
        return result;
    }


    public static Date toDate(byte[] bytes){
        int milliseconds1 = bytes[0] < 0 ? 256 + bytes[0] : bytes[0];
        int milliseconds2 = bytes[1] < 0 ? 256 + bytes[1] : bytes[1];
        int milliseconds = milliseconds1 + milliseconds2 * 256;
        int realMilliseconds =  milliseconds % 1000;
        int seconds =  milliseconds / 1000;
        // 位于 0011 1111
        int minutes = bytes[2] & 0x3f;
        // 位于 0001 1111
        int hours = bytes[3] & 0x1f;
        // 位于 0001 1111
        int days = bytes[4] & 0x1f;
        // 位于 0000 1111
        int months = bytes[5] & 0x0f;
        // 位于 0111 1111
        int years = bytes[6] & 0x7f;
        Calendar aTime = Calendar.getInstance();
        aTime.set(Calendar.MILLISECOND, realMilliseconds);
        aTime.set(Calendar.SECOND, seconds);
        aTime.set(Calendar.MINUTE, minutes);
        aTime.set(Calendar.HOUR_OF_DAY, hours);
        aTime.set(Calendar.DAY_OF_MONTH, days);
        aTime.set(Calendar.MONTH, months-1);
        aTime.set(Calendar.YEAR, years + 2000);
        return aTime.getTime();
    }
//
//
//    public static Date convertToCP56Time2a(byte[] bytes) {
//        int milliseconds1 = bytes[0] < 0 ? 256 + bytes[0] : bytes[0];
//        int milliseconds2 = bytes[1] < 0 ? 256 + bytes[1] : bytes[1];
//        int milliseconds = milliseconds1 + milliseconds2 * 256;
//        // 位于 0011 1111
//        int minutes = bytes[2] & 0x3f;
//        // 位于 0001 1111
//        int hours = bytes[3] & 0x1f;
//        // 位于 0000 1111
//        int days = bytes[4] & 0x0f;
//        // 位于 0001 1111
//        int months = bytes[5] & 0x0f;
//        // 位于 0111 1111
//        int years = bytes[6] & 0x7f;
//        Calendar aTime = Calendar.getInstance();
//        aTime.set(Calendar.MILLISECOND, milliseconds);
//        aTime.set(Calendar.MINUTE, minutes);
//        aTime.set(Calendar.HOUR_OF_DAY, hours);
//        aTime.set(Calendar.DAY_OF_MONTH, days);
//        aTime.set(Calendar.MONTH, months);
//        aTime.set(Calendar.YEAR, years + 2000);
//        return aTime.getTime();
//    }
//
//    public static byte[] convert2CP56Time2a(Date date) {
//        byte[] result = new byte[7];
//        final Calendar aTime = Calendar.getInstance();
//        aTime.setTime(date);
//        final int milliseconds = aTime.get(Calendar.MILLISECOND);
//
//        result[0] = (byte) (milliseconds % 256);
//        result[1] = (byte) (milliseconds / 256);
//        result[2] = (byte) aTime.get(Calendar.MINUTE);
//        result[3] = (byte) aTime.get(Calendar.HOUR_OF_DAY);
//        result[4] = (byte) aTime.get(Calendar.DAY_OF_MONTH);
//        result[5] = (byte) aTime.get(Calendar.MONTH);
//        result[6] = (byte) (aTime.get(Calendar.YEAR) % 100);
//        return result;
//    }
//


}