package com.erp.inventory.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.erp.inventory.entity.InvStockLedger;
import com.erp.inventory.mapper.InvStockLedgerMapper;
import com.erp.inventory.service.InvStockLedgerService;
import org.springframework.stereotype.Service;

@Service
public class InvStockLedgerServiceImpl extends ServiceImpl<InvStockLedgerMapper, InvStockLedger> implements InvStockLedgerService {
}
