package com.erp.inventory.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.erp.inventory.entity.InvStockBalance;
import com.erp.inventory.mapper.InvStockBalanceMapper;
import com.erp.inventory.service.InvStockBalanceService;
import org.springframework.stereotype.Service;

@Service
public class InvStockBalanceServiceImpl extends ServiceImpl<InvStockBalanceMapper, InvStockBalance> implements InvStockBalanceService {
}
