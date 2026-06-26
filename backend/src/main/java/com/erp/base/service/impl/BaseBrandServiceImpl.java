package com.erp.base.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.erp.base.entity.BaseBrand;
import com.erp.base.mapper.BaseBrandMapper;
import com.erp.base.service.BaseBrandService;
import org.springframework.stereotype.Service;

@Service
public class BaseBrandServiceImpl extends ServiceImpl<BaseBrandMapper, BaseBrand> implements BaseBrandService {
}
