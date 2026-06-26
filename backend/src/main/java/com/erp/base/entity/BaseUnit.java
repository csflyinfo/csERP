package com.erp.base.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("base_unit")
public class BaseUnit {

    @TableId(type = IdType.INPUT)
    private String unitId;
    private String unitCode;
    private String unitName;
    private Boolean canBaseUnit;
    private Boolean canMiddleUnit;
    private Boolean canLargeUnit;
    private Integer goodsCount;
    private String status;

    public String getUnitId() { return unitId; }
    public void setUnitId(String unitId) { this.unitId = unitId; }

    public String getUnitCode() { return unitCode; }
    public void setUnitCode(String unitCode) { this.unitCode = unitCode; }

    public String getUnitName() { return unitName; }
    public void setUnitName(String unitName) { this.unitName = unitName; }

    public Boolean getCanBaseUnit() { return canBaseUnit; }
    public void setCanBaseUnit(Boolean canBaseUnit) { this.canBaseUnit = canBaseUnit; }

    public Boolean getCanMiddleUnit() { return canMiddleUnit; }
    public void setCanMiddleUnit(Boolean canMiddleUnit) { this.canMiddleUnit = canMiddleUnit; }

    public Boolean getCanLargeUnit() { return canLargeUnit; }
    public void setCanLargeUnit(Boolean canLargeUnit) { this.canLargeUnit = canLargeUnit; }

    public Integer getGoodsCount() { return goodsCount; }
    public void setGoodsCount(Integer goodsCount) { this.goodsCount = goodsCount; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
