package com.erp.sales.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.erp.sales.entity.SalesOrder;
import com.erp.sales.mapper.SalesOrderMapper;
import com.erp.sales.service.SalesOrderService;
import org.springframework.stereotype.Service;

@Service
public class SalesOrderServiceImpl extends ServiceImpl<SalesOrderMapper, SalesOrder> implements SalesOrderService {
}
