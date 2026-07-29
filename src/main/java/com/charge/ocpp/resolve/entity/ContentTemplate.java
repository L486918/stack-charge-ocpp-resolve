package com.charge.ocpp.resolve.entity;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.List;


/**
 * 报文内容
 */
@Data
@EqualsAndHashCode
public class ContentTemplate implements Serializable {
    private static final long serialVersionUID=1L;
    private int id;
    /**
     * 名称
     */
    private String name;
    /**
     * 英文key
     */
    private String key;
    /**
     * 字节长度
     */
    private int byteLength;
    /**
     * 编码类型
     */
    private String encodeType;
    /**
     * 内容格式
     */
    private String format;
    /**
     * 倍率
     * 真实值 = 测量值/倍率 + 偏移量
     */

    private BigDecimal rate;

    /**
     * 偏移量
     * 真实值 = 测量值/倍率 + 偏移量
     */
    private int offset;

    /**
     * HIVE 中对应的字段key
     */
    private String hiveKey;

    /**
     * 对应的mapKey
     */
    private String mapKey;
    /**
     * 顺序
     */
    private String order;

    /**
     * 分隔长度
     */
    private int splitLength;

    /**
     * 对应枚举类名称
     */
    private List<ContentEnum> contentEnumList;

    /**
     * 循环段类型（0为非循环段，1为循环头，2为循环段中，3位循环段尾）
     */
    private int cycleType;

    /**
     * 位偏移量
     */
    private int bitOffset;

    /**
     * 位长度
     */
    private int bitLength;


    /**
     * 是否为位信号
     */
    private Boolean isBitField;
}