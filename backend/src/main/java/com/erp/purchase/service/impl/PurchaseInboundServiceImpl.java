package com.erp.purchase.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.erp.purchase.entity.PurchaseInbound;
import com.erp.purchase.mapper.PurchaseInboundMapper;
import com.erp.purchase.service.PurchaseInboundService;
import org.springframework.stereotype.Service;

@Service
public class PurchaseInboundServiceImpl extends ServiceImpl<PurchaseInboundMapper, PurchaseInbound> implements PurchaseInboundService {
}
