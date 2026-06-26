package com.erp.purchase.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.erp.purchase.entity.PurchaseOrderDetail;
import com.erp.purchase.mapper.PurchaseOrderDetailMapper;
import com.erp.purchase.service.PurchaseOrderDetailService;
import org.springframework.stereotype.Service;

@Service
public class PurchaseOrderDetailServiceImpl extends ServiceImpl<PurchaseOrderDetailMapper, PurchaseOrderDetail> implements PurchaseOrderDetailService {
}
