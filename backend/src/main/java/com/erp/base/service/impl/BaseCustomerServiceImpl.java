package com.erp.base.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.erp.base.entity.BaseCustomer;
import com.erp.base.mapper.BaseCustomerMapper;
import com.erp.base.service.BaseCustomerService;
import org.springframework.stereotype.Service;

@Service
public class BaseCustomerServiceImpl extends ServiceImpl<BaseCustomerMapper, BaseCustomer> implements BaseCustomerService {
}
