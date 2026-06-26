package com.erp.base.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.erp.base.entity.BaseUnit;
import com.erp.base.mapper.BaseUnitMapper;
import com.erp.base.service.BaseUnitService;
import org.springframework.stereotype.Service;

@Service
public class BaseUnitServiceImpl extends ServiceImpl<BaseUnitMapper, BaseUnit> implements BaseUnitService {
}
