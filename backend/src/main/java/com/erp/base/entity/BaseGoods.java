package com.erp.base.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;

@TableName("base_goods")
public class BaseGoods {

    @TableId(type = IdType.INPUT)
    private String goodsId;
    private String goodsCode;
    private String goodsName;
    private String spec;
    private String categoryName;
    private String brandName;
    private String baseUnit;
    private String barcode;
    private BigDecimal standardPrice;
    private BigDecimal latestPurchasePrice;
    private BigDecimal minSalePrice;
    private String goodsType;
    private Integer shelfLifeDays;
    private String storageProperty;
    private BigDecimal suggestedRetailPrice;
    private BigDecimal stockUpperLimit;
    private BigDecimal stockLowerLimit;
    private String defaultSupplier;
    private String defaultWarehouse;
    private Boolean canReturn;
    private BigDecimal currentStock;
    private String status;

    public String getGoodsId() { return goodsId; }
    public void setGoodsId(String goodsId) { this.goodsId = goodsId; }

    public String getGoodsCode() { return goodsCode; }
    public void setGoodsCode(String goodsCode) { this.goodsCode = goodsCode; }

    public String getGoodsName() { return goodsName; }
    public void setGoodsName(String goodsName) { this.goodsName = goodsName; }

    public String getSpec() { return spec; }
    public void setSpec(String spec) { this.spec = spec; }

    public String getCategoryName() { return categoryName; }
    public void setCategoryName(String categoryName) { this.categoryName = categoryName; }

    public String getBrandName() { return brandName; }
    public void setBrandName(String brandName) { this.brandName = brandName; }

    public String getBaseUnit() { return baseUnit; }
    public void setBaseUnit(String baseUnit) { this.baseUnit = baseUnit; }

    public String getBarcode() { return barcode; }
    public void setBarcode(String barcode) { this.barcode = barcode; }

    public BigDecimal getStandardPrice() { return standardPrice; }
    public void setStandardPrice(BigDecimal standardPrice) { this.standardPrice = standardPrice; }

    public BigDecimal getLatestPurchasePrice() { return latestPurchasePrice; }
    public void setLatestPurchasePrice(BigDecimal latestPurchasePrice) { this.latestPurchasePrice = latestPurchasePrice; }

    public BigDecimal getMinSalePrice() { return minSalePrice; }
    public void setMinSalePrice(BigDecimal minSalePrice) { this.minSalePrice = minSalePrice; }

    public String getGoodsType() { return goodsType; }
    public void setGoodsType(String goodsType) { this.goodsType = goodsType; }

    public Integer getShelfLifeDays() { return shelfLifeDays; }
    public void setShelfLifeDays(Integer shelfLifeDays) { this.shelfLifeDays = shelfLifeDays; }

    public String getStorageProperty() { return storageProperty; }
    public void setStorageProperty(String storageProperty) { this.storageProperty = storageProperty; }

    public BigDecimal getSuggestedRetailPrice() { return suggestedRetailPrice; }
    public void setSuggestedRetailPrice(BigDecimal suggestedRetailPrice) { this.suggestedRetailPrice = suggestedRetailPrice; }

    public BigDecimal getStockUpperLimit() { return stockUpperLimit; }
    public void setStockUpperLimit(BigDecimal stockUpperLimit) { this.stockUpperLimit = stockUpperLimit; }

    public BigDecimal getStockLowerLimit() { return stockLowerLimit; }
    public void setStockLowerLimit(BigDecimal stockLowerLimit) { this.stockLowerLimit = stockLowerLimit; }

    public String getDefaultSupplier() { return defaultSupplier; }
    public void setDefaultSupplier(String defaultSupplier) { this.defaultSupplier = defaultSupplier; }

    public String getDefaultWarehouse() { return defaultWarehouse; }
    public void setDefaultWarehouse(String defaultWarehouse) { this.defaultWarehouse = defaultWarehouse; }

    public Boolean getCanReturn() { return canReturn; }
    public void setCanReturn(Boolean canReturn) { this.canReturn = canReturn; }

    public BigDecimal getCurrentStock() { return currentStock; }
    public void setCurrentStock(BigDecimal currentStock) { this.currentStock = currentStock; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
