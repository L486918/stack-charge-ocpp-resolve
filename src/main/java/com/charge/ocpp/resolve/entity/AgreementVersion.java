package com.charge.ocpp.resolve.entity;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serializable;
import java.util.List;

@Data
@EqualsAndHashCode
public class AgreementVersion implements Serializable {
    private static final long serialVersionUID=1L;
    private int id;
    /**
     * 协议名称
     */
    private String name;
    /**
     * 协议版本
     */
    private String version;
    /**
     * 服务器接收报文解析配置
     */
    private List<TypeTemplate> acceptTypeTemplateList;
    /**
     * 服务器下发报文解析配置
     */
    private List<TypeTemplate> sendTypeTemplateList;
}