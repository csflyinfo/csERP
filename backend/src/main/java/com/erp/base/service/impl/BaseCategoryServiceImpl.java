package com.erp.base.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.erp.base.entity.BaseCategory;
import com.erp.base.mapper.BaseCategoryMapper;
import com.erp.base.service.BaseCategoryService;
import org.springframework.stereotype.Service;

@Service
public class BaseCategoryServiceImpl extends ServiceImpl<BaseCategoryMapper, BaseCategory> implements BaseCategoryService {
}
