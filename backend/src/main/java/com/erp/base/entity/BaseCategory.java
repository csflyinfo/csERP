package com.erp.base.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("base_category")
public class BaseCategory {

    @TableId(type = IdType.INPUT)
    private String categoryId;
    private String parentId;
    private String parentCode;
    private String categoryCode;
    private String categoryName;
    private String defaultTaxRate;
    private Integer goodsCount;
    private String status;

    public String getCategoryId() { return categoryId; }
    public void setCategoryId(String categoryId) { this.categoryId = categoryId; }

    public String getParentId() { return parentId; }
    public void setParentId(String parentId) { this.parentId = parentId; }

    public String getParentCode() { return parentCode; }
    public void setParentCode(String parentCode) { this.parentCode = parentCode; }

    public String getCategoryCode() { return categoryCode; }
    public void setCategoryCode(String categoryCode) { this.categoryCode = categoryCode; }

    public String getCategoryName() { return categoryName; }
    public void setCategoryName(String categoryName) { this.categoryName = categoryName; }

    public String getDefaultTaxRate() { return defaultTaxRate; }
    public void setDefaultTaxRate(String defaultTaxRate) { this.defaultTaxRate = defaultTaxRate; }

    public Integer getGoodsCount() { return goodsCount; }
    public void setGoodsCount(Integer goodsCount) { this.goodsCount = goodsCount; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
