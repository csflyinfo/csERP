package com.erp.finance.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.erp.finance.entity.FinAr;
import com.erp.finance.mapper.FinArMapper;
import com.erp.finance.service.FinArService;
import org.springframework.stereotype.Service;

@Service
public class FinArServiceImpl extends ServiceImpl<FinArMapper, FinAr> implements FinArService {
}
