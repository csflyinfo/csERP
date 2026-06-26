package com.erp.purchase.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.erp.purchase.entity.PurchaseOrder;
import com.erp.purchase.mapper.PurchaseOrderMapper;
import com.erp.purchase.service.PurchaseOrderService;
import org.springframework.stereotype.Service;

@Service
public class PurchaseOrderServiceImpl extends ServiceImpl<PurchaseOrderMapper, PurchaseOrder> implements PurchaseOrderService {
}
