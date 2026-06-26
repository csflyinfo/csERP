package com.erp.sales.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.erp.sales.entity.SalesOrderDetail;
import com.erp.sales.mapper.SalesOrderDetailMapper;
import com.erp.sales.service.SalesOrderDetailService;
import org.springframework.stereotype.Service;

@Service
public class SalesOrderDetailServiceImpl extends ServiceImpl<SalesOrderDetailMapper, SalesOrderDetail> implements SalesOrderDetailService {
}
