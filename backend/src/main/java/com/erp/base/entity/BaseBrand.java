package com.erp.base.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("base_brand")
public class BaseBrand {

    @TableId(type = IdType.INPUT)
    private String brandId;
    private String brandCode;
    private String brandName;
    private String simpleCode;
    private Integer goodsCount;
    private String status;

    public String getBrandId() { return brandId; }
    public void setBrandId(String brandId) { this.brandId = brandId; }

    public String getBrandCode() { return brandCode; }
    public void setBrandCode(String brandCode) { this.brandCode = brandCode; }

    public String getBrandName() { return brandName; }
    public void setBrandName(String brandName) { this.brandName = brandName; }

    public String getSimpleCode() { return simpleCode; }
    public void setSimpleCode(String simpleCode) { this.simpleCode = simpleCode; }

    public Integer getGoodsCount() { return goodsCount; }
    public void setGoodsCount(Integer goodsCount) { this.goodsCount = goodsCount; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
