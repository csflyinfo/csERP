package com.erp.purchase.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.erp.purchase.entity.PurchaseInboundDetail;
import com.erp.purchase.mapper.PurchaseInboundDetailMapper;
import com.erp.purchase.service.PurchaseInboundDetailService;
import org.springframework.stereotype.Service;

@Service
public class PurchaseInboundDetailServiceImpl extends ServiceImpl<PurchaseInboundDetailMapper, PurchaseInboundDetail> implements PurchaseInboundDetailService {
}
