package com.charge.ocpp.resolve.entity;

import java.io.Serializable;

public class MessageContent implements Serializable {
    private static final long serialVersionUID=1L;
    /**
     * 报文固定的前缀长度
     */
    public static final int PRE_LENGTH = 14;

    /**
     * 帧类型字符串长度
     */
    public static final int FUNC_CODE_LENGTH = 2;
    /**
     * 序列号长度
     */
    public static final int SERIAL_NO_LENGTH = 4;
    /**
     *
     */
    public static final int DATA_LENGTH_LENGTH = 4;
    /**
     * 校验域长度
     */
    public static final int CHECK_AREA_LENGTH = 4;
    /**
     * 加密标识长度
     */
    public static final int ENCODE_SIGN_LENGTH = 2;
    /**
     * 帧类型所属报文起始下标
     */
    public static final int FUNC_CODE_INDEX = 12;
    /**
     * 消息体所属报文起始下标
     */
    public static final int BODY_INDEX = 14;
    /**
     * 序列号所属报文起始下标
     */
    public static final int SERIAL_NO_INDEX = 6;
    /**
     * 加密标识所属报文起始下标
     */
    public static final int ENCODE_SIGN_INDEX = 10;
    /**
     * 固定起始标识
     */
    public static final String START_SIGN = "68";
    /**
     * 默认加密标识
     */
    public static final String DEFAULT_ENCODE_TYPE = "00";
    /**
     * 预设校验域 0000(用于生成下发报文)
     */
    public static final String DEFAULT_CHECK_AREA = "0000";

    /**
     * 十六进制字符串转byte数组默认长度倍率
     * 公式：字符串长度 / 倍率 = byte数组长度
     */
    public static final int LENGTH_RATE = 2;

    /**
     * CA 软硬件报文帧类型
     */
    public static final String CA = "CA";

    /**
     * 标识心跳包应答 key
     */
    public static final String TIME = "time";
}