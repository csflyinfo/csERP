package com.erp.base.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.erp.base.entity.BaseSupplier;
import com.erp.base.mapper.BaseSupplierMapper;
import com.erp.base.service.BaseSupplierService;
import org.springframework.stereotype.Service;

@Service
public class BaseSupplierServiceImpl extends ServiceImpl<BaseSupplierMapper, BaseSupplier> implements BaseSupplierService {
}
