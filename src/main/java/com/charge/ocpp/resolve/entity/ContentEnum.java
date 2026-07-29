package com.charge.ocpp.resolve.entity;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serializable;

/**
 * 参数值对应枚举类
 */
@Data
@EqualsAndHashCode
public class ContentEnum implements Serializable {
    private static final long serialVersionUID=1L;
    /**
     * 编码内容
     */
    private String code;
    /**
     * 实际含义
     */
    private String value;
    /**
     * BINARY情况时，需要知道 起始位置
     */
    private int index;
    /**
     * BINARY情况时，需要知道 内容长度
     */
    private int length;
    /**
     * binary情况时，需要 key 表示参数key
     */
    private String key;
}