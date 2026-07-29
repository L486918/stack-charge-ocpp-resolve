package com.charge.ocpp.resolve.entity;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serializable;
import java.util.List;

/**
 * 报文类型
 */
@Data
@EqualsAndHashCode
public class TypeTemplate implements Serializable {
    private static final long serialVersionUID=1L;
    private int id;

    /**
     * g关联message主键id
     */
    private int MessageId;
    /**
     * 名称
     */
    private String name;
    /**
     * 功能码
     */
    private String code;

    /**
     * 报文类型
     */
    private String type;
    /**
     * 关联设备类型(HOST/PILE(分别表示主机/桩))
     */
    private String deviceType;
    /**
     * 内容
     */
    private List<ContentTemplate> contentTemplateList;
    /**
     * 请求url
     */
    private String url ;
    /**
     * 报文转发消息队列的TOPIC
     */
    private String topic ;

    /**
     * 应答帧类型码
     */
    private String responseCode;

    /**
     * 报文定义
     */
    private String messageDefinition;

    /**
     * 报文周期时间
     */
    private String messageCycleTime;

    /**
     * 报文长度
     */
    private int messageLength;

    /**
     * 报文发送的快速周期（ms）
     */
    private String msgCycleTimeFast;

    /**
     * 发送逻辑
     */
    private String sendLogic;
    /**
     * 版本
     */
    private String version;
    /**
     * 备注
     */
    private String remark;

    /**
     * 是否使用Bit解析
     */
    private boolean useBitParsing;
}